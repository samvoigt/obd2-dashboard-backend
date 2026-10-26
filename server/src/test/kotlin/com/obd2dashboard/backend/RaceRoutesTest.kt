package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.InMemoryAccessStore
import com.obd2dashboard.backend.admin.InMemoryUserStore
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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

    // M23: three invited users beside the master admin (sam@): Ann makes things, Ed is added to them, Olga to nothing.
    private val users = InMemoryUserStore().apply {
        runBlocking { for (e in listOf("ann", "ed", "olga")) add(User("$e@example.com", "sam@example.com", Instant.EPOCH)) }
    }
    private val access = InMemoryAccessStore()

    /** A signed-in cookie for [email], as the login would set it. */
    private fun cookieOf(email: String) = "${AdminAuth.COOKIE}=${AdminAuth(testCrewKey()).issue(email)}"

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
            module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = courses, events = stores,
                users = users, access = access)
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
    fun `stints aren't edited any more, by the crew or the admin, and the crew still sets the flags (M32)`() = testApplication {
        app()
        val outback = crew("outback", "outback-crew")
        // Decision 48: a race's stints follow from who drove; the routes that edited them are gone.
        put("/api/cars/outback/events/race-day/race/stints", outback, """{"expected":1,"stints":[{"start":1000,"driver":"d-sam"}]}""").status shouldBe HttpStatusCode.NotFound
        put("/api/admin/events/race-day/race/stints/outback", admin(), """{"expected":1,"stints":[]}""").status shouldBe HttpStatusCode.NotFound
        race().stints shouldBe emptyMap()
        put("/api/cars/yaris/events/race-day/race", crew("yaris", "pit-lane"), """{"expected":1,"green":${t0.toEpochMilli() + 3_700_000}}""").status shouldBe HttpStatusCode.OK
        put("/api/cars/outback/events/race-day/race", null, """{"expected":2}""").status shouldBe HttpStatusCode.Unauthorized
        val miata = crew("miata", "miata-crew")
        put("/api/cars/miata/events/race-day/race", miata, """{"expected":2}""").status shouldBe HttpStatusCode.NotFound // not entered
    }

    @Test
    fun `the event editor's save keeps the race's flags`() = testApplication {
        app()
        val a = admin()
        put("/api/admin/events/race-day/race", a, """{"expected":1,"green":${t0.toEpochMilli() + 3_700_000}}""").status shouldBe HttpStatusCode.OK
        // The editor saves the event, renaming the race: the flags stay.
        val t = t0.toEpochMilli()
        put("/api/admin/events/race-day", a, """{"expected":2,"name":"Race day","date":"2026-10-04","course":"nhms","layout":"road","cars":["outback","yaris"],"parts":[
            {"id":"p1","kind":"practice","name":"Practice","start":$t,"end":${t + 3_600_000}},
            {"id":"p2","kind":"race","name":"The 6 hours","start":${t + 3_600_000},"end":${t + 8 * 3_600_000}}]}""").status shouldBe HttpStatusCode.OK
        race().name shouldBe "The 6 hours"
        race().green shouldBe Instant.ofEpochMilli(t + 3_700_000)
    }

    @Test
    fun `a race's flags are set by the event's creator and editors, nobody else (M23)`() = testApplication {
        app()
        val (ann, ed, olga) = listOf("ann", "ed", "olga").map { cookieOf("$it@example.com") }
        val green = t0.plusSeconds(3700).toEpochMilli()
        // race-day was made before M23: Sam's alone until Ann is given it.
        put("/api/admin/events/race-day/race", ann, """{"expected":1,"green":$green}""").status shouldBe HttpStatusCode.Forbidden
        access.set(Thing(Kind.EVENT, "race-day"), Access("ann@example.com", setOf("ed@example.com")))
        put("/api/admin/events/race-day/race", ann, """{"expected":1,"green":$green}""").status shouldBe HttpStatusCode.OK
        put("/api/admin/events/race-day/race", ed, """{"expected":2,"green":$green}""").status shouldBe HttpStatusCode.OK
        put("/api/admin/events/race-day/race", olga, """{"expected":3,"green":$green}""").status shouldBe HttpStatusCode.Forbidden
        race().green shouldBe Instant.ofEpochMilli(green)
        log.list.map { it.formattedMessage }.any { it.startsWith("race of race-day set") && it.endsWith("by ed@example.com") } shouldBe true
    }

    @Test
    fun `a race in sessions - each session's flags are its own, and an edit says which (M26)`() = testApplication {
        app()
        runBlocking {
            stores.events.save(Event("weekend", "Weekend", "2026-10-03", "nhms", "road", listOf("outback", "yaris"), listOf(
                Part("p1", PartKind.PRACTICE, "Practice", t0, t0.plusSeconds(3600)),
                Part("p2", PartKind.RACE, "Race, Saturday", t0.plusSeconds(3600), t0.plusSeconds(8 * 3600)),
                Part("p3", PartKind.RACE, "Race, Sunday", t0.plusSeconds(86_400), t0.plusSeconds(86_400 + 6 * 3600)),
            ), endDate = "2026-10-04"), 0, t0)
        }
        val a = admin()
        val sundayGreen = t0.plusSeconds(86_500).toEpochMilli()
        put("/api/admin/events/weekend/race", a, """{"expected":1,"green":$sundayGreen}""").let {
            it.status shouldBe HttpStatusCode.BadRequest
            it.bodyAsText() shouldContain "say which"
        }
        put("/api/admin/events/weekend/race", a, """{"expected":1,"green":$sundayGreen,"part":"p9"}""").status shouldBe HttpStatusCode.NotFound
        put("/api/admin/events/weekend/race", a, """{"expected":1,"green":$sundayGreen,"part":"p1"}""").status shouldBe HttpStatusCode.NotFound // practice
        put("/api/admin/events/weekend/race", a, """{"expected":1,"green":$sundayGreen,"part":"p3"}""").status shouldBe HttpStatusCode.OK
        runBlocking { stores.events.get("weekend")!! }.races.let { (sat, sun) ->
            sat.green shouldBe null
            sun.green shouldBe Instant.ofEpochMilli(sundayGreen)
        }
        log.list.map { it.formattedMessage } shouldContain "race of weekend (p3) set, flags ${Instant.ofEpochMilli(sundayGreen)} to null, by sam@example.com"
    }
}
