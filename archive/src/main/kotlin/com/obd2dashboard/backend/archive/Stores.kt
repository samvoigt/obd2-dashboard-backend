package com.obd2dashboard.backend.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A stored run of lines, `first..last` inclusive, in the object [key]. */
public data class Segment(
    val first: Long,
    val last: Long,
    val key: String,
    /** `seq` of the first and last line, when they carry one: for merging with the live lane (§7). */
    val firstSeq: Long?,
    val lastSeq: Long?,
)

/**
 * What the index knows about a session. **The authority on what is stored**:
 * a segment counts only once it is in [segments], whatever the bucket holds.
 */
public data class SessionRecord(
    val id: String,
    val car: String,
    /** Null for a session the live lane created before its `PUT` (M4). */
    val header: SessionHeader?,
    val line0Sha256: String?,
    /** The highest index stored contiguously from 0; −1 before line 0. */
    val ackedThrough: Long,
    val segments: List<Segment>,
    val complete: Boolean,
    val sha256: String?,
    val hashResets: Int,
    val created: Instant,
    val updated: Instant,
    /** Built once the session is complete (M7.1); null before, or if not built yet. */
    val summary: SessionSummary? = null,
    /** Who drove it (M14): a driver's id, set on the website; null until someone says. */
    val driver: String? = null,
    /**
     * The server's clock less the tablet's over this session (M18.1): the
     * smallest the live lane measured. Null for a session not streamed, or
     * streamed before M18.
     */
    val clockOffsetMs: Long? = null,
    /** What the admin or the crew called it (M18.3); null until someone does. */
    val name: String? = null,
    /**
     * The SHA-256 of every line acknowledged, as a [RunningSha256]'s state
     * (M19.3), so `complete` only compares; null for a session opened before
     * M19, which is hashed whole at `complete`.
     */
    val hashState: String? = null,
)

/**
 * Where the archive's facts live (Firestore in production).
 *
 * Every change is **conditional**, so two instances handling the same session
 * cannot both win: each write names the state it expects and does nothing,
 * returning false, if the session has moved on.
 */
public interface SessionIndex {
    public suspend fun get(id: String): SessionRecord?

    /** Adds [record] if no session has its id. */
    public suspend fun create(record: SessionRecord): Boolean

    /** Sets line 0 on a session that has none (one the live lane created), and the running hash from it (M19.3). */
    public suspend fun setLine0(id: String, header: SessionHeader, line0Sha256: String, segment: Segment, now: Instant, hashState: String? = null): Boolean

    /**
     * Appends [segment] and advances `ackedThrough` to its last line, with the
     * running hash carried over its lines (M19.3), **only if** it is still
     * [expectedAcked].
     */
    public suspend fun append(id: String, expectedAcked: Long, segment: Segment, now: Instant, hashState: String? = null): Boolean

    /**
     * Marks the session complete, only if `ackedThrough` is still
     * [expectedAcked]. [assembled]: it's one object already, so its segment
     * list is cleared; else (M19.3) the segments stay listed until
     * [assembled] is called.
     */
    public suspend fun complete(id: String, expectedAcked: Long, sha256: String, now: Instant, assembled: Boolean = true): Boolean

    /** A complete session is now one object (M19.3): its segment list cleared. False if it isn't complete. */
    public suspend fun assembled(id: String, now: Instant): Boolean

    /**
     * Replaces the segments whose keys are [replaced] with [piece], composed
     * from them (M19.2), **only if** every one is still listed; false if not.
     */
    public suspend fun compact(id: String, replaced: List<String>, piece: Segment, now: Instant): Boolean

    /**
     * Drops every segment after line 0 and counts a reset, with the running
     * hash back at line 0 (M19.3), only if `ackedThrough` is still [expectedAcked].
     */
    public suspend fun resetToLine0(id: String, expectedAcked: Long, now: Instant, hashState: String? = null): Boolean

    public suspend fun listByCar(car: String): List<SessionRecord>

    /** Sets the session's summary (M7.1), touching nothing else; false if there's no such session. */
    public suspend fun setSummary(id: String, summary: SessionSummary): Boolean

    /** Sets (or with null clears) who drove the session (M14), touching nothing else; false if there's no such session. */
    public suspend fun setDriver(id: String, driver: String?): Boolean

    /** Names the session, or clears its name with null (M18.3). */
    public suspend fun setName(id: String, name: String?): Boolean

