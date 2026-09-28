package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.TabletFrame
import com.obd2dashboard.backend.live.TabletHandle
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.BOX_WALL0
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLap
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test

/**
 * Where a car stands, gathered (M17.3): session a complete (laps 1–2), then b,
 * the same run of the app, streaming (laps 3–4). a's lap 2 is the quickest (the tablet's laps share their crossings: lap 3 starts where it ended).
 */
class CarTimingsTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val courses = InMemoryCourseStore()
    private val events = testEvents()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hub = InMemoryLiveHub()
    private val live = LiveTimings(archive, scope, Clock.systemUTC())
    private val timings = CarTimings(archive, courses, events, RetimingJobs(registry, archive, courses, scope), live, hub)
    private val a = "5ace0000-1111-4111-8111-00000000017a"
    private val b = "5ace0000-1111-4111-8111-00000000017b"
    private val now = Instant.now()
    private val sam = Driver("d-sam", "Sam Voigt", "SAM")
    private val alex = Driver("d-alex", "Alex Rider", "ALE")

    private fun lines(id: String, from: Int, to: Int, laps: List<String>) = boxLog(from, to, laps).map { it.replace("\"id\":\"s\"", "\"id\":\"$id\"") }

    init {
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            courses.save("box", 0, "Box", boxGeoJson(), now)
            events.drivers.put(sam)
            events.drivers.put(alex)
            // a: complete, heard ten minutes ago, Sam driving.
            val aLines = lines(a, 0, 160, listOf(boxLap(1, 1), boxLap(2, 1, endOff = -800)))
            index.create(SessionRecord(a, "outback", null, null, aLines.size - 1L, emptyList(), true, null, 0, now.minusSeconds(600), now.minusSeconds(300), driver = "d-sam"))
            store.put(ArchiveService.sessionKey(a), aLines.joinToString("\n", postfix = "\n").toByteArray())
            // b: announced by the live lane now, streaming.
            archive.announce("outback", b)
            val bLines = lines(b, 161, 300, listOf(boxLap(3, 1, startOff = -800, endOff = -500), boxLap(4, 1, startOff = -500))).map { Json.parseToJsonElement(it).jsonObject }
            hub.attach("outback", object : TabletHandle {
                override fun superseded() = Unit
                override fun close(code: Short, reason: String) = Unit
                override fun send(frame: String) = Unit
            }).apply(TabletFrame.Session(b, bLines.first()))
            live.offer("outback", TabletFrame.Session(b, bLines.first()))
            live.offer("outback", TabletFrame.Batch(b, bLines.drop(1)))
            val until = System.currentTimeMillis() + 5_000
            while (live.read("outback") { s -> s.sumOf { it.trace.tabletLaps.size } } < 2) {
                check(System.currentTimeMillis() < until) { "b's laps not held" }
                Thread.sleep(10)
            }
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun event(kind: PartKind, from: Instant = now.minusSeconds(3600)) = runBlocking {
        events.events.save(
            Event("day", "Test day", "2026-09-28", "box", "box", listOf("outback"), listOf(Part("p1", kind, "Part", from, now.plusSeconds(3600)))),
            0, now,
        )
    }

    private fun standing() = runBlocking { timings.standing("outback") }!!

    @Test
    fun `with no event, the course from the live laps and the best over every session`() {
        val s = standing()
        s.session shouldBe b
        s.course shouldBe CourseAt("box", 1, "box")
        s.best shouldBe BestLap(69.2, listOf(17.5, 17.5, 17.5, 17.5), "SAM", a, 2) // a's lap 2, Sam's
        s.race shouldBe null
        s.driver shouldBe DriverNow(null, null, BOX_WALL0 + 12_500 + 140_000 - 800) // b's run's first lap, lap 3, from where a's lap 2 ended
    }

    @Test
    fun `in a race, laps counted through the complete session and the live one`() {
        event(PartKind.RACE)
        runBlocking { index.setDriver(b, "d-alex") }
        val s = standing()
        s.race shouldBe RaceNow(4, BOX_WALL0 + 12_500) // no stop: since the first lap
        // Alex, as set on the session now, though no stop has split the stint Sam began; the best was Sam's stint's.
        s.driver shouldBe DriverNow("Alex Rider", "ALE", BOX_WALL0 + 12_500)
        s.best!!.driver shouldBe "SAM"
    }

    @Test
    fun `a session outside the event's parts never counts`() {
        event(PartKind.PRACTICE, from = now.minusSeconds(200)) // after a was last heard
        val s = standing()
        s.best!!.session shouldBe b // a's quicker lap isn't the event's
        s.race shouldBe null // practice
        runBlocking { timings.eventOf("outback", a) } shouldBe null
        runBlocking { timings.eventOf("outback", b) }!!.second.id shouldBe "p1"
    }

    @Test
    fun `not in a session, nothing`() {
        runBlocking { hub.attach("outback", object : TabletHandle {
            override fun superseded() = Unit
            override fun close(code: Short, reason: String) = Unit
            override fun send(frame: String) = Unit
        }).apply(TabletFrame.End(b, null)) }
        runBlocking { timings.standing("outback") } shouldBe null
    }
}
