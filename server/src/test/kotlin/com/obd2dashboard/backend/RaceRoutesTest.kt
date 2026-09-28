package com.obd2dashboard.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.events.Stint
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test
import org.slf4j.LoggerFactory

/** The race's flags and stints (M15.3): the admin or the crew of a car entered. */
class RaceRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore())
    private val courses = testCourses()
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )
    private val log = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger("admin") as Logger).apply { addAppender(log) }
    private val t0 = Instant.parse("2026-10-04T13:00:00Z")

    init {
        runBlocking {
            for ((slug, pass) in listOf("outback" to "outback-crew", "yaris" to "pit-lane", "miata" to "miata-crew")) {
                registry.addCar(Slug.parse(slug), slug.replaceFirstChar { it.uppercase() })
                registry.setPasscode(Slug.parse(slug), pass.toCharArray())
            }
            courses.save("nhms", 0, "NHMS", Json.parseToJsonElement(File("../courses/seed/nhms.geojson").readText()).jsonObject, Instant.EPOCH)
            stores.drivers.put(Driver("d-sam", "Sam Voigt", "SAM"))
            stores.events.save(Event("race-day", "Race day", "2026-10-04", "nhms", "road", listOf("outback", "yaris"), listOf(
                Part("p1", PartKind.PRACTICE, "Practice", t0, t0.plusSeconds(3600)),
                Part("p2", PartKind.RACE, "Race", t0.plusSeconds(3600), t0.plusSeconds(8 * 3600)),
            )), 0, t0)
            stores.events.save(Event("practice-day", "Practice day", "2026-10-05", "nhms", "road", listOf("outback"),
                listOf(Part("p1", PartKind.PRACTICE, "Practice", t0, t0.plusSeconds(3600)))), 0, t0)
        }
    }

    @After
    fun detach() {
        logger.detachAppender(log)
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = courses, events = stores)
        }
    }

    private suspend fun ApplicationTestBuilder.crew(car: String, passcode: String) =
        client.post("/api/cars/$car/login") { contentType(ContentType.Application.Json); setBody("""{"passcode":"$passcode"}""") }
            .headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.admin() =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json); setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.put(path: String, cookie: String?, body: String, origin: String? = "http://localhost"): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Host, "localhost"); origin?.let { header(HttpHeaders.Origin, it) }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            contentType(ContentType.Application.Json); setBody(body)
        }

    private fun race() = runBlocking { stores.events.get("race-day")!!.race!! }

    @Test
    fun `the admin sets the flags, a stale edit refused, the flag after the green`() = testApplication {
        app()
        val a = admin()
        val green = t0.plusSeconds(3700).toEpochMilli()
        val flag = t0.plusSeconds(7 * 3600).toEpochMilli()
        put("/api/admin/events/race-day/race", a, """{"expected":1,"green":$green,"flag":$flag}""").let {
            it.status shouldBe HttpStatusCode.OK
            it.bodyAsText() shouldBe """{"revision":2}"""
        }
        race().green shouldBe Instant.ofEpochMilli(green)
        race().flag shouldBe Instant.ofEpochMilli(flag)
        put("/api/admin/events/race-day/race", a, """{"expected":1,"green":$green}""").status shouldBe HttpStatusCode.Conflict
        put("/api/admin/events/race-day/race", a, """{"expected":2,"green":$flag,"flag":$green}""").bodyAsText() shouldContain "the flag falls after the green flag"
        put("/api/admin/events/race-day/race", a, """{"expected":2,"green":$green}""", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        put("/api/admin/events/practice-day/race", a, """{"expected":1,"green":$green}""").status shouldBe HttpStatusCode.Conflict
        put("/api/admin/events/nope/race", a, """{"expected":1}""").status shouldBe HttpStatusCode.NotFound
        log.list.map { it.formattedMessage }.any { it.startsWith("race of race-day set, flags") && it.endsWith("by sam@example.com") } shouldBe true
    }

    @Test
    fun `a car's crew sets its stints, and back to the default, and nobody else`() = testApplication {
        app()
        val outback = crew("outback", "outback-crew")
        put("/api/cars/outback/events/race-day/race/stints", outback, """{"expected":1,"stints":[{"start":2000,"driver":"d-sam"},{"start":1000}]}""")
            .status shouldBe HttpStatusCode.OK
        race().stints shouldBe mapOf("outback" to listOf(Stint(1000, null), Stint(2000, "d-sam")))
        log.list.map { it.formattedMessage } shouldContain "race of race-day set, 2 stint(s) of outback, by the crew of outback"
        put("/api/cars/outback/events/race-day/race/stints", outback, """{"expected":2,"stints":null}""").status shouldBe HttpStatusCode.OK
        race().stints shouldBe emptyMap()

        put("/api/cars/outback/events/race-day/race/stints", outback, """{"expected":3,"stints":[{"start":1,"driver":"d-nobody"}]}""").let {
            it.status shouldBe HttpStatusCode.BadRequest; it.bodyAsText() shouldContain "there's no driver d-nobody"
        }
        put("/api/cars/outback/events/race-day/race/stints", outback, """{"expected":3,"stints":[{"start":1},{"start":1}]}""").bodyAsText() shouldContain "start at once"
        put("/api/cars/outback/events/race-day/race/stints", null, """{"expected":3,"stints":[]}""").status shouldBe HttpStatusCode.Unauthorized
        put("/api/cars/yaris/events/race-day/race/stints", outback, """{"expected":3,"stints":[]}""").status shouldBe HttpStatusCode.Unauthorized // another car's login
        val miata = crew("miata", "miata-crew")
        put("/api/cars/miata/events/race-day/race/stints", miata, """{"expected":3,"stints":[{"start":1}]}""").status shouldBe HttpStatusCode.NotFound // not entered
        // The crew sets the flags too.
        put("/api/cars/yaris/events/race-day/race", crew("yaris", "pit-lane"), """{"expected":3,"green":${t0.toEpochMilli() + 3_700_000}}""").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `the admin sets any car's stints, and the event editor's save keeps them and the flags`() = testApplication {
        app()
        val a = admin()
        put("/api/admin/events/race-day/race/stints/yaris", a, """{"expected":1,"stints":[{"start":5,"driver":"d-sam"}]}""").status shouldBe HttpStatusCode.OK
        put("/api/admin/events/race-day/race", a, """{"expected":2,"green":${t0.toEpochMilli() + 3_700_000}}""").status shouldBe HttpStatusCode.OK
        put("/api/admin/events/race-day/race/stints/miata", a, """{"expected":3,"stints":[{"start":5}]}""").let {
            it.status shouldBe HttpStatusCode.NotFound; it.bodyAsText() shouldContain "This car isn't in that event."
        }
        // The editor saves the event, renaming the race: the flags and stints stay.
        val t = t0.toEpochMilli()
        put("/api/admin/events/race-day", a, """{"expected":3,"name":"Race day","date":"2026-10-04","course":"nhms","layout":"road","cars":["outback","yaris"],"parts":[
            {"id":"p1","kind":"practice","name":"Practice","start":$t,"end":${t + 3_600_000}},
            {"id":"p2","kind":"race","name":"The 6 hours","start":${t + 3_600_000},"end":${t + 8 * 3_600_000}}]}""").status shouldBe HttpStatusCode.OK
        race().name shouldBe "The 6 hours"
        race().stints shouldBe mapOf("yaris" to listOf(Stint(5, "d-sam")))
        race().green shouldBe Instant.ofEpochMilli(t + 3_700_000)
    }
}
