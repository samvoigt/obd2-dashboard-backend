package com.obd2dashboard.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import org.slf4j.LoggerFactory

/** The admin page's courses API (M12.3). */
class CourseRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val courses = InMemoryCourseStore()
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )
    private val log = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger("admin") as Logger).apply { addAppender(log) }
    private val nhms = Json.parseToJsonElement(File("../courses/seed/nhms.geojson").readText()).jsonObject

    @After
    fun detach() {
        logger.detachAppender(log)
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), InMemoryLiveHub(),
                messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = courses)
        }
    }

    private suspend fun ApplicationTestBuilder.signIn(): String =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod,
        path: String,
        cookie: String?,
        body: String? = null,
        origin: String? = "http://localhost",
    ): HttpResponse = client.request(path) {
        this.method = method
        header(HttpHeaders.Host, "localhost")
        origin?.let { header(HttpHeaders.Origin, it) }
        cookie?.let { header(HttpHeaders.Cookie, it) }
        body?.let { contentType(ContentType.Application.Json); setBody(it) }
    }

    private fun save(expected: Int, name: String, geojson: JsonObject = nhms) = buildJsonObject {
        put("expected", expected)
        put("name", name)
        put("geojson", geojson)
    }.toString()

    private fun json(response: HttpResponse) = runBlocking { Json.parseToJsonElement(response.bodyAsText()).jsonObject }

    @Test
    fun `without a sign-in, or from another page, nothing is read or changed`() = testApplication {
        app()
        call(HttpMethod.Get, "/api/admin/courses", null).status shouldBe HttpStatusCode.Unauthorized
        call(HttpMethod.Put, "/api/admin/courses/nhms", null, save(0, "NHMS")).status shouldBe HttpStatusCode.Unauthorized
        val cookie = signIn()
        call(HttpMethod.Put, "/api/admin/courses/nhms", cookie, save(0, "NHMS"), origin = "https://evil.example").status shouldBe
            HttpStatusCode.Forbidden
        courses.current() shouldBe emptyList()
    }

    @Test
    fun `a new course is version 1, each save the next, and a stale save is refused`() = testApplication {
        app()
        val cookie = signIn()
        val first = call(HttpMethod.Put, "/api/admin/courses/nhms", cookie, save(0, "NHMS"))
        first.status shouldBe HttpStatusCode.Created
        json(first).getValue("version").jsonPrimitive.content shouldBe "1"
        call(HttpMethod.Put, "/api/admin/courses/nhms", cookie, save(1, "New Hampshire Motor Speedway")).status shouldBe HttpStatusCode.OK
        // Someone opened version 1 and saves after the save above.
        val stale = call(HttpMethod.Put, "/api/admin/courses/nhms", cookie, save(1, "NHMS again"))
        stale.status shouldBe HttpStatusCode.Conflict
        stale.bodyAsText() shouldContain "Someone saved this course since you opened it"
        json(call(HttpMethod.Get, "/api/admin/courses/nhms?version=1", cookie)).getValue("name").jsonPrimitive.content shouldBe "NHMS"
        json(call(HttpMethod.Get, "/api/admin/courses/nhms", cookie)).getValue("version").jsonPrimitive.content shouldBe "2"
        Json.parseToJsonElement(call(HttpMethod.Get, "/api/admin/courses/nhms/versions", cookie).bodyAsText()).jsonArray
            .map { it.jsonObject.getValue("version").jsonPrimitive.content } shouldBe listOf("2", "1") // newest first
        val listed = Json.parseToJsonElement(call(HttpMethod.Get, "/api/admin/courses", cookie).bodyAsText()).jsonArray.single().jsonObject
        listed.getValue("layouts").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content } shouldBe
            listOf("road", "road-option", "road-option-2")
        log.list.map { it.formattedMessage } shouldContain "course saved: nhms v2 by sam@example.com"
    }

    @Test
    fun `an invalid course is refused with every problem, and nothing is stored`() = testApplication {
        app()
        val cookie = signIn()
        val bare = buildJsonObject { put("type", "FeatureCollection"); put("features", kotlinx.serialization.json.JsonArray(emptyList())) }
        val refused = call(HttpMethod.Put, "/api/admin/courses/Bad_Id", cookie, save(0, " ", bare))
        refused.status shouldBe HttpStatusCode.BadRequest
        val problems = json(refused).getValue("problems").jsonArray.map { it.jsonPrimitive.content }
        problems shouldContain "an id is 2–32 lower-case letters, digits and hyphens, starting with a letter"
        problems shouldContain "a name cannot be blank"
        problems shouldContain "a course needs at least one layout"
        courses.current() shouldBe emptyList()
    }

    @Test
    fun `a course laps were timed at is never deleted, and an unused one is`() = testApplication {
        app()
        val cookie = signIn()
        registry.addCar(Slug.parse("yaris"), "Yaris")
        call(HttpMethod.Put, "/api/admin/courses/nhms", cookie, save(0, "NHMS"))
        call(HttpMethod.Put, "/api/admin/courses/home-loop", cookie, save(0, "Home loop"))
        // A session whose laps were at NHMS (its summary's track).
        runBlocking {
            val id = "a1111111-1111-4111-8111-11111111aaaa"
            sessions.create(SessionRecord(id, "yaris", null, null, 10, emptyList(), true, null, 0, Instant.EPOCH, Instant.EPOCH))
            sessions.setSummary(
                id,
                SessionSummary(SessionSummary.VERSION, 0, 1, 11, emptyList(), track = "nhms", layout = "Road Course", laps = 3,
                    bestLap = null, faults = emptyList(), gaps = 0, missed = 0, unreadable = 0),
            )
        }
        val refused = call(HttpMethod.Delete, "/api/admin/courses/nhms", cookie)
        refused.status shouldBe HttpStatusCode.Conflict
        refused.bodyAsText() shouldContain "Laps were timed at this course"
        call(HttpMethod.Delete, "/api/admin/courses/home-loop", cookie).status shouldBe HttpStatusCode.NoContent
        call(HttpMethod.Get, "/api/admin/courses/home-loop", cookie).status shouldBe HttpStatusCode.NotFound
        call(HttpMethod.Delete, "/api/admin/courses/home-loop", cookie).status shouldBe HttpStatusCode.NotFound
        log.list.map { it.formattedMessage } shouldContain "course removed: home-loop by sam@example.com"
    }
}