    /** Stores [offsetMs] as the session's clock offset, or keeps the one stored if smaller (M18.1). */
    public suspend fun setClockOffset(id: String, offsetMs: Long): Boolean

    public suspend fun list(): List<SessionRecord>

    public suspend fun delete(id: String): Boolean
}

/**
 * Where lines are kept (Cloud Storage in production). Callers pass and get
 * **plain line bytes**; how they are encoded at rest is the store's business.
 */
public interface SegmentStore {
    public suspend fun put(key: String, lines: ByteArray)

    public suspend fun read(key: String): ByteArray

    /** Reads an object's plain line bytes as a stream, so a long session is never held whole (M7.1). */
    public suspend fun <T> readStream(key: String, body: suspend (InputStream) -> T): T

    /** Reads an object **as stored**, gzip and all, to send on without unzipping (M7.3). */
    public suspend fun <T> readRaw(key: String, body: suspend (InputStream) -> T): T

    /**
     * Writes an object from a stream, so a long session is never held whole.
     * **If [body] throws, no object is created**: a refused assembly must not
     * leave a half-written session behind.
     */
    public suspend fun write(key: String, body: suspend (OutputStream) -> Unit)

    /** Deletes every object whose key starts with [prefix]; returns how many. */
    public suspend fun deletePrefix(prefix: String): Int

    public suspend fun delete(key: String)

    public suspend fun list(prefix: String): List<String>

    /**
     * Makes [target] of [sources] joined in order, on the store's side (Cloud
     * Storage's compose, M19.2): at most [COMPOSE_MAX] sources. Gzip files
     * joined are one multi-member gzip, read straight through.
     */
    public suspend fun compose(target: String, sources: List<String>)

    public companion object {
        /** Cloud Storage's limit on a compose's sources. */
        public const val COMPOSE_MAX: Int = 32
    }
}

/** A [SessionIndex] in memory, for tests. */
public class InMemorySessionIndex : SessionIndex {
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    /** Runs once before the next [append] checks its condition: a test's way to interleave a rival. */
    public var beforeAppend: (suspend () -> Unit)? = null

    /** Makes the next [append] throw, as a store failing after the object was written. */
    public var failNextAppend: Boolean = false

    override suspend fun get(id: String): SessionRecord? = sessions[id]

    override suspend fun create(record: SessionRecord): Boolean = sessions.putIfAbsent(record.id, record) == null

    override suspend fun setLine0(
        id: String,
        header: SessionHeader,
        line0Sha256: String,
        segment: Segment,
        now: Instant,
        hashState: String?,
    ): Boolean = update(id) {
        if (it.ackedThrough != -1L) null
        else it.copy(header = header, line0Sha256 = line0Sha256, ackedThrough = 0, segments = listOf(segment), updated = now, hashState = hashState)
    }

    override suspend fun append(id: String, expectedAcked: Long, segment: Segment, now: Instant, hashState: String?): Boolean {
        beforeAppend?.let { hook -> beforeAppend = null; hook() }
        if (failNextAppend) {
            failNextAppend = false
            throw IllegalStateException("simulated index failure")
        }
        return update(id) {
            if (it.ackedThrough != expectedAcked || it.complete) null
            else it.copy(ackedThrough = segment.last, segments = it.segments + segment, updated = now, hashState = hashState ?: it.hashState)
        }
    }

    override suspend fun complete(id: String, expectedAcked: Long, sha256: String, now: Instant, assembled: Boolean): Boolean =
        update(id) {
            if (it.ackedThrough != expectedAcked) null
            else it.copy(complete = true, sha256 = sha256, segments = if (assembled) emptyList() else it.segments, updated = now)
        }

    override suspend fun assembled(id: String, now: Instant): Boolean = update(id) {
        if (!it.complete) null else it.copy(segments = emptyList(), updated = now)
    }

    override suspend fun compact(id: String, replaced: List<String>, piece: Segment, now: Instant): Boolean = update(id) {
        compacted(it, replaced, piece, now)
    }

    override suspend fun resetToLine0(id: String, expectedAcked: Long, now: Instant, hashState: String?): Boolean = update(id) {
        if (it.ackedThrough != expectedAcked) null
        else it.copy(
            ackedThrough = 0, segments = it.segments.filter { s -> s.first == 0L }, hashResets = it.hashResets + 1, updated = now,
            hashState = hashState ?: it.hashState,
        )
    }

    override suspend fun listByCar(car: String): List<SessionRecord> = sessions.values.filter { it.car == car }

