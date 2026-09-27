package com.obd2dashboard.backend.archive

import java.security.MessageDigest
import java.time.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The archive lane's rules (contract §6), free of HTTP and of Google.
 *
 * **Store, then advance.** A run of lines is written to the [SegmentStore] first,
 * and only then recorded in the [SessionIndex], conditionally on nothing having
 * moved in between. So `ackedThrough` never names a line that is not durable
 * (§6.2), and two instances racing over one session cannot both win: the loser's
 * object is an orphan no index entry names, deleted when the session completes.
 *
 * **Every answer is true when given.** A request that loses a race reads the
 * index again and reports what is actually stored.
 */
public class ArchiveService(
    private val index: SessionIndex,
    private val store: SegmentStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** `PUT /v1/sessions/{id}` (§6.1). */
    public suspend fun open(car: String, id: String, body: ByteArray): Open {
        // "The session record's line": with or without its newline, but one line only,
        // or every index after it would be off by the lines it hid.
        val line0 = if (body.isNotEmpty() && body.last() == NEWLINE) body.copyOf(body.size - 1) else body
        if (line0.contains(NEWLINE)) return Open.BadRecord("the session record must be a single line")
        val header = when (val parsed = SessionHeader.parse(line0, id)) {
            is SessionHeader.Parsed.Ok -> parsed.header
            is SessionHeader.Parsed.Bad -> return Open.BadRecord(parsed.reason)
        }
        val line0Sha = sha256(line0)
        val existing = index.get(id)
        if (existing != null) return reopen(existing, car, header, line0, line0Sha)

        val segment = storeSegment(id, 0, 0, line0 + NEWLINE, singleLine(line0))
        val now = clock.instant()
        val record = SessionRecord(
            id = id, car = car, header = header, line0Sha256 = line0Sha, ackedThrough = 0,
            segments = listOf(segment), complete = false, sha256 = null, hashResets = 0, created = now, updated = now,
        )
        if (index.create(record)) return Open.Created(0)
        // Another request created it first; answer as a reopen of what it made.
        return reopen(index.get(id) ?: error("session $id vanished"), car, header, line0, line0Sha)
    }

    private suspend fun reopen(
        existing: SessionRecord,
        car: String,
        header: SessionHeader,
        line0: ByteArray,
        line0Sha: String,
    ): Open {
        if (existing.car != car) return Open.WrongCar
        if (existing.line0Sha256 == null) {
            // Created by the live lane (M4), without line 0: this PUT supplies it.
            val segment = storeSegment(existing.id, 0, 0, line0 + NEWLINE, singleLine(line0))
            if (index.setLine0(existing.id, header, line0Sha, segment, clock.instant())) return Open.Created(0)
            return reopen(index.get(existing.id)!!, car, header, line0, line0Sha)
        }
        if (existing.line0Sha256 != line0Sha) {
            return Open.BadRecord("this session's record differs from the one already stored")
        }
        return Open.Existing(existing.ackedThrough)
    }

    /**
     * A live `session` (§5.2) for [id]: creates the index entry if there is none,
     * with no line 0 yet, so the archive's `PUT` finds it (§6.1: "a live
     * `session` may have created the session first"). Since only streamed
     * sessions are archived (contract §10), this is usually how a session begins.
     */
    public suspend fun announce(car: String, id: String): Announce {
        val existing = index.get(id)
        if (existing != null) return if (existing.car == car) Announce.Ok else Announce.WrongCar
        val now = clock.instant()
        val record = SessionRecord(
            id = id, car = car, header = null, line0Sha256 = null, ackedThrough = -1,
            segments = emptyList(), complete = false, sha256 = null, hashResets = 0, created = now, updated = now,
        )
        if (index.create(record)) return Announce.Ok
        return if (index.get(id)?.car == car) Announce.Ok else Announce.WrongCar
    }

    /** `POST /v1/sessions/{id}/chunks` (§6.2): [lines] starting at index [first]. */
    public suspend fun append(car: String, id: String, first: Long, lines: LineBlock): Append {
        val record = index.get(id) ?: return Append.NotOpen
        if (record.car != car) return Append.WrongCar
        if (record.ackedThrough < 0) return Append.NotOpen
        Records.firstNonObject(lines)?.let { bad ->
            return Append.BadRecord("line ${first + bad} is not a JSON object")
        }
        return when (val plan = Trim.plan(record.ackedThrough, first, lines.size)) {
            is Trim.Plan.Gap -> Append.Gap(plan.missingFrom)
            Trim.Plan.Duplicate -> Append.Acked(record.ackedThrough)
            is Trim.Plan.Append -> {
                if (record.complete) return Append.BadRecord("the session is complete; it takes no new lines")
                val segment = storeSegment(id, plan.first, plan.last, lines.bytesFrom(plan.skip), lines, plan.skip)
                if (index.append(id, record.ackedThrough, segment, clock.instant())) {
                    Append.Acked(plan.last)
                } else {
                    Append.Acked(index.get(id)?.ackedThrough ?: return Append.NotOpen)
                }
            }
        }
    }

    /** `POST /v1/sessions/{id}/complete` (§6.3). */
    public suspend fun complete(car: String, id: String, lastIndex: Long, recordCount: Long, sha256: String): Complete {
        val record = index.get(id) ?: return Complete.NotOpen
        if (record.car != car) return Complete.WrongCar
        if (record.ackedThrough < 0) return Complete.NotOpen
        if (lastIndex < 0 || recordCount != lastIndex + 1) {
            return Complete.BadRecord("recordCount must be lastIndex + 1")
        }
        if (record.complete) {
            return if (record.ackedThrough == lastIndex && record.sha256.equals(sha256, ignoreCase = true)) {
                Complete.Done
            } else {
                Complete.BadRecord("the session is already complete, with a different end or hash")
            }
        }
        if (record.ackedThrough < lastIndex) return Complete.Gap(record.ackedThrough + 1)
        if (record.ackedThrough > lastIndex) {
            return Complete.BadRecord("the server holds ${record.ackedThrough + 1} lines, more than the session's $recordCount")
        }

        val hash = LineHash()
        val final = sessionKey(id)
        assemble(record, final, hash)
        val actual = hash.hex()
        if (!actual.equals(sha256, ignoreCase = true)) {
            store.delete(final)
            if (record.hashResets >= MAX_HASH_RESETS) {
                return Complete.BadRecord("the stored lines never match the session's hash; stopping after $MAX_HASH_RESETS resends")
            }
            if (!index.resetToLine0(id, record.ackedThrough, clock.instant())) return Complete.Gap(currentNext(id))
            record.segments.filter { it.first != 0L }.forEach { store.delete(it.key) }
            return Complete.Gap(1)
        }
        if (!index.complete(id, record.ackedThrough, actual, clock.instant())) return Complete.Gap(currentNext(id))
        // The whole session is durable in one object; segments and any race's orphans can go.
        store.deletePrefix(segmentsPrefix(id))
        return Complete.Done
    }

    /**
     * Streams every segment, in index order, into [final] and [hash].
     *
     * **Refuses rather than assemble a corrupt session**: segments must be
     * contiguous from 0 to `ackedThrough`, each holding exactly the lines it
     * claims. The hash alone would not catch an index that consistently lost a
     * range; this does.
     */
    private suspend fun assemble(record: SessionRecord, final: String, hash: LineHash) {
        val id = record.id
        store.write(final) { out ->
            var next = 0L
            for (segment in record.segments) {
                check(segment.first == next) { "session $id: segments are not contiguous at $next" }
                val bytes = store.read(segment.key)
                check(countLines(bytes) == segment.last - segment.first + 1) {
                    "session $id: segment ${segment.key} does not hold the lines it claims"
                }
                hash.addLines(bytes)
                out.write(bytes)
                next = segment.last + 1
            }
            check(next == record.ackedThrough + 1) { "session $id: segments end at ${next - 1}, not ${record.ackedThrough}" }
        }
    }

    /** A car's sessions, as the index holds them (the admin page, M6). */
    public suspend fun sessionsOf(car: String): List<SessionRecord> = index.listByCar(car)

    /** Deletes a session's objects and its index entry: the owner's decision (decision 16). */
    public suspend fun delete(id: String): Boolean {
        store.deletePrefix(sessionPrefix(id))
        return index.delete(id)
    }

    private suspend fun currentNext(id: String): Long = (index.get(id)?.ackedThrough ?: -1) + 1

    private suspend fun storeSegment(
        id: String,
        first: Long,
        last: Long,
        bytes: ByteArray,
        lines: LineBlock,
        skip: Int = 0,
    ): Segment {
        val key = segmentKey(id, first, last)
        store.put(key, bytes)
        return Segment(first, last, key, seqOf(lines.line(skip)), seqOf(lines.line(lines.size - 1)))
    }

    public sealed interface Open {
        public data class Created(val ackedThrough: Long) : Open
        public data class Existing(val ackedThrough: Long) : Open
        public data object WrongCar : Open
        public data class BadRecord(val reason: String) : Open
    }

    public sealed interface Announce {
        public data object Ok : Announce
        public data object WrongCar : Announce
    }

    public sealed interface Append {
        public data class Acked(val ackedThrough: Long) : Append
        public data class Gap(val missingFrom: Long) : Append
        public data object NotOpen : Append
        public data object WrongCar : Append
        public data class BadRecord(val reason: String) : Append
    }

    public sealed interface Complete {
        public data object Done : Complete
        public data class Gap(val missingFrom: Long) : Complete
        public data object NotOpen : Complete
        public data object WrongCar : Complete
        public data class BadRecord(val reason: String) : Complete
    }

    public companion object {
        /** A third mismatch would not change anything: the two sides disagree about the bytes. */
        public const val MAX_HASH_RESETS: Int = 2

        public fun sessionPrefix(id: String): String = "sessions/$id/"
        public fun segmentsPrefix(id: String): String = "sessions/$id/segments/"
        public fun sessionKey(id: String): String = "sessions/$id/session.jsonl.gz"
        public fun segmentKey(id: String, first: Long, last: Long): String =
            segmentsPrefix(id) + "%010d-%010d.jsonl.gz".format(first, last)

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun countLines(bytes: ByteArray): Long = bytes.count { it == NEWLINE }.toLong()

        private fun seqOf(line: ByteArray): Long? =
            (Records.parseObject(line)?.get("seq") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private const val NEWLINE: Byte = '\n'.code.toByte()

        private fun singleLine(line: ByteArray): LineBlock =
            (LineBlock.split(line + NEWLINE) as LineBlock.Split.Ok).lines
    }
}
