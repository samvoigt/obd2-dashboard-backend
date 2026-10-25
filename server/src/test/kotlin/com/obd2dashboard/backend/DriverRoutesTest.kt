package com.obd2dashboard.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.slf4j.LoggerFactory

/** Who drove a session (M14.4): set by the admin or the car's crew, public to read. */
class DriverRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore())
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )
    private val log = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger("admin") as Logger).apply { addAppender(log) }
    private val json = Json { ignoreUnknownKeys = true }
    private val s = "5ace0000-1111-4111-8111-00000000d014"
    private val fake = "5ace0000-1111-4111-8111-00000000f014"
    private val theirs = "5ace0000-1111-4111-8111-00000000e014"

    init {
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            registry.setPasscode(Slug.parse("outback"), "outback-crew".toCharArray())
            registry.addCar(Slug.parse("yaris"), "Yaris")
            registry.setPasscode(Slug.parse("yaris"), "pit-lane".toCharArray())
            stores.drivers.put(Driver("d-sam", "Sam Voigt", "SAM"))
            stores.drivers.put(Driver("d-alex", "Alex Rider", "ALE"))
            for ((id, car, source) in listOf(Triple(s, "outback", null), Triple(fake, "outback", "fake"), Triple(theirs, "yaris", null))) {
                sessions.create(SessionRecord(id, car, SessionHeader(id, 3, "2026-10-04T13:00:00Z", "tab", null, null, null, source), null, 3, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
            }
        }
    }

    @After
    fun detach() {
        logger.detachAppender(log)
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), InMemoryLiveHub(),
                messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = testCourses(), events = stores)
        }
    }

    private suspend fun ApplicationTestBuilder.crew(car: String, passcode: String): String =
        client.post("/api/cars/$car/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"passcode":"$passcode"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.admin(): String =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.set(path: String, cookie: String?, driver: String?, origin: String? = "http://localhost"): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Host, "localhost")
            origin?.let { header(HttpHeaders.Origin, it) }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            contentType(ContentType.Application.Json)
            setBody(if (driver == null) """{"driver":null}""" else """{"driver":"$driver"}""")
        }

    private suspend fun ApplicationTestBuilder.driverOf(id: String): DriverView? =
        json.decodeFromString<SessionDetail>(client.get("/api/sessions/$id").bodyAsText()).driver

    @Test
    fun `the car's crew sets who drove its session, and clears it`() = testApplication {
        app()
        val c = crew("outback", "outback-crew")
        set("/api/cars/outback/sessions/$s/driver", c, "d-sam").status shouldBe HttpStatusCode.OK
        driverOf(s) shouldBe DriverView("d-sam", "Sam Voigt", "SAM")
        log.list.map { it.formattedMessage } shouldContain "driver set: session $s of outback to SAM by the crew of outback"
        set("/api/cars/outback/sessions/$s/driver", c, null).status shouldBe HttpStatusCode.OK
        driverOf(s) shouldBe null
    }

    @Test
    fun `the admin sets it for any car, from our page only`() = testApplication {
        app()
        val a = admin()
        set("/api/admin/sessions/$theirs/driver", a, "d-alex").status shouldBe HttpStatusCode.OK
        driverOf(theirs) shouldBe DriverView("d-alex", "Alex Rider", "ALE")
        log.list.map { it.formattedMessage } shouldContain "driver set: session $theirs of yaris to ALE by sam@example.com"
        set("/api/admin/sessions/$theirs/driver", a, "d-sam", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        set("/api/admin/sessions/5ace0000-1111-4111-8111-000000000000/driver", a, "d-sam").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `nobody else sets it, another car's crew included, nor to someone unknown, nor on test data`() = testApplication {
        app()
        val yaris = crew("yaris", "pit-lane")
        set("/api/cars/outback/sessions/$s/driver", null, "d-sam").status shouldBe HttpStatusCode.Unauthorized
        set("/api/admin/sessions/$s/driver", null, "d-sam").status shouldBe HttpStatusCode.Unauthorized
        set("/api/cars/outback/sessions/$s/driver", yaris, "d-sam").status shouldBe HttpStatusCode.Unauthorized // another car's login
        set("/api/cars/yaris/sessions/$s/driver", yaris, "d-sam").status shouldBe HttpStatusCode.NotFound // not their session
        val outback = crew("outback", "outback-crew")
        set("/api/cars/outback/sessions/$s/driver", outback, "d-nobody").status shouldBe HttpStatusCode.BadRequest
        set("/api/cars/outback/sessions/$fake/driver", outback, "d-sam").status shouldBe HttpStatusCode.Conflict
        driverOf(s) shouldBe null
        sessions.get(fake)!!.driver shouldBe null
    }

    @Test
    fun `drivers are public, names and codes`() = testApplication {
        app()
        json.decodeFromString<List<DriverView>>(client.get("/api/drivers").bodyAsText()).map { it.code } shouldBe listOf("ALE", "SAM")
    }

    // Names (M18.3)

    private suspend fun ApplicationTestBuilder.name(path: String, cookie: String?, name: String?): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
            contentType(ContentType.Application.Json)
            setBody(if (name == null) """{"name":null}""" else """{"name":${Json.encodeToString(kotlinx.serialization.serializer<String>(), name)}}""")
        }

    private suspend fun ApplicationTestBuilder.nameOf(id: String): String? =
        json.decodeFromString<SessionDetail>(client.get("/api/sessions/$id").bodyAsText()).session.name

    @Test
    fun `the crew names its session, trimmed, shown in its lists, and clears it (M18_3)`() = testApplication {
        app()
        val c = crew("outback", "outback-crew")
        name("/api/cars/outback/sessions/$s/name", c, "  Practice 2, wet  ").status shouldBe HttpStatusCode.OK
        nameOf(s) shouldBe "Practice 2, wet"
        log.list.map { it.formattedMessage } shouldContain "session named: $s of outback to \"Practice 2, wet\" by the crew of outback"
        json.decodeFromString<List<Drive>>(client.get("/api/cars/outback/sessions").bodyAsText()).flatMap { it.sessions }.single { it.id == s }.name shouldBe "Practice 2, wet"
        // Test data can be named: it says what the test was.
        name("/api/cars/outback/sessions/$fake/name", c, "Fake: pit stop").status shouldBe HttpStatusCode.OK
        name("/api/cars/outback/sessions/$s/name", c, "   ").status shouldBe HttpStatusCode.OK
        nameOf(s) shouldBe null
    }

    @Test
    fun `the admin names any car's session, shown on the admin page, nobody else, and nothing too long or on two lines (M18_3)`() = testApplication {
        app()
        val a = admin()
        name("/api/admin/sessions/$theirs/name", a, "Yaris shakedown").status shouldBe HttpStatusCode.OK
        nameOf(theirs) shouldBe "Yaris shakedown"
        client.get("/api/admin/cars/yaris/sessions") { header(HttpHeaders.Cookie, a) }.bodyAsText().contains("\"name\":\"Yaris shakedown\"") shouldBe true
        val yaris = crew("yaris", "pit-lane")
        name("/api/cars/outback/sessions/$s/name", null, "x").status shouldBe HttpStatusCode.Unauthorized
        name("/api/cars/yaris/sessions/$s/name", yaris, "x").status shouldBe HttpStatusCode.NotFound // outback's
        val c = crew("outback", "outback-crew")
        name("/api/cars/outback/sessions/$s/name", c, "x".repeat(61)).status shouldBe HttpStatusCode.BadRequest
        name("/api/cars/outback/sessions/$s/name", c, "x".repeat(60)).status shouldBe HttpStatusCode.OK
        name("/api/cars/outback/sessions/$s/name", c, "two\nlines").status shouldBe HttpStatusCode.BadRequest
        nameOf(s) shouldBe "x".repeat(60)
    }

    private suspend fun ApplicationTestBuilder.changes(path: String, cookie: String?, body: String, origin: String? = "http://localhost"): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Host, "localhost")
            origin?.let { header(HttpHeaders.Origin, it) }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `driver changes within a session (M27) - the crew and the admin set the whole list, nobody else, checked`() = testApplication {
        app()
        val c = crew("outback", "outback-crew")
        set("/api/cars/outback/sessions/$s/driver", c, "d-sam").status shouldBe HttpStatusCode.OK
        changes("/api/cars/outback/sessions/$s/driver-changes", c, """{"changes":[{"from":2000,"driver":"d-sam"},{"from":1000,"driver":"d-alex"}]}""").let {
            it.status shouldBe HttpStatusCode.OK
            it.bodyAsText() shouldBe """{"changes":[{"from":1000,"driver":"d-alex"},{"from":2000,"driver":"d-sam"}]}""" // in order
        }
        json.decodeFromString<SessionDetail>(client.get("/api/sessions/$s").bodyAsText()).let {
            it.driver shouldBe DriverView("d-sam", "Sam Voigt", "SAM") // from the start
            it.driverChanges shouldBe listOf(DriverChangeDetail(1000, DriverView("d-alex", "Alex Rider", "ALE")), DriverChangeDetail(2000, DriverView("d-sam", "Sam Voigt", "SAM")))
        }
        log.list.map { it.formattedMessage } shouldContain "driver changes: session $s of outback, d-alex from 1000, d-sam from 2000 by the crew of outback"
        // The admin, for any car; an empty list is the session's driver throughout again.
        val a = admin()
        changes("/api/admin/sessions/$s/driver-changes", a, """{"changes":[]}""").status shouldBe HttpStatusCode.OK
        sessions.get(s)!!.driverChanges shouldBe emptyList()
        changes("/api/admin/sessions/$s/driver-changes", a, """{"changes":[]}""", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        // Nobody else; and never two at one moment, an unknown driver, a moment that isn't one, or test data.
        val yaris = crew("yaris", "pit-lane")
        changes("/api/cars/outback/sessions/$s/driver-changes", null, """{"changes":[]}""").status shouldBe HttpStatusCode.Unauthorized
        changes("/api/cars/outback/sessions/$s/driver-changes", yaris, """{"changes":[]}""").status shouldBe HttpStatusCode.Unauthorized
        changes("/api/cars/yaris/sessions/$s/driver-changes", yaris, """{"changes":[]}""").status shouldBe HttpStatusCode.NotFound
        changes("/api/cars/outback/sessions/$s/driver-changes", c, """{"changes":[{"from":1000,"driver":"d-sam"},{"from":1000,"driver":"d-alex"}]}""").status shouldBe HttpStatusCode.BadRequest
        changes("/api/cars/outback/sessions/$s/driver-changes", c, """{"changes":[{"from":1000,"driver":"d-nobody"}]}""").status shouldBe HttpStatusCode.BadRequest
        changes("/api/cars/outback/sessions/$s/driver-changes", c, """{"changes":[{"from":0,"driver":"d-sam"}]}""").status shouldBe HttpStatusCode.BadRequest
        changes("/api/cars/outback/sessions/$fake/driver-changes", c, """{"changes":[]}""").status shouldBe HttpStatusCode.Conflict
        sessions.get(s)!!.driverChanges shouldBe emptyList()
    }

    // GPS latency and videos (M30)

    private suspend fun ApplicationTestBuilder.putJson(path: String, cookie: String?, body: String): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.detailOf(id: String): SessionDetail =
        json.decodeFromString<SessionDetail>(client.get("/api/sessions/$id").bodyAsText())

    @Test
    fun `the crew sets its session's GPS latency, shown on its page, and clears it with nothing or 0 (M30_2)`() = testApplication {
        app()
        val c = crew("outback", "outback-crew")
        putJson("/api/cars/outback/sessions/$s/gps-latency", c, """{"latencyMs":650}""").status shouldBe HttpStatusCode.OK
        detailOf(s).gpsLatencyMs shouldBe 650L
        log.list.map { it.formattedMessage } shouldContain "gps latency: session $s of outback to 650 ms by the crew of outback"
        putJson("/api/cars/outback/sessions/$s/gps-latency", c, """{"latencyMs":0}""").status shouldBe HttpStatusCode.OK
        detailOf(s).gpsLatencyMs shouldBe null
        putJson("/api/cars/outback/sessions/$s/gps-latency", c, """{"latencyMs":5001}""").status shouldBe HttpStatusCode.BadRequest
        putJson("/api/cars/outback/sessions/$s/gps-latency", c, """{"latencyMs":-1}""").status shouldBe HttpStatusCode.BadRequest
        putJson("/api/cars/outback/sessions/$s/gps-latency", null, """{"latencyMs":650}""").status shouldBe HttpStatusCode.Unauthorized
        putJson("/api/cars/yaris/sessions/$s/gps-latency", crew("yaris", "pit-lane"), """{"latencyMs":650}""").status shouldBe HttpStatusCode.NotFound
        putJson("/api/admin/sessions/$theirs/gps-latency", admin(), """{"latencyMs":400}""").status shouldBe HttpStatusCode.OK
        detailOf(theirs).gpsLatencyMs shouldBe 400L
    }

    @Test
    fun `the crew sets its session's videos, in order and trimmed, and nothing that can't be placed (M30_3)`() = testApplication {
        app()
        val c = crew("outback", "outback-crew")
        val two = """{"videos":[{"file":" GP020028.MP4 ","startWall":1792328501000,"durationMs":1193726},""" +
            """{"file":"GP010028.MP4","startWall":1792327307300,"durationMs":1193726}]}"""
        putJson("/api/cars/outback/sessions/$s/videos", c, two).status shouldBe HttpStatusCode.OK
        detailOf(s).videos shouldBe listOf(
            SessionVideoView("GP010028.MP4", 1792327307300, 1193726),
            SessionVideoView("GP020028.MP4", 1792328501000, 1193726),
        )
        log.list.map { it.formattedMessage } shouldContain "videos: session $s of outback, 2 by the crew of outback"
        for (bad in listOf(
            """{"videos":[{"file":"","startWall":1,"durationMs":1}]}""",
            """{"videos":[{"file":"two\nlines","startWall":1,"durationMs":1}]}""",
            """{"videos":[{"file":"a.mp4","startWall":0,"durationMs":1}]}""",
            """{"videos":[{"file":"a.mp4","startWall":1,"durationMs":0}]}""",
            """{"videos":[{"file":"a.mp4","startWall":1,"durationMs":86400001}]}""",
        )) putJson("/api/cars/outback/sessions/$s/videos", c, bad).status shouldBe HttpStatusCode.BadRequest
        putJson("/api/cars/outback/sessions/$s/videos", null, two).status shouldBe HttpStatusCode.Unauthorized
        putJson("/api/cars/outback/sessions/$s/videos", c, """{"videos":[]}""").status shouldBe HttpStatusCode.OK
        detailOf(s).videos shouldBe emptyList()
    }
}
