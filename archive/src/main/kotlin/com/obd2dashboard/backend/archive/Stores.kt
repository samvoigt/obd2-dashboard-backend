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

    /** Sets line 0 on a session that has none (one the live lane created). */
    public suspend fun setLine0(id: String, header: SessionHeader, line0Sha256: String, segment: Segment, now: Instant): Boolean

    /** Appends [segment] and advances `ackedThrough` to its last line, **only if** it is still [expectedAcked]. */
    public suspend fun append(id: String, expectedAcked: Long, segment: Segment, now: Instant): Boolean

    /**
     * Marks the session complete and clears its segment list (the segments are
     * deleted once the session is one object), only if `ackedThrough` is still
     * [expectedAcked].
     */
    public suspend fun complete(id: String, expectedAcked: Long, sha256: String, now: Instant): Boolean

    /** Drops every segment after line 0 and counts a reset, only if `ackedThrough` is still [expectedAcked]. */
    public suspend fun resetToLine0(id: String, expectedAcked: Long, now: Instant): Boolean

    public suspend fun listByCar(car: String): List<SessionRecord>

    /** Sets the session's summary (M7.1), touching nothing else; false if there's no such session. */
    public suspend fun setSummary(id: String, summary: SessionSummary): Boolean

    /** Sets (or with null clears) who drove the session (M14), touching nothing else; false if there's no such session. */
    public suspend fun setDriver(id: String, driver: String?): Boolean

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
    ): Boolean = update(id) {
        if (it.ackedThrough != -1L) null
        else it.copy(header = header, line0Sha256 = line0Sha256, ackedThrough = 0, segments = listOf(segment), updated = now)
    }

    override suspend fun append(id: String, expectedAcked: Long, segment: Segment, now: Instant): Boolean {
        beforeAppend?.let { hook -> beforeAppend = null; hook() }
        if (failNextAppend) {
            failNextAppend = false
            throw IllegalStateException("simulated index failure")
        }
        return update(id) {
            if (it.ackedThrough != expectedAcked || it.complete) null
            else it.copy(ackedThrough = segment.last, segments = it.segments + segment, updated = now)
        }
    }

    override suspend fun complete(id: String, expectedAcked: Long, sha256: String, now: Instant): Boolean =
        update(id) {
            if (it.ackedThrough != expectedAcked) null
            else it.copy(complete = true, sha256 = sha256, segments = emptyList(), updated = now)
        }

    override suspend fun resetToLine0(id: String, expectedAcked: Long, now: Instant): Boolean = update(id) {
        if (it.ackedThrough != expectedAcked) null
        else it.copy(ackedThrough = 0, segments = it.segments.filter { s -> s.first == 0L }, hashResets = it.hashResets + 1, updated = now)
    }

    override suspend fun listByCar(car: String): List<SessionRecord> = sessions.values.filter { it.car == car }

    override suspend fun setSummary(id: String, summary: SessionSummary): Boolean =
        sessions.computeIfPresent(id) { _, current -> current.copy(summary = summary) } != null

    override suspend fun setDriver(id: String, driver: String?): Boolean =
        sessions.computeIfPresent(id) { _, current -> current.copy(driver = driver) } != null

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
}
