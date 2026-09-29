package com.obd2dashboard.backend.archive

import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            hashState = RunningSha256().update(line0 + NEWLINE).state(), // the running hash begins (M19.3)
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
            val hashState = RunningSha256().update(line0 + NEWLINE).state()
            if (index.setLine0(existing.id, header, line0Sha, segment, clock.instant(), hashState)) return Open.Created(0)
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
                val bytes = lines.bytesFrom(plan.skip)
                val segment = storeSegment(id, plan.first, plan.last, bytes, lines, plan.skip)
                // The running hash carried over exactly the lines acknowledged (M19.3).
                val hashState = record.hashState?.let { RunningSha256.restore(it).update(bytes).state() }
                if (index.append(id, record.ackedThrough, segment, clock.instant(), hashState)) {
                    Append.Acked(plan.last)
                } else {
                    Append.Acked(index.get(id)?.ackedThrough ?: return Append.NotOpen)
                }
            }
        }
    }

    /**
     * One `complete` per session at a time (the instance is one, decision 20):
     * a second, sent while the first assembles, would read segments the first
     * then deletes. It waits, and finds the session complete (contract §23).
     */
    private val completing = Array(COMPLETE_LOCKS) { Mutex() }

    /** `POST /v1/sessions/{id}/complete` (§6.3). */
    public suspend fun complete(car: String, id: String, lastIndex: Long, recordCount: Long, sha256: String): Complete =
        completing[Math.floorMod(id.hashCode(), COMPLETE_LOCKS)].withLock { completeNow(car, id, lastIndex, recordCount, sha256) }

    private suspend fun completeNow(car: String, id: String, lastIndex: Long, recordCount: Long, sha256: String): Complete {
        val record = index.get(id) ?: return Complete.NotOpen
        if (record.car != car) return Complete.WrongCar
        if (record.ackedThrough < 0) return Complete.NotOpen
        if (lastIndex < 0 || recordCount != lastIndex + 1) {
            return Complete.BadRecord("recordCount must be lastIndex + 1")
        }
        if (record.complete) {
            return if (record.ackedThrough == lastIndex && record.sha256.equals(sha256, ignoreCase = true)) {
                Complete.AlreadyDone
            } else {
                Complete.BadRecord("the session is already complete, with a different end or hash")
            }
        }
        if (record.ackedThrough < lastIndex) return Complete.Gap(record.ackedThrough + 1)
        if (record.ackedThrough > lastIndex) {
            return Complete.BadRecord("the server holds ${record.ackedThrough + 1} lines, more than the session's $recordCount")
        }

        record.hashState?.let { state -> return completeRunning(record, RunningSha256.restore(state).hex(), sha256) }
        // Opened before M19: no running hash, so hashed and assembled whole, as before.
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
     * `complete` with the running hash (M19.3): **compare and answer**. The
     * segments stay listed and are read as they are until [finish] makes them
     * one object; a mismatch starts again from line 0, as before.
     */
    private suspend fun completeRunning(record: SessionRecord, actual: String, sha256: String): Complete {
        val id = record.id
        contiguous(record)
        if (!actual.equals(sha256, ignoreCase = true)) {
            if (record.hashResets >= MAX_HASH_RESETS) {
                return Complete.BadRecord("the stored lines never match the session's hash; stopping after $MAX_HASH_RESETS resends")
            }
            val line0 = record.segments.first { it.first == 0L }
            val restart = RunningSha256().update(store.read(line0.key)).state()
            if (!index.resetToLine0(id, record.ackedThrough, clock.instant(), restart)) return Complete.Gap(currentNext(id))
            store.list(segmentsPrefix(id)).filter { it != line0.key }.forEach { store.delete(it) }
            return Complete.Gap(1)
        }
        if (!index.complete(id, record.ackedThrough, actual, clock.instant(), assembled = false)) return Complete.Gap(currentNext(id))
        return Complete.Done
    }

    /** The index's segments run from line 0 to `ackedThrough` without a gap (decision 17's authority, checked). */
    private fun contiguous(record: SessionRecord) {
        var next = 0L
        for (s in record.segments.sortedBy { it.first }) {
            check(s.first == next) { "session ${record.id}: segments are not contiguous at $next" }
            next = s.last + 1
        }
        check(next == record.ackedThrough + 1) { "session ${record.id}: segments end at ${next - 1}, not ${record.ackedThrough}" }
    }

    /**
     * **One object at last** (M19.3): a complete session's segments composed
     * into `session.jsonl.gz` on the store's side, the record marked
     * assembled, and the segments folder deleted. After `complete`'s answer,
     * and on start for any session left between. False if there was nothing
     * to do.
     */
    public suspend fun finish(id: String): Boolean = completing[Math.floorMod(id.hashCode(), COMPLETE_LOCKS)].withLock {
        val record = index.get(id) ?: return@withLock false
        if (!record.complete || record.segments.isEmpty()) return@withLock false
        contiguous(record)
        composeAll(sessionKey(id), record.segments.sortedBy { it.first }.map { it.key }, segmentsPrefix(id))
        if (!index.assembled(id, clock.instant())) return@withLock false
        store.deletePrefix(segmentsPrefix(id))
        superseded.remove(id)
        true
    }

    /** Complete sessions not yet one object (M19.3): what [finish] has left to do, after a restart. */
    public suspend fun unfinished(): List<String> = index.list().filter { it.complete && it.segments.isNotEmpty() }.map { it.id }

    /**
     * **Compaction** (M19.2): once [COMPACT_AT] segments follow line 0, the
     * first [SegmentStore.COMPOSE_MAX] of them (the piece so far and the chunks
     * after it) become one piece, composed on the store's side. A session stays
     * at most ~33 objects however long. **What a compaction replaces is
     * deleted by the next one** (about 3 minutes later), so a reader that took
     * the list before it has long finished; kept for good, every piece would
     * keep all the ones before it, growing with the square of the session's
     * length (M19.4's measurement). A restart forgets what's waiting; [finish]
     * deletes the folder whole. Only a session with a running hash (opened
     * since M19). True if it compacted.
     */
    public suspend fun compact(id: String): Boolean = completing[Math.floorMod(id.hashCode(), COMPLETE_LOCKS)].withLock {
        val record = index.get(id) ?: return@withLock false
        if (record.complete || record.hashState == null) return@withLock false
        val after = record.segments.filter { it.first != 0L }.sortedBy { it.first }
        if (after.size < COMPACT_AT) return@withLock false
        val run = after.take(SegmentStore.COMPOSE_MAX)
        val key = segmentKey(id, run.first().first, run.last().last)
        store.compose(key, run.map { it.key })
        val done = index.compact(id, run.map { it.key }, Segment(run.first().first, run.last().last, key, run.first().firstSeq, run.last().lastSeq), clock.instant())
        if (done) {
            superseded.put(id, run.map { it.key })?.forEach { store.delete(it) }
        } else {
            store.delete(key) // lost a race: this piece is no one's
        }
        done
    }

    /** What each session's last compaction replaced, deleted by its next (M19.4). */
    private val superseded = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** [target] composed of [keys] in order, in rounds of [SegmentStore.COMPOSE_MAX], the rounds' parts under [scratch]. */
    private suspend fun composeAll(target: String, keys: List<String>, scratch: String) {
        var parts = keys
        var round = 0
        while (parts.size > SegmentStore.COMPOSE_MAX) {
            round++
            parts = parts.chunked(SegmentStore.COMPOSE_MAX).mapIndexed { i, group ->
                "${scratch}compose-$round-$i".also { store.compose(it, group) }
            }
        }
        store.compose(target, parts)
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
        val segments = record.segments
        store.write(final) { out ->
            var next = 0L
            coroutineScope {
                // Read ahead, in order: a long drive is a hundred segments or more, and one at a time
                // took longer than the tablet waits for an answer (contract §23; JOURNAL: M17).
                val ahead = ArrayDeque<Deferred<ByteArray>>()
                var queued = 0
                fun fill() {
                    while (queued < segments.size && ahead.size < READ_AHEAD) {
                        val key = segments[queued++].key
                        ahead.addLast(async { store.read(key) })
                    }
                }
                fill()
                for (segment in segments) {
                    check(segment.first == next) { "session $id: segments are not contiguous at $next" }
                    val bytes = ahead.removeFirst().await()
                    fill()
                    check(countLines(bytes) == segment.last - segment.first + 1) {
                        "session $id: segment ${segment.key} does not hold the lines it claims"
                    }
                    hash.addLines(bytes)
                    out.write(bytes)
                    next = segment.last + 1
                }
            }
            check(next == record.ackedThrough + 1) { "session $id: segments end at ${next - 1}, not ${record.ackedThrough}" }
        }
    }

    /**
     * Streams every stored line of [record], in index order, to [reader]: the
     * completed log, or its segments up to `ackedThrough` (M7.1). Never holds a
     * whole session.
     */
    public suspend fun read(record: SessionRecord, reader: suspend (InputStream) -> Unit) {
        // Complete and one object; else (uploading, or complete and not yet composed, M19.3) its segments.
        if (record.complete && record.segments.isEmpty()) {
            store.readStream(sessionKey(record.id)) { reader(it) }
        } else {
            // The index lists only acked segments (decision 17), so these are exactly the stored lines.
            for (segment in record.segments.sortedBy { it.first }) {
                store.readStream(segment.key) { reader(it) }
            }
        }
    }

    /**
     * Lines of a session not yet one object, **from line [from] on**, each
     * with its index (M19.4): only the segments that reach it are read, and
     * lines before it in the first are skipped, whatever the segments are.
     */
    public suspend fun readLinesFrom(record: SessionRecord, from: Long, each: (Long, ByteArray) -> Unit) {
        check(!(record.complete && record.segments.isEmpty())) { "session ${record.id} is one object: read it whole" }
        for (segment in record.segments.sortedBy { it.first }) {
            if (segment.last < from) continue
            var index = segment.first
            store.readStream(segment.key) { stream ->
                LineSplitter { line -> if (index >= from) each(index, line); index++ }.feed(stream)
            }
        }
    }

    /**
     * Streams the segments of a session not yet one object that can hold a
     * record with `seq` [fromSeq] or later (M19.4: a refill after a reconnect
     * reads only the gap's). A segment that doesn't know its `seq`s is read.
     */
    public suspend fun readSegmentsFromSeq(record: SessionRecord, fromSeq: Long, reader: suspend (InputStream) -> Unit) {
        check(!(record.complete && record.segments.isEmpty())) { "session ${record.id} is one object: read it whole" }
        for (segment in record.segments.sortedBy { it.first }) {
            if (segment.lastSeq != null && segment.lastSeq < fromSeq) continue
            store.readStream(segment.key) { reader(it) }
        }
    }

    /**
     * The summary of a **complete** session: the stored one if it's current,
     * else built from its lines and stored (after `complete`, or on first view).
     * Null for a session still uploading, whose summary would change.
     */
    public suspend fun summary(id: String): SessionSummary? {
        val record = index.get(id) ?: return null
        if (!record.complete) return null
        record.summary?.takeIf { it.version == SessionSummary.VERSION }?.let { return it }
        val reader = SessionReader()
        read(record) { reader.read(it) }
        val summary = reader.summary()
        index.setSummary(id, summary)
        return summary
    }

    /**
     * The key of the session's prepared series (M7.2), building it if it isn't
     * stored yet, in one pass with the summary. A complete session's is built
     * once; one still uploading gets one per `ackedThrough`. Every other one
     * is then deleted. Null for an unknown session or one with no lines yet.
     */
    public suspend fun prepare(id: String): String? {
        val record = index.get(id) ?: return null
        if (record.ackedThrough < 0) return null
        val key = seriesKey(id, if (record.complete) null else record.ackedThrough)
        val thin = thinSeriesKey(id).takeIf { record.complete }
        if (key in store.list(key)) return key
        // One full build at a time (M19.6): an 8-hour session's peaks at ~178 MiB of a 384 MiB heap, two ~356.
        return building.withLock {
            if (key in store.list(key)) return@withLock key
            val builder = SeriesBuilder()
            // A complete session's thinned series, in the same pass (M19.6): what its page opens on.
            val thinned = thin?.let { ThinSeries() }
            val reader = SessionReader(also = { builder.record(it); thinned?.record(it) })
            read(record) { reader.read(it) }
            val summary = reader.summary()
            store.write(key) { builder.write(it, summary.started, summary.signals) }
            if (thin != null && thinned != null) store.write(thin) { thinned.write(it, summary.started, summary.signals, final = true) }
            if (record.complete && record.summary?.version != SessionSummary.VERSION) index.setSummary(id, summary)
            // Only the newest stays: earlier partial ones, and any of an older version.
            store.list(seriesPrefix(id)).filter { it != key && it != thin }.forEach { store.delete(it) }
            key
        }
    }

    /**
     * The key of a **complete** session's thinned series (M19.6), building it
     * if it isn't stored: its full series first, as [prepare], which builds
     * both; a session prepared before M19 gets its thinned one on its own,
     * one pass. Null for one not complete (the live series is thinned already).
     */
    public suspend fun prepareThin(id: String): String? {
        val record = index.get(id) ?: return null
        if (!record.complete) return null
        val thin = thinSeriesKey(id)
        if (thin in store.list(thin)) return thin
        prepare(id) ?: return null
        if (thin in store.list(thin)) return thin
        return building.withLock {
            if (thin in store.list(thin)) return@withLock thin
            val thinned = ThinSeries()
            val reader = SessionReader(also = thinned::record)
            read(record) { reader.read(it) }
            store.write(thin) { thinned.write(it, reader.summary().started, reader.summary().signals, final = true) }
            thin
        }
    }

    /** Full series are built one at a time (M19.6). */
    private val building = Mutex()

    /** A file derived from session [id]'s lines, kept beside it and deleted with it (M13): null if there's none. */
    public suspend fun derived(id: String, name: String): ByteArray? {
        val key = derivedKey(id, name)
        return if (key in store.list(key)) store.read(key) else null
    }

    public suspend fun putDerived(id: String, name: String, bytes: ByteArray): Unit = store.put(derivedKey(id, name), bytes)

    /** The names of session [id]'s derived files starting [prefix]. */
    public suspend fun derivedNames(id: String, prefix: String): List<String> =
        store.list(derivedKey(id, prefix)).map { it.removePrefix(sessionPrefix(id)) }

    public suspend fun deleteDerived(id: String, name: String): Unit = store.delete(derivedKey(id, name))

    /** An object as stored, gzip and all (M7.3). */
    public suspend fun <T> readRaw(key: String, body: suspend (InputStream) -> T): T = store.readRaw(key, body)

    /** One session's record, or null (the admin page, M6). */
    public suspend fun session(id: String): SessionRecord? = index.get(id)

    /** Sets (or with null clears) who drove session [id] (M14); false if there's no such session. */
    public suspend fun setDriver(id: String, driver: String?): Boolean = index.setDriver(id, driver)

    /** Names the session, or clears its name (M18.3). */
    public suspend fun setName(id: String, name: String?): Boolean = index.setName(id, name)

    /** The session's clock offset as the live lane measured it (M18.1); the smaller is kept. */
    public suspend fun setClockOffset(id: String, offsetMs: Long): Boolean = index.setClockOffset(id, offsetMs)

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

        /** Complete already, with the same end and hash: answered as [Done], and nothing done again (§23). */
        public data object AlreadyDone : Complete
        public data class Gap(val missingFrom: Long) : Complete
        public data object NotOpen : Complete
        public data object WrongCar : Complete
        public data class BadRecord(val reason: String) : Complete
    }

    public companion object {
        /** A third mismatch would not change anything: the two sides disagree about the bytes. */
        public const val MAX_HASH_RESETS: Int = 2

        /** Segments after line 0 that set off a compaction (M19.2): about every 3 minutes at the tablet's rate. */
        public const val COMPACT_AT: Int = SegmentStore.COMPOSE_MAX

        /** Segments read ahead while a session is assembled: at most this many in memory. */
        public const val READ_AHEAD: Int = 8

        private const val COMPLETE_LOCKS: Int = 64

        public fun sessionPrefix(id: String): String = "sessions/$id/"
        public fun segmentsPrefix(id: String): String = "sessions/$id/segments/"

        /** A derived file's key (M13); never a name the archive itself uses. */
        public fun derivedKey(id: String, name: String): String {
            require(name.startsWith("timing-") && '/' !in name) { "not a derived file name: $name" }
            return sessionPrefix(id) + name
        }
        public fun sessionKey(id: String): String = "sessions/$id/session.jsonl.gz"

        /** The prepared series (M7.2); versioned in its name, and per `ackedThrough` while uploading. */
        public fun seriesKey(id: String, ackedThrough: Long?): String =
            "${seriesPrefix(id)}v${SeriesBuilder.VERSION}${ackedThrough?.let { "-$it" } ?: ""}.json.gz"

        public fun seriesPrefix(id: String): String = "sessions/$id/series-"

        /** A complete session's thinned series (M19.6), beside its full one. */
        public fun thinSeriesKey(id: String): String = "${seriesPrefix(id)}v${SeriesBuilder.VERSION}-thin.json.gz"

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
