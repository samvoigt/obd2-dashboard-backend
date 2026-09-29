package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.SessionReader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.ThinSeries
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **A session still uploading, as a thinned series** (M19.4): what the car
 * page's "Whole session" and a live session's page get, never a full-size
 * build (at 8 hours that ran production out of memory: M19.1). One builder per
 * session, **fed only the lines past the last it saw**, and **one answer per
 * `ackedThrough` shared by every viewer**. Let go once complete, or
 * [idle] after it was last asked for.
 */
class LiveSeries(private val archive: ArchiveService, private val clock: Clock, private val idle: Duration = Duration.ofMinutes(30)) {
    private class State {
        val lock = Mutex()
        var through = -1L
        val thin = ThinSeries()
        val reader = SessionReader(also = thin::record)
        var answer: Pair<Long, ByteArray>? = null
        @Volatile var asked: Instant = Instant.EPOCH
    }

    private val sessions = ConcurrentHashMap<String, State>()

    /** [record]'s thinned series so far, gzipped, and how far it reaches (`ackedThrough`). */
    suspend fun series(record: SessionRecord): Pair<Long, ByteArray> {
        prune()
        val state = sessions.computeIfAbsent(record.id) { State() }
        state.asked = clock.instant()
        return state.lock.withLock {
            state.answer?.takeIf { it.first == record.ackedThrough }?.let { return@withLock it }
            if (record.ackedThrough > state.through) {
                archive.readLinesFrom(record, state.through + 1) { index, line ->
                    state.reader.line(line)
                    state.through = index
                }
            }
            val summary = state.reader.summary()
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { state.thin.write(it, summary.started, summary.signals) }
            (state.through to out.toByteArray()).also { state.answer = it }
        }
    }

    /** How many sessions are held (for tests and the logs). */
    fun held(): Int = sessions.size

    private fun prune() {
        val oldest = clock.instant().minus(idle)
        sessions.entries.removeIf { it.value.asked.isBefore(oldest) }
    }

    /** Session [id] is complete: its full series is built once from now on. */
    fun completed(id: String) {
        sessions.remove(id)
    }
}