    override suspend fun setSummary(id: String, summary: SessionSummary): Boolean =
        sessions.computeIfPresent(id) { _, current -> current.copy(summary = summary) } != null

    override suspend fun setDriver(id: String, driver: String?): Boolean =
        sessions.computeIfPresent(id) { _, current -> current.copy(driver = driver) } != null

    override suspend fun setName(id: String, name: String?): Boolean =
        sessions.computeIfPresent(id) { _, current -> current.copy(name = name) } != null

    override suspend fun setClockOffset(id: String, offsetMs: Long): Boolean =
        sessions.computeIfPresent(id) { _, current -> current.copy(clockOffsetMs = minOf(current.clockOffsetMs ?: offsetMs, offsetMs)) } != null

    override suspend fun list(): List<SessionRecord> = sessions.values.toList()

    override suspend fun delete(id: String): Boolean = sessions.remove(id) != null

    /** Applies [change] atomically; a null result means "the condition failed". */
    private fun update(id: String, change: (SessionRecord) -> SessionRecord?): Boolean {
        var applied = false
        sessions.computeIfPresent(id) { _, current -> change(current)?.also { applied = true } ?: current }
        return applied
    }
}

/** A [SegmentStore] in memory, for tests. */
public class InMemorySegmentStore : SegmentStore {
    public val objects: MutableMap<String, ByteArray> = ConcurrentHashMap()

    /** Runs once, after the next [put] has stored its object: a test's way to interleave a rival. */
    public var afterPut: (suspend () -> Unit)? = null

    /** Makes the next [put] throw without storing, as Cloud Storage failing. */
    public var failNextPut: Boolean = false

    override suspend fun put(key: String, lines: ByteArray) {
        if (failNextPut) {
            failNextPut = false
            throw IllegalStateException("simulated storage failure")
        }
        objects[key] = lines.copyOf()
        afterPut?.let { hook -> afterPut = null; hook() }
    }

    /** Runs before every [read]: a test's way to let a rival run while segments are read. */
    public var beforeRead: (suspend () -> Unit)? = null

    /** How many objects [write] has stored. */
    public var writes: Int = 0
        private set

    override suspend fun read(key: String): ByteArray {
        beforeRead?.invoke()
        return objects[key]?.copyOf() ?: error("no object $key")
    }

    override suspend fun <T> readStream(key: String, body: suspend (InputStream) -> T): T =
        body(ByteArrayInputStream(objects[key] ?: error("no object $key")))

    /** This store keeps plain bytes, so "as stored" is gzipped here, as Cloud Storage keeps them. */
    override suspend fun <T> readRaw(key: String, body: suspend (InputStream) -> T): T {
        val plain = objects[key] ?: error("no object $key")
        val gz = ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(plain) } }
        return body(ByteArrayInputStream(gz.toByteArray()))
    }

    override suspend fun write(key: String, body: suspend (OutputStream) -> Unit) {
        val out = ByteArrayOutputStream()
        body(out) // throws before anything is stored, as the interface requires
        objects[key] = out.toByteArray()
        writes++
    }

    override suspend fun deletePrefix(prefix: String): Int {
        val keys = objects.keys.filter { it.startsWith(prefix) }
        keys.forEach { objects.remove(it) }
        return keys.size
    }

    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    override suspend fun list(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }.sorted()

    /** How many composes ran (M19.2). */
    public var composes: Int = 0
        private set

    /** Plain bytes here, so joining is concatenating, as a multi-member gzip reads. */
    override suspend fun compose(target: String, sources: List<String>) {
        require(sources.size in 1..SegmentStore.COMPOSE_MAX) { "compose takes 1 to ${SegmentStore.COMPOSE_MAX} sources" }
        val joined = java.io.ByteArrayOutputStream()
        for (s in sources) joined.write(objects[s] ?: error("no object $s"))
        objects[target] = joined.toByteArray()
        composes++
    }
}

/**
 * [record] with the segments keyed [replaced] swapped for [piece] (M19.2), in
 * order; null if any of them is no longer listed, or the session is complete.
 */
public fun compacted(record: SessionRecord, replaced: List<String>, piece: Segment, now: Instant): SessionRecord? {
    if (record.complete || replaced.isEmpty()) return null
    val keys = replaced.toSet()
    if (!record.segments.map { it.key }.containsAll(keys)) return null
    val kept = record.segments.filter { it.key !in keys }
    return record.copy(segments = (kept + piece).sortedBy { it.first }, updated = now)
}

