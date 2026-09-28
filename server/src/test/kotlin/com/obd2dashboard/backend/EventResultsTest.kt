package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLap
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/** Practice results (M14.5): the rules, and the whole path on the box course. */
class EventResultsTest {
    private fun lap(n: Int, time: Double, sectors: List<Double> = emptyList(), pitIn: Boolean = false, pitOut: Boolean = false) =
        StandingLap(n, time, sectors, pitIn, pitOut, 0, 0, "tablet")

    private val sam = DriverView("d-sam", "Sam Voigt", "SAM")
    private val alex = DriverView("d-alex", "Alex Rider", "ALE")

    @Test
    fun `each driver's best on track, fastest first, and a session with no driver counted as not set`() {
        val sessions = listOf(
            SessionResult("a", "outback", sam, 0, listOf(lap(1, 72.0), lap(2, 69.0, pitIn = true), lap(3, 71.0))),
            SessionResult("b", "outback", sam, 0, listOf(lap(1, 70.5))),
            SessionResult("c", "outback", alex, 0, listOf(lap(1, 70.0, pitOut = true), lap(2, 70.9))),
            SessionResult("d", "outback", null, 0, listOf(lap(1, 75.0))),
            SessionResult("e", "outback", alex, 0, listOf(lap(1, 60.0)), otherLayout = "short"), // never counted
        )
        Results.driverBests(sessions).map { Triple(it.driver?.code, it.session, it.lap.time) } shouldBe
            listOf(Triple("SAM", "b", 70.5), Triple("ALE", "c", 70.9), Triple(null, "d", 75.0))
        Results.best(sessions[0].laps)!!.time shouldBe 71.0
    }

