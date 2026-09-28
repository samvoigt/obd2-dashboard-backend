package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.BOX_WALL0
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLap
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.math.absoluteValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** `GET /api/sessions/{id}/laps` (M13.5). */
class SessionLapsTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val courses = InMemoryCourseStore()
    private val events = testEvents()
    private val a = "5ace0000-1111-4111-8111-00000000000a"
    private val b = "5ace0000-1111-4111-8111-00000000000b"

    init {
        runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }
    }

    private fun session(id: String, lines: List<String>, complete: Boolean = true) = runBlocking {
        index.create(SessionRecord(id, "outback", null, null, lines.size - 1L, emptyList(), complete, null, 0, Instant.EPOCH, Instant.EPOCH))
        store.put(ArchiveService.sessionKey(id), lines.joinToString("\n", postfix = "\n").toByteArray())
    }

    private fun centimetres(lines: List<String>) =
        lines.map { Regex("\"(lat|lon)\":(-?[0-9.]+)").replace(it) { m -> "\"${m.groupValues[1]}\":${"%.7f".format(m.groupValues[2].toDouble())}" } }

    private fun ApplicationTestBuilder.app() {
        application { module(registry, archive, InMemoryLiveHub(), messages = testMessages(), courses = courses, events = events, crewKey = testCrewKey()) }
    }

    private suspend fun ApplicationTestBuilder.laps(id: String): SessionLaps? {
        val response = client.get("/api/sessions/$id/laps")
        if (response.status == HttpStatusCode.NoContent) return null
        response.status shouldBe HttpStatusCode.OK
        return Json.decodeFromString<SessionLaps>(response.bodyAsText())
    }

    @Test
    fun `a session's laps as they stand, numbered through the run, placed on its wall clock`() = testApplication {
        app()
        runBlocking {
            // Two courses in the same place: the laps name the second.
            courses.save("abox", 0, "A box", boxGeoJson(), Instant.EPOCH)
            courses.save("box", 0, "Box", boxGeoJson(), Instant.EPOCH)
        }
        // The OBD link drops at 40 s: every lap ends in b. The tablet's lap 2 on version 1 is 3 ms off, and it
        // numbers it 9 (the run numbers it). Positions to 1 cm, as a receiver gives them: re-timing's times get noise.
        session(a, centimetres(boxLog(0, 40)))
        session(b, centimetres(boxLog(41, 300, listOf(boxLap(2, 1, endOff = 3).replace("\"lap\":2", "\"lap\":9")))))
        laps(a)!!.laps shouldBe emptyList()
        val got = laps(b)!!
        got.course shouldBe "box"
        got.courseName shouldBe "Box"
        got.courseVersion shouldBe 1
        got.layout shouldBe "box"
        got.laps.map { it.lap } shouldBe listOf(1, 2, 3, 4)
        got.laps.map { it.source } shouldBe listOf("retimed", "tablet", "retimed", "retimed")
        got.laps.map { it.time } shouldBe listOf(70.0, 70.003, 70.0, 70.0)
        (got.laps[0].start - (BOX_WALL0 + 12_500)).absoluteValue shouldBeLessThan 2
        (got.laps[0].end - (BOX_WALL0 + 82_500)).absoluteValue shouldBeLessThan 2
        got.laps[1].flag!!.time shouldBe 70.0
        got.laps[1].end shouldBe BOX_WALL0 + 152_503
        // To the millisecond, so equal sectors are equal (the page's best of each sector).
        got.laps.flatMap { it.sectors }.toSet() shouldBe setOf(17.5)
    }

    @Test
    fun `nothing for a session still uploading, at no course, or unknown`() = testApplication {
        app()
        session(a, boxLog(0, 300), complete = false)
        laps(a) shouldBe null
        session(b, boxLog(0, 300))
        laps(b) shouldBe null // no courses at all
        runBlocking { courses.save("far", 0, "Far", Json.parseToJsonElement(boxGeoJson().toString().replace("-71.4", "-72.4")).jsonObject, Instant.EPOCH) }
        laps(b) shouldBe null // a course, but not here
        client.get("/api/sessions/5ace0000-1111-4111-8111-0000000000ff/laps").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `a session in an event is timed on the event's course, not the first it touches (M16_1)`() = testApplication {
        app()
        runBlocking {
            // "abox" sorts first and is where the car went too; its line is 100 m on.
            courses.save("abox", 0, "Another box", boxGeoJson(sfX = 600.0), Instant.EPOCH)
            courses.save("box", 0, "The box", boxGeoJson(), Instant.EPOCH)
        }
        session(a, boxLog(0, 300))
        laps(a)!!.course shouldBe "abox" // no event: the first course its fixes touch
        runBlocking {
            events.events.save(com.obd2dashboard.backend.events.Event("box-day", "Box day", "2026-10-04", "box", "box", listOf("outback"),
                listOf(com.obd2dashboard.backend.events.Part("p1", com.obd2dashboard.backend.events.PartKind.PRACTICE, "Practice",
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)))), 0, Instant.EPOCH)
        }
        laps(a)!!.let { it.course shouldBe "box"; it.laps.first().start shouldBe com.obd2dashboard.backend.timing.BOX_WALL0 + 12_500 }
    }
}
