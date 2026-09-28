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
}
