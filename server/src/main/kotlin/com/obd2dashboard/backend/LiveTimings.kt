package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.LineSplitter
import com.obd2dashboard.backend.archive.Records
import com.obd2dashboard.backend.live.TabletFrame
import com.obd2dashboard.backend.timing.LiveSession
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * **The live run** (M17.2): every session a car streamed that isn't complete
 * yet, its records held as they came, so the laps being driven count before
 * the session is uploaded whole.
 *
 * - **Fed after `CarLive` takes a frame**: a `session`, and batches' records;
 *   never a snapshot's, which are the latest of each signal, however old.
 * - **A session first seen mid-drive** (after a restart of the server) is read
 *   from its partial archive first, then fed live records past its last `seq`.
 * - **After a reconnect**, the archive is read again [refillAfter] later for
 *   `lap` records sent while the link was down; each is kept once, by `seq`.
 * - **One worker per car**, off the socket, so reading an archive never holds
 *   up a tablet's frames. Readers take the car's lock.
 * - **Dropped** once complete, or [keepFor] after it was last heard.
 */
class LiveTimings(
    private val archive: ArchiveService,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val refillAfter: Duration = 3.minutes,
    private val keepFor: Duration = 12.hours,
    /** Told after anything that can change what's timed: a session, a lap, a refill, one completed. */
    private val changed: suspend (car: String) -> Unit = {},
) {
    private sealed interface Input {
        data class Session(val id: String, val record: JsonObject) : Input
        data class Records(val session: String, val records: List<JsonObject>) : Input
        data class Refill(val session: String) : Input
        data class Completed(val session: String) : Input
    }

    private class Held(val live: LiveSession, var heard: Instant)

    private inner class Car(val slug: String) {
        val inputs = Channel<Input>(Channel.UNLIMITED)
        val lock = Mutex()
        val sessions = LinkedHashMap<String, Held>()

        init {
            scope.launch {
                for (input in inputs) {
                    try {
                        if (handle(input)) changed(slug)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("live timing for {}: {} failed", slug, input::class.simpleName, e)
                    }
                }
            }
        }

        /** Whether it changed anything. */
        suspend fun handle(input: Input): Boolean = when (input) {
            is Input.Session -> {
                val known = lock.withLock { sessions[input.id] }
                if (known != null) {
                    // A reconnect: what was sent while the link was down is in the archive a little later.
                    scope.launch { delay(refillAfter); inputs.trySend(Input.Refill(input.id)) }
                    lock.withLock { known.heard = clock.instant(); known.live.trace.record(input.record) }
                    false
                } else {
                    val held = load(input.id)
                    lock.withLock {
                        held.live.trace.record(input.record)
                        sessions[input.id] = held
                        prune()
                    }
                    true
                }
            }
            is Input.Records -> {
                val held = lock.withLock { sessions[input.session] } ?: load(input.session).also { h -> lock.withLock { sessions[input.session] = h } }
                lock.withLock {
                    held.heard = clock.instant()
                    val trace = held.live.trace
                    var lap = false
                    for (r in input.records) {
                        val seq = (r["seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        if (seq != null && seq <= trace.lastSeq) continue
                        if (r.type() == "lap") lap = true
                        trace.record(r)
                    }
                    lap
                }
            }
            is Input.Refill -> {
                val record = archive.session(input.session)
                if (record == null || record.complete) false
                else {
                    val laps = mutableListOf<JsonObject>()
                    archive.read(record) { stream -> LineSplitter { line -> Records.parseObject(line)?.takeIf { it.type() == "lap" }?.let(laps::add) }.feed(stream) }
                    lock.withLock {
                        val trace = sessions[input.session]?.live?.trace ?: return@withLock false
                        val before = trace.tabletLaps.size
                        laps.forEach(trace::record)
                        trace.tabletLaps.size > before
                    }
                }
            }
            is Input.Completed -> lock.withLock { sessions.remove(input.session) != null }
        }

        /** A session not held yet: its partial archive, if it has one. */
        private suspend fun load(id: String): Held {
            val live = LiveSession(id, slug)
            archive.session(id)?.takeIf { !it.complete && it.ackedThrough >= 0 }?.let { record ->
                archive.read(record) { live.trace.read(it) }
            }
            return Held(live, clock.instant())
        }

        private fun prune() {
            val oldest = clock.instant().minus(keepFor.toJavaDuration())
            sessions.values.removeIf { it.heard.isBefore(oldest) }
        }
    }

    private val cars = ConcurrentHashMap<String, Car>()

    private fun car(slug: String): Car = cars.computeIfAbsent(slug) { Car(it) }

    /** A frame [car]'s `CarLive` took. */
    fun offer(car: String, frame: TabletFrame) {
        when (frame) {
            is TabletFrame.Session -> car(car).inputs.trySend(Input.Session(frame.id, frame.record))
            is TabletFrame.Batch -> car(car).inputs.trySend(Input.Records(frame.session, frame.records))
            else -> Unit
        }
    }

    /** Session [id] completed: its run is re-timed whole from now on. */
    fun completed(car: String, id: String) {
        cars[car]?.inputs?.trySend(Input.Completed(id))
    }

    /** [block] over [car]'s sessions not complete, oldest first, under its lock (the traces change as records come). */
    suspend fun <T> read(car: String, block: (List<LiveSession>) -> T): T {
        val c = cars[car] ?: return block(emptyList())
        return c.lock.withLock { block(c.sessions.values.map { it.live }) }
    }

    private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        val log = LoggerFactory.getLogger("live-timing")
    }
}
