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
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
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
import kotlinx.serialization.json.jsonObject
import io.ktor.client.request.get
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.slf4j.LoggerFactory

/** The admin page's drivers and events (M14.3). */
class EventRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val courses = InMemoryCourseStore()
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore())
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )
    private val log = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger("admin") as Logger).apply { addAppender(log) }
    private val json = Json { ignoreUnknownKeys = true }
    private val t0 = Instant.parse("2026-10-04T13:00:00Z")
    private fun ms(minutes: Long) = t0.plusSeconds(minutes * 60).toEpochMilli()

    // M23: three invited users beside the master admin (sam@): Ann makes things, Ed is added to them, Olga to nothing.
    private val users = InMemoryUserStore().apply {
        runBlocking { for (e in listOf("ann", "ed", "olga")) add(User("$e@example.com", "sam@example.com", Instant.EPOCH)) }
    }
    private val access = InMemoryAccessStore()

    /** A signed-in cookie for [email], as the login would set it. */
    private fun cookieOf(email: String) = "${AdminAuth.COOKIE}=${AdminAuth(testCrewKey()).issue(email)}"

    init {
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            registry.addCar(Slug.parse("yaris"), "Yaris")
            courses.save("nhms", 0, "NHMS", Json.parseToJsonElement(File("../courses/seed/nhms.geojson").readText()).jsonObject, Instant.EPOCH)
        }
    }

    @After
    fun detach() {
        logger.detachAppender(log)
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), InMemoryLiveHub(),
                messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = courses, events = stores,
                users = users, access = access)
        }
    }

    private suspend fun ApplicationTestBuilder.signIn(): String =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, cookie: String?, body: String? = null, origin: String? = "http://localhost"): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Host, "localhost")
            origin?.let { header(HttpHeaders.Origin, it) }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private fun session(id: String, car: String, from: Long, to: Long, source: String? = null, driver: String? = null) = runBlocking {
        val header = SessionHeader(id, 3, "2026-10-04T13:00:00Z", "tab", null, null, null, source)
        sessions.create(SessionRecord(id, car, header, null, 10, emptyList(), true, null, 0,
            Instant.ofEpochMilli(ms(from)), Instant.ofEpochMilli(ms(to)), driver = driver))
    }

    private fun event(expected: Int, parts: String, cars: String = """["outback"]""", course: String = "nhms", layout: String = "road") =
        """{"expected":$expected,"name":"NHMS October","date":"2026-10-04","course":"$course","layout":"$layout","cars":$cars,"parts":$parts}"""

    private fun practice(from: Long, to: Long, name: String = "Practice", id: String? = null, added: String = "[]", removed: String = "[]") =
        """{${id?.let { "\"id\":\"$it\"," } ?: ""}"kind":"practice","name":"$name","start":${ms(from)},"end":${ms(to)},"added":$added,"removed":$removed}"""

    @Test
    fun `without a sign-in, or from another page, nothing is read or changed`() = testApplication {
        app()
        call(HttpMethod.Get, "/api/admin/drivers", null).status shouldBe HttpStatusCode.Unauthorized
        call(HttpMethod.Get, "/api/admin/events", null).status shouldBe HttpStatusCode.Unauthorized
        call(HttpMethod.Post, "/api/admin/drivers", null, """{"name":"Sam","code":"SAM"}""").status shouldBe HttpStatusCode.Unauthorized
        val cookie = signIn()
        call(HttpMethod.Post, "/api/admin/drivers", cookie, """{"name":"Sam","code":"SAM"}""", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie, event(0, "[]"), origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        stores.drivers.list() shouldBe emptyList()
        stores.events.list() shouldBe emptyList()
    }

    @Test
    fun `drivers are added, renamed and removed, codes kept unique, and one who drove stays`() = testApplication {
        app()
        val cookie = signIn()
        val sam = json.decodeFromString<DriverView>(call(HttpMethod.Post, "/api/admin/drivers", cookie, """{"name":" Sam ","code":"SAM"}""")
            .also { it.status shouldBe HttpStatusCode.Created }.bodyAsText())
        sam.name shouldBe "Sam"
        sam.id.matches(Regex("d-[0-9a-f]{8}")) shouldBe true
        val taken = call(HttpMethod.Post, "/api/admin/drivers", cookie, """{"name":"Samantha","code":"SAM"}""")
        taken.status shouldBe HttpStatusCode.BadRequest
        taken.bodyAsText() shouldContain "Sam already has the code SAM"
        call(HttpMethod.Put, "/api/admin/drivers/${sam.id}", cookie, """{"name":"Sam V","code":"SAM"}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Put, "/api/admin/drivers/d-00000000", cookie, """{"name":"Nobody","code":"NOB"}""").status shouldBe HttpStatusCode.NotFound
        call(HttpMethod.Post, "/api/admin/drivers", cookie, """{"name":"","code":"x"}""").bodyAsText() shouldContain "a driver's code is 2–4 capital letters"
        val alex = json.decodeFromString<DriverView>(call(HttpMethod.Post, "/api/admin/drivers", cookie, """{"name":"Alex","code":"ALX"}""").bodyAsText())

        session("s1", "outback", 0, 30, driver = sam.id)
        call(HttpMethod.Delete, "/api/admin/drivers/${sam.id}", cookie).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText() shouldContain "Sam V drove sessions, so stays"
        }
        call(HttpMethod.Delete, "/api/admin/drivers/${alex.id}", cookie).status shouldBe HttpStatusCode.NoContent
        json.decodeFromString<List<DriverView>>(call(HttpMethod.Get, "/api/admin/drivers", cookie).bodyAsText()).map { it.name } shouldBe listOf("Sam V")
        log.list.map { it.formattedMessage } shouldContain "driver saved: Sam V (SAM) by sam@example.com"
        log.list.map { it.formattedMessage } shouldContain "driver removed: Alex (ALX) by sam@example.com"
    }

    @Test
    fun `an event is saved, its new parts given ids, every problem said, and a stale save refused`() = testApplication {
        app()
        val cookie = signIn()
        val created = call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie, event(0, "[${practice(0, 60, "Practice 1")},${practice(60, 120, "Practice 2")}]"))
        created.status shouldBe HttpStatusCode.Created
        val saved = json.decodeFromString<EventView>(created.bodyAsText())
        saved.parts.map { it.id } shouldBe listOf("p1", "p2")
        saved.revision shouldBe 1
        // A third part, added between: the next id, whatever its place.
        val again = json.decodeFromString<EventView>(call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie,
            event(1, "[${practice(0, 60, "Practice 1", "p1")},${practice(60, 90, "Warm-up")},${practice(90, 120, "Practice 2", "p2")}]")).bodyAsText())
        again.parts.map { it.id to it.name } shouldBe listOf("p1" to "Practice 1", "p3" to "Warm-up", "p2" to "Practice 2")

        call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie, event(1, "[]")).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText() shouldContain "Someone saved this event since you opened it"
        }
        call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie, event(0, "[]")).bodyAsText() shouldContain "An event already has that id."
        val refused = call(HttpMethod.Put, "/api/admin/events/Bad", cookie, event(0, "[${practice(10, 5)}]", cars = """["outback","nope"]""", layout = "oval"))
        refused.status shouldBe HttpStatusCode.BadRequest
        val body = refused.bodyAsText()
        body shouldContain "an id is 2–32 lower-case letters"
        body shouldContain "Practice: it ends after it starts"
        body shouldContain "there's no car nope"
        body shouldContain "NHMS has no layout oval"
        call(HttpMethod.Put, "/api/admin/events/elsewhere", cookie, event(0, "[]", course = "nowhere")).bodyAsText() shouldContain "there's no course nowhere"
        log.list.map { it.formattedMessage } shouldContain "event saved: nhms-october r2 by sam@example.com"
    }

    @Test
    fun `an event's page lists each part's sessions, and the others around its day to add by hand`() = testApplication {
        app()
        val cookie = signIn()
        session("in-p1", "outback", 10, 20)
        session("in-p2", "outback", 70, 80, source = "tablet")
        session("removed", "outback", 30, 40)
        session("late", "outback", 400, 410) // uploaded hours after: offered, not placed
        session("fake", "outback", 15, 16, source = "fake")
        session("other-car", "yaris", 10, 20)
        session("last-week", "outback", -7 * 24 * 60, -7 * 24 * 60 + 10)
        call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie,
            event(0, "[${practice(0, 60, "Practice 1", removed = """["removed"]""")},${practice(60, 120, "Practice 2")}]"))
        val page = json.decodeFromString<AdminEvent>(call(HttpMethod.Get, "/api/admin/events/nhms-october", cookie).bodyAsText())
        page.sessions.mapValues { (_, list) -> list.map { it.id } } shouldBe mapOf("p1" to listOf("in-p1"), "p2" to listOf("in-p2"))
        page.sessions.getValue("p2").single().source shouldBe "tablet"
        page.sessions.getValue("p1").single().heardFrom shouldBe ms(10)
        page.others.map { it.id } shouldBe listOf("removed", "late")

        // Added by hand: now in Practice 2, and no longer offered.
        call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie,
            event(1, "[${practice(0, 60, "Practice 1", "p1", removed = """["removed"]""")},${practice(60, 120, "Practice 2", "p2", added = """["late","late"]""")}]"))
        val after = json.decodeFromString<AdminEvent>(call(HttpMethod.Get, "/api/admin/events/nhms-october", cookie).bodyAsText())
        after.sessions.getValue("p2").map { it.id } shouldBe listOf("in-p2", "late")
        after.others.map { it.id } shouldBe listOf("removed")
        after.event.parts.last().added shouldBe listOf("late") // once, however often sent

        // A session the live lane made has no header: its summary says it's fake data, so it's never offered.
        runBlocking {
            sessions.create(SessionRecord("live-fake", "outback", null, null, 10, emptyList(), true, null, 0,
                Instant.ofEpochMilli(ms(20)), Instant.ofEpochMilli(ms(25)),
                summary = com.obd2dashboard.backend.archive.SessionReader().apply {
                    read("""{"type":"session","v":3,"id":"live-fake","source":"fake","started":"2026-10-04T13:20:00Z","signals":[],"seq":0,"at":0}""".byteInputStream())
                }.summary()))
        }
        json.decodeFromString<AdminEvent>(call(HttpMethod.Get, "/api/admin/events/nhms-october", cookie).bodyAsText())
            .let { page -> (page.sessions.values.flatten() + page.others).none { it.id == "live-fake" } shouldBe true }
        call(HttpMethod.Get, "/api/admin/events/nope", cookie).status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `an event is removed, its sessions untouched`() = testApplication {
        app()
        val cookie = signIn()
        session("s", "outback", 10, 20)
        val before = sessions.get("s")
        call(HttpMethod.Put, "/api/admin/events/nhms-october", cookie, event(0, "[${practice(0, 60)}]"))
        call(HttpMethod.Delete, "/api/admin/events/nhms-october", cookie).status shouldBe HttpStatusCode.NoContent
        call(HttpMethod.Delete, "/api/admin/events/nhms-october", cookie).status shouldBe HttpStatusCode.NotFound
        sessions.get("s") shouldBe before
        stores.events.list() shouldBe emptyList()
    }

    private fun ids(response: HttpResponse) = runBlocking {
        json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    @Test
    fun `a user makes an event with anyone's cars and course, an editor changes it, and only she removes it (M23)`() = testApplication {
        app()
        val sam = signIn()
        val (ann, ed, olga) = listOf("ann", "ed", "olga").map { cookieOf("$it@example.com") }
        // Sam's cars and course: Ann's event may use them (Sam, 2026-10-01).
        call(HttpMethod.Put, "/api/admin/events/ann-day", ann, event(0, "[]")).status shouldBe HttpStatusCode.Created
        access.get(Thing(Kind.EVENT, "ann-day")) shouldBe Access("ann@example.com")
        ids(call(HttpMethod.Get, "/api/admin/events", ann)) shouldBe listOf("ann-day")
        ids(call(HttpMethod.Get, "/api/admin/events", olga)) shouldBe emptyList()
        call(HttpMethod.Get, "/api/admin/events/ann-day", olga).status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Put, "/api/admin/events/ann-day", olga, event(1, "[]")).status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Delete, "/api/admin/events/ann-day", olga).status shouldBe HttpStatusCode.Forbidden

        access.set(Thing(Kind.EVENT, "ann-day"), Access("ann@example.com", setOf("ed@example.com")))
        call(HttpMethod.Get, "/api/admin/events/ann-day", ed).status shouldBe HttpStatusCode.OK
        call(HttpMethod.Put, "/api/admin/events/ann-day", ed, event(1, "[]", cars = """["outback","yaris"]""")).status shouldBe HttpStatusCode.OK
        call(HttpMethod.Delete, "/api/admin/events/ann-day", ed).status shouldBe HttpStatusCode.Forbidden

        // An event made before M23 (no record) is Sam's alone; he sees and may delete everything.
        stores.events.save(com.obd2dashboard.backend.events.Event("old-day", "Old day", "2026-09-01", "nhms", "road", listOf("outback"), emptyList()), 0, t0)
        call(HttpMethod.Put, "/api/admin/events/old-day", ann, event(1, "[]")).status shouldBe HttpStatusCode.Forbidden
        ids(call(HttpMethod.Get, "/api/admin/events", sam)).toSet() shouldBe setOf("ann-day", "old-day")
        client.get("/api/events").bodyAsText().contains("\"access\"") shouldBe false

        call(HttpMethod.Delete, "/api/admin/events/ann-day", ann).status shouldBe HttpStatusCode.NoContent
        access.get(Thing(Kind.EVENT, "ann-day")) shouldBe null
        call(HttpMethod.Delete, "/api/admin/events/old-day", sam).status shouldBe HttpStatusCode.NoContent
    }

    @Test
    fun `any user adds a driver and is its creator, an editor renames, and only the creator or Sam removes (M23)`() = testApplication {
        app()
        val sam = signIn()
        val (ann, ed, olga) = listOf("ann", "ed", "olga").map { cookieOf("$it@example.com") }
        val id = json.parseToJsonElement(call(HttpMethod.Post, "/api/admin/drivers", ann, """{"name":"Ann Lee","code":"ANN"}""").also {
            it.status shouldBe HttpStatusCode.Created
        }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        access.get(Thing(Kind.DRIVER, id)) shouldBe Access("ann@example.com")
        ids(call(HttpMethod.Get, "/api/admin/drivers", ann)) shouldBe listOf(id)
        ids(call(HttpMethod.Get, "/api/admin/drivers", olga)) shouldBe emptyList()
        call(HttpMethod.Put, "/api/admin/drivers/$id", olga, """{"name":"Olga","code":"OLG"}""").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Delete, "/api/admin/drivers/$id", olga).status shouldBe HttpStatusCode.Forbidden

        access.set(Thing(Kind.DRIVER, id), Access("ann@example.com", setOf("ed@example.com")))
        call(HttpMethod.Put, "/api/admin/drivers/$id", ed, """{"name":"Ann Lee-Smith","code":"ANN"}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Delete, "/api/admin/drivers/$id", ed).status shouldBe HttpStatusCode.Forbidden
        ids(call(HttpMethod.Get, "/api/admin/drivers", sam)) shouldBe listOf(id)
        client.get("/api/drivers").bodyAsText().contains("\"access\"") shouldBe false

        call(HttpMethod.Delete, "/api/admin/drivers/$id", ann).status shouldBe HttpStatusCode.NoContent
        access.get(Thing(Kind.DRIVER, id)) shouldBe null
        log.list.map { it.formattedMessage }.any { it == "driver removed: Ann Lee-Smith (ANN) by ann@example.com" } shouldBe true
    }
}