    @Test
    fun `the best of each sector, never an in-lap's last nor an out-lap's first`() {
        val sessions = listOf(
            SessionResult("a", "o", sam, 0, listOf(lap(1, 60.0, listOf(20.0, 20.0, 20.0)), lap(2, 55.0, listOf(19.0, 21.0, 15.0), pitIn = true))),
            SessionResult("b", "o", alex, 0, listOf(lap(1, 58.0, listOf(10.0, 25.0, 23.0), pitOut = true), lap(2, 61.0, listOf(21.0, 20.5)))),
        )
        // S1: 19 (the in-lap's first counts; the out-lap's 10 doesn't); S2: 20; S3: 20 (the in-lap's 15 doesn't).
        Results.bestSectors(sessions) shouldBe listOf(19.0, 20.0, 20.0)
        Results.bestSectors(emptyList()) shouldBe emptyList()
    }

    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val courses = InMemoryCourseStore()
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore())
    private val t0 = Instant.parse("2026-10-04T13:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)
    private val json = Json { ignoreUnknownKeys = true }

    private fun session(id: String, from: Long, lines: List<String>, driver: String?) = runBlocking {
        index.create(SessionRecord(id, "outback", null, null, lines.size - 1L, emptyList(), true, null, 0, at(from), at(from + 10), driver = driver))
        store.put(ArchiveService.sessionKey(id), lines.joinToString("\n", postfix = "\n").toByteArray())
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(index, store), InMemoryLiveHub(),
                messages = testMessages(), crewKey = testCrewKey(), courses = courses, events = stores)
        }
    }

    @Test
    fun `an event's page shows each practice's sessions, laps, and each driver's best, on the event's course`() = testApplication {
        app()
        val a = "5ace0000-1111-4111-8111-00000000a015"
        val b = "5ace0000-1111-4111-8111-00000000b015"
        val r = "5ace0000-1111-4111-8111-00000000c015"
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            courses.save("box", 0, "The box", boxGeoJson(), Instant.EPOCH)
            stores.drivers.put(Driver("d-sam", "Sam Voigt", "SAM"))
            stores.drivers.put(Driver("d-alex", "Alex Rider", "ALE"))
            stores.events.save(Event("box-day", "Box day", "2026-10-04", "box", "box", listOf("outback"), listOf(
                Part("p1", PartKind.PRACTICE, "Practice 1", at(0), at(60)),
                Part("p2", PartKind.PRACTICE, "Practice 2", at(60), at(120)),
                Part("p3", PartKind.RACE, "Race", at(120), at(480)),
            )), 0, t0)
        }
        // Sam's drive has no laps of its own (re-timed, 70 s each); Alex's has the tablet's, lap 2 a second quicker.
        session(a, 10, boxLog(0, 300, device = "tab-a"), "d-sam")
        session(b, 70, boxLog(0, 300, listOf(boxLap(1, 1), boxLap(2, 1, endOff = -1000), boxLap(3, 1, startOff = -1000)), device = "tab-b"), "d-alex")
        session(r, 130, boxLog(0, 300, device = "tab-c"), null)

        val results = json.decodeFromString<EventResults>(client.get("/api/events/box-day").bodyAsText())
        results.event.courseName shouldBe "The box"
        results.event.layoutName shouldBe "Box"
        results.event.cars shouldBe listOf(CarRef("outback", "Outback"))
        results.parts.map { it.part.name to it.sessions.map { s -> s.id } } shouldBe
            listOf("Practice 1" to listOf(a), "Practice 2" to listOf(b), "Race" to listOf(r))
        val p1 = results.parts[0]
        p1.sessions.single().laps.map { it.source } shouldBe List(4) { "retimed" }
        p1.sessions.single().driver shouldBe sam
        p1.bests.single().lap.time shouldBe (70.0 plusOrMinus 1e-9)
        results.parts[1].sessions.single().best!!.time shouldBe (69.0 plusOrMinus 1e-9)
        // Over all practice: Alex quicker than Sam; the race's sessions never counted here.
        results.practiceBests.map { it.driver?.code to it.lap.time } shouldBe listOf("ALE" to 69.0, "SAM" to 70.0)
        results.practiceBestSectors.size shouldBe 4

        // The session's own page and its car's list name the event and part.
        client.get("/api/sessions/$b").bodyAsText().let { json.decodeFromString<SessionDetail>(it).session.event } shouldBe EventRef("box-day", "Box day", "Practice 2")
        json.decodeFromString<List<Drive>>(client.get("/api/cars/outback/sessions").bodyAsText())
            .flatMap { it.sessions }.associate { it.id to it.event?.part } shouldBe mapOf(a to "Practice 1", b to "Practice 2", r to "Race")

        // Listed, without the admin's hand-made lists.
        val listed = client.get("/api/events").bodyAsText()
        json.decodeFromString<List<PublicEvent>>(listed).single().parts.size shouldBe 3
        listed shouldNotContain "added"
        client.get("/api/events/nope").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `laps are on the event's course, not another at the same place, and a course never reached is never timed`() = testApplication {
        app()
        val s = "5ace0000-1111-4111-8111-00000000e015"
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            // "abox" sorts first and is where the car went too; its line is 100 m on (laps 2.5 s later).
            courses.save("abox", 0, "Another box", boxGeoJson(sfX = 600.0), Instant.EPOCH)
            courses.save("box", 0, "The box", boxGeoJson(), Instant.EPOCH)
            courses.save("far", 0, "Far", Json.parseToJsonElement(boxGeoJson().toString().replace("-71.4", "-72.4")) as kotlinx.serialization.json.JsonObject, Instant.EPOCH)
            stores.events.save(Event("box-day", "Box day", "2026-10-04", "box", "box", listOf("outback"),
                listOf(Part("p1", PartKind.PRACTICE, "Practice 1", at(0), at(60)))), 0, t0)
            stores.events.save(Event("far-day", "Far day", "2026-10-04", "far", "box", listOf("outback"),
                listOf(Part("p1", PartKind.PRACTICE, "Practice 1", at(0), at(60)))), 0, t0)
        }
        session(s, 10, boxLog(0, 300), null)
        val results = json.decodeFromString<EventResults>(client.get("/api/events/box-day").bodyAsText())
        results.parts.single().sessions.single().laps.first().start shouldBe com.obd2dashboard.backend.timing.BOX_WALL0 + 12_500
        val far = json.decodeFromString<EventResults>(client.get("/api/events/far-day").bodyAsText())
        far.parts.single().sessions.single().laps shouldBe emptyList()
        store.objects.keys.none { "-far-" in it } shouldBe true
    }

    @Test
    fun `laps timed on another layout than the event's are shown, never counted`() = testApplication {
        app()
        val s = "5ace0000-1111-4111-8111-00000000d015"
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            // A second layout, the default: a drive with no laps of its own is re-timed on it.
            courses.save("box", 0, "The box", boxGeoJson(short = true), Instant.EPOCH)
            stores.events.save(Event("box-day", "Box day", "2026-10-04", "box", "box", listOf("outback"),
                listOf(Part("p1", PartKind.PRACTICE, "Practice 1", at(0), at(60)))), 0, t0)
        }
        session(s, 10, boxLog(0, 300), null)
        val results = json.decodeFromString<EventResults>(client.get("/api/events/box-day").bodyAsText())
        val only = results.parts.single().sessions.single()
        only.otherLayout shouldBe "short"
        only.best shouldBe null
        results.practiceBests shouldBe emptyList()
        // A race over the same drive: timed on the other layout, so no car in it.
        runBlocking {
            stores.events.save(Event("box-race", "Box race", "2026-10-04", "box", "box", listOf("outback"),
                listOf(Part("p1", PartKind.RACE, "Race", at(0), at(60)))), 0, t0)
        }
        json.decodeFromString<EventResults>(client.get("/api/events/box-race").bodyAsText()).race!!.cars shouldBe emptyList()
    }

    @Test
    fun `the race is one timeline, through a dropped link and a restart, the flag placed from the tablet's offset`() = testApplication {
        app()
        val r1 = "5ace0000-1111-4111-8111-0000000a0154"
        val r2 = "5ace0000-1111-4111-8111-0000000b0154"
        val r3 = "5ace0000-1111-4111-8111-0000000c0154"
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            courses.save("box", 0, "The box", boxGeoJson(), Instant.EPOCH)
            stores.drivers.put(Driver("d-sam", "Sam Voigt", "SAM"))
            stores.events.save(Event("race-day", "Race day", "2026-10-04", "box", "box", listOf("outback"), listOf(
                Part("p1", PartKind.RACE, "Race", at(0), at(60), green = t0.plusMillis(100_000).plusSeconds(600)),
            )), 0, t0)
        }
        // r1 and r2 one run (the link dropped for 10 s); r3 after the app restarted (at from 10 s again).
        session(r1, 10, boxLog(0, 300, device = "tab-r"), "d-sam")
        session(r2, 20, boxLog(310, 600, device = "tab-r"), null)
        session(r3, 30, boxLog(700, 1000, device = "tab-r", atShift = -690_000), null)

        val race = json.decodeFromString<EventResults>(client.get("/api/events/race-day").bodyAsText()).race!!
        val car = race.cars.single()
        car.laps.size shouldBe 13
        car.laps.map { it.source }.count { it == "restart" } shouldBe 1
        car.laps[8].let { it.source shouldBe "restart"; it.time shouldBe (140.0 plusOrMinus 1e-3) }
        car.laps.first().start shouldBe com.obd2dashboard.backend.timing.BOX_WALL0 + 12_500 // the first crossing (on fixAt), on the tablet's wall
        car.stints.single().let { it.driver shouldBe "d-sam"; it.laps shouldBe 13 }
        car.greenLap shouldBe 2
        race.tabletOffset.keys shouldBe setOf("outback")
        race.green shouldBe t0.plusMillis(100_000).plusSeconds(600).toEpochMilli()
        json.decodeFromString<EventResults>(client.get("/api/events/race-day").bodyAsText()).event.revision shouldBe 1

        // Stints as edited replace the default.
        runBlocking {
            val e = stores.events.get("race-day")!!
            stores.events.save(e.copy(parts = listOf(e.race!!.copy(stints = mapOf("outback" to listOf(
                com.obd2dashboard.backend.events.Stint(car.laps.first().start, "d-sam"),
                com.obd2dashboard.backend.events.Stint(car.laps[5].start, null),
            ))))), 1, t0)
        }
        val edited = json.decodeFromString<EventResults>(client.get("/api/events/race-day").bodyAsText()).race!!
        edited.cars.single().stints.map { it.firstLap to it.lastLap } shouldBe listOf(1 to 5, 6 to 13)
        edited.edited.keys shouldBe setOf("outback")
    }
}
