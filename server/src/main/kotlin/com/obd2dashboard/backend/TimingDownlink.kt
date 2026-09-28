package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.TabletHandle
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * `timing` down to the tablet (M17.4, contract §22.7): to a tablet listing
 * `timing.1`, on every `session` frame (after every `hello`), and whenever
 * where its car stands changes. Compared without ages, which are worked out
 * from the tablet's `wall` now when each frame goes.
 */
class TimingDownlink(
    private val timings: CarTimings,
    private val hub: LiveHub,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val tick: Duration = 10.seconds,
    /** Told of each car's standing as it's worked out: the car page's (M17.5). */
    private val standing: suspend (car: String, Standing?) -> Unit = { _, _ -> },
) {
    /** A car with a tablet connected: every socket (the car page wants the standing), and those that asked for `timing`. */
    private class Listening(
        val tablets: MutableSet<TabletHandle> = ConcurrentHashMap.newKeySet(),
        val timing: MutableSet<TabletHandle> = ConcurrentHashMap.newKeySet(),
        val lock: Mutex = Mutex(),
        @Volatile var last: Standing? = null,
    )

    private val cars = ConcurrentHashMap<String, Listening>()

    init {
        scope.launch {
            while (isActive) {
                delay(tick)
                cars.keys.forEach { changed(it) }
            }
        }
    }

    /** [car]'s tablet connected; it gets `timing` if it listed [FEATURE] ([wants]). */
    fun listen(car: String, tablet: TabletHandle, wants: Boolean) {
        cars.compute(car) { _, l ->
            (l ?: Listening()).also { it.tablets += tablet; if (wants) it.timing += tablet }
        }
    }

    fun leave(car: String, tablet: TabletHandle) {
        cars.computeIfPresent(car) { _, l ->
            l.tablets -= tablet
            l.timing -= tablet
            l.takeIf { it.tablets.isNotEmpty() }
        }
    }

    /** A `session` frame from [car]'s tablet (after every `hello`): `timing` goes whatever the last one said. */
    fun sessionStarted(car: String) {
        if (cars.containsKey(car)) scope.launch { update(car, always = true) }
    }

    /** Something [car]'s timing depends on changed: `timing` goes if it differs. */
    fun changed(car: String) {
        if (cars.containsKey(car)) scope.launch { update(car, always = false) }
    }

    /** A driver, a race, an event or a course changed: every listening car is worked out again. */
    fun nudge() {
        cars.keys.forEach { changed(it) }
    }

    private suspend fun update(car: String, always: Boolean) {
        val listening = cars[car] ?: return
        try {
            listening.lock.withLock {
                val now = timings.standing(car)
                standing(car, now)
                if (now == null || (!always && now == listening.last)) return@withLock
                listening.last = now
                val offset = hub.status(car).clockOffset?.toMillis()
                val frame = frame(now, offset?.let { clock.millis() - it }).toString()
                listening.timing.forEach { it.send(frame) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("timing for {} failed", car, e)
        }
    }

    companion object {
        const val FEATURE: String = "timing.1"

        private val log = LoggerFactory.getLogger("timing")

        /** §22.7's frame for [s]; ages from [tabletNow] (the tablet's `wall` now), left out if it's unknown. */
        fun frame(s: Standing, tabletNow: Long?): JsonObject = buildJsonObject {
            put("t", "timing")
            put("session", s.session)
            s.course?.let { c -> put("course", buildJsonObject { put("id", c.id); put("version", c.version); put("layout", c.layout) }) }
            s.best?.let { b ->
                put("best", buildJsonObject {
                    put("time", b.time)
                    put("sectors", buildJsonArray { b.sectors.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    b.driver?.let { put("driver", it) }
                    put("session", b.session)
                    b.lap?.let { put("lap", it) }
                })
            }
            s.bestSectors?.let { list -> put("bestSectors", buildJsonArray { list.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }
            s.driver?.let { d ->
                put("driver", buildJsonObject {
                    d.name?.let { put("name", it) }
                    d.code?.let { put("code", it) }
                    age(tabletNow, d.stintStart)?.let { put("stintAgeMs", it) }
                })
            }
            s.race?.let { r ->
                put("race", buildJsonObject {
                    put("lap", r.lap)
                    age(tabletNow, r.leftPits)?.let { put("sinceStopAgeMs", it) }
                })
            }
        }

        private fun age(now: Long?, at: Long?): Long? = if (now == null || at == null) null else maxOf(0, now - at)
    }
}
