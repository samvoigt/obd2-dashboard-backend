package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.InMemoryAccessStore
import com.obd2dashboard.backend.admin.InMemoryUserStore
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.MergedSession
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.CarStatus
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Hidden (M25): a hidden car, course and event don't exist to anyone signed
 * out, on any public route; anyone signed in sees them all. A visible event
 * with a hidden car leaves it out; one at a hidden course doesn't name it.
 */
class HiddenTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val courses = InMemoryCourseStore()
    private val stores = testEvents()
    private val users = InMemoryUserStore()
    private val access = InMemoryAccessStore()
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { SignIn.Allowed("$it@example.com") },
    )
    private val t0 = Instant.parse("2026-10-04T13:00:00Z")
    private val secretSession = "5ace0000-1111-4111-8111-0000000000aa"
    private val openSession = "5ace0000-1111-4111-8111-0000000000bb"
    /** A merged session of the hidden car's (M33.5). */
    private val secretMerge = "5ace0000-1111-4111-8111-0000000000ad"

    /** Everything a signed-out answer must never hold. */
    private val secrets = listOf("secret-car", "Secret Car", "Secret Driver", "secret-course", "Secret Course", "secret-event", "Secret Event", secretSession, secretMerge)

    private fun cookieOf(email: String) = "${AdminAuth.COOKIE}=${AdminAuth(testCrewKey()).issue(email)}"

    init {
        runBlocking {
            users.add(User("ann@example.com", "sam@example.com", Instant.EPOCH))
            registry.addCar(Slug.parse("open-car"), "Open Car")
            registry.addCar(Slug.parse("secret-car"), "Secret Car")
            val nhms = Json.parseToJsonElement(File("../courses/seed/nhms.geojson").readText()).jsonObject
            courses.save("nhms", 0, "NHMS", nhms, Instant.EPOCH)
            courses.save("secret-course", 0, "Secret Course", nhms, Instant.EPOCH)
            stores.drivers.put(Driver("d-00000001", "Dee", "DEE"))
            val day = Part("p1", PartKind.PRACTICE, "Practice", t0, t0.plusSeconds(3600))
            stores.events.save(Event("open-day", "Open Day", "2026-10-04", "secret-course", "road", listOf("open-car", "secret-car"), listOf(day)), 0, Instant.EPOCH)
            stores.events.save(Event("secret-event", "Secret Event", "2026-10-04", "nhms", "road", listOf("open-car"), listOf(day)), 0, Instant.EPOCH)
            for ((id, car) in listOf(secretSession to "secret-car", openSession to "open-car")) {
                val at = t0.plusSeconds(600)
                sessions.create(SessionRecord(id, car, null, null, 0, emptyList(), false, null, 0, at, at, driver = "d-00000001"))
            }
            stores.merges.save(MergedSession(secretMerge, "secret-car", listOf(secretSession), created = t0, updated = t0), 0, t0)
            for (thing in listOf(Thing(Kind.CAR, "secret-car"), Thing(Kind.COURSE, "secret-course"), Thing(Kind.EVENT, "secret-event"))) {
                access.set(thing, Access("", hidden = true))
            }
        }
    }

    private var routes: List<RoutingNode> = emptyList()

    /** Both cars streaming, the hidden one with a standing naming its driver (M36.1): the cars list must not carry it. */
    private val hub: LiveHub = InMemoryLiveHub().let { real ->
        object : LiveHub by real {
            override suspend fun status(car: String): CarStatus = CarStatus(connected = true, inSession = true, lastDataAt = Instant.now())
        }
    }
    private val timingPage = TimingPage(hub).also { page ->
        runBlocking { page.standing("secret-car", Standing("s1", CourseAt("nhms", 0, "road"), null, null, DriverNow("Secret Driver", "SEC", null), null)) }
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), hub,
                messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = courses, events = stores,
                users = users, access = access, timingPage = timingPage)
            routes = plugin(RoutingRoot).getAllRoutes()
        }
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, cookie: String? = null, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    /**
     * Every public read, each with the ids it's asked about. A route the server
     * has that isn't here fails [every public read is checked], so a new one
     * can't be added without saying what it shows.
     */
    private val reads = mapOf(
        "/api/cars" to listOf("/api/cars"),
        "/api/cars/{slug}/live" to emptyList(), // a stream: its 404 is checked on its own below
        "/api/cars/{slug}/messages" to listOf("/api/cars/secret-car/messages", "/api/cars/open-car/messages"),
        "/api/cars/{slug}/sessions" to listOf("/api/cars/secret-car/sessions", "/api/cars/open-car/sessions"),
        "/api/cars/{slug}/crew" to listOf("/api/cars/secret-car/crew"),
        "/api/sessions/{id}" to listOf("/api/sessions/$secretSession", "/api/sessions/$openSession", "/api/sessions/$secretMerge"),
        "/api/sessions/{id}/live-laps" to listOf("/api/sessions/$secretSession/live-laps"),
        "/api/sessions/{id}/messages" to listOf("/api/sessions/$secretSession/messages", "/api/sessions/$openSession/messages", "/api/sessions/$secretMerge/messages"),
        "/api/sessions/{id}/laps" to listOf("/api/sessions/$secretSession/laps", "/api/sessions/$secretMerge/laps"),
        "/api/sessions/{id}/series" to listOf("/api/sessions/$secretSession/series"),
        "/api/courses" to listOf("/api/courses"),
        "/api/courses/{id}" to listOf("/api/courses/secret-course"),
        "/api/courses/{id}/versions" to listOf("/api/courses/secret-course/versions"),
        "/api/events" to listOf("/api/events"),
        "/api/events/{id}" to listOf("/api/events/secret-event", "/api/events/open-day"),
        "/api/drivers" to listOf("/api/drivers"),
        "/api/drivers/{id}" to listOf("/api/drivers/d-00000001"),
    )

    /** `/api/cars/{slug}/(method:GET)` → `/api/cars/{slug}`: a route's path, for a GET. */
    private fun RoutingNode.getPath(): String? {
        val s = toString()
        if (!s.contains("(method:GET)")) return null
        return s.replace(Regex("/\\(method:GET\\)|/\\([^)]*\\)"), "").ifEmpty { "/" }
    }

    @Test
    fun `every public read is checked`() = testApplication {
        app()
        call(HttpMethod.Get, "/health")
        val public = routes.mapNotNull { it.getPath() }.filter { it.startsWith("/api/") && !it.startsWith("/api/admin/") }.toSet()
        (public - reads.keys).shouldBeEmpty()
    }

    @Test
    fun `signed out, nothing hidden is anywhere`() = testApplication {
        app()
        val never = mapOf("secret-car" to "never-car", "secret-course" to "never-course", "secret-event" to "never-event",
            secretSession to "5ace0000-1111-4111-8111-0000000000cc", secretMerge to "5ace0000-1111-4111-8111-0000000000ce")
        for (path in reads.values.flatten() + "/api/cars/secret-car/live") {
            val response = call(HttpMethod.Get, path)
            val about = never.keys.firstOrNull { path.contains(it) }
            if (about == null) {
                val text = response.bodyAsText()
                for (secret in secrets) withClue(path, secret) { text shouldNotContain secret }
                withClue(path, "no mark") { text shouldNotContain "\"hidden\"" }
                continue
            }
            // One about a hidden thing is answered word for word as one that never was.
            val unknown = call(HttpMethod.Get, path.replace(about, never.getValue(about)))
            withClue(path, "status") { response.status shouldBe HttpStatusCode.NotFound }
            withClue(path, "as if it never was") { response.bodyAsText() shouldBe unknown.bodyAsText().replace(never.getValue(about), about) }
        }
        // The visible event is there, without the hidden car, and without naming its hidden course.
        val open = call(HttpMethod.Get, "/api/events/open-day").bodyAsText()
        open shouldContain "Open Car"
        // Its crew can't log in either: the car doesn't exist to them.
        call(HttpMethod.Post, "/api/cars/secret-car/login", body = """{"passcode":"anything"}""").status shouldBe HttpStatusCode.NotFound
        // Any other request about one, routed or not, any method, is answered as for one that never was (found in production:
        // a path no route serves was a JSON 404 for a hidden car, and routing's empty one for a car that never existed).
        for ((method, path) in listOf(
            HttpMethod.Get to "/api/cars/secret-car", HttpMethod.Get to "/api/cars/secret-car/nope", HttpMethod.Get to "/api/cars/secret-car/login",
            HttpMethod.Post to "/api/cars/secret-car/login", HttpMethod.Post to "/api/cars/secret-car/messages", HttpMethod.Put to "/api/cars/secret-car/sessions/x/driver-changes", HttpMethod.Put to "/api/cars/secret-car/events/open-day/race",
            HttpMethod.Get to "/api/courses/secret-course/nope", HttpMethod.Delete to "/api/courses/secret-course", HttpMethod.Get to "/api/events/secret-event/nope",
        )) {
            val about = never.keys.first { path.contains(it) }
            val body = if (method == HttpMethod.Get || method == HttpMethod.Delete) null else "{}"
            val response = call(method, path, body = body)
            val unknown = call(method, path.replace(about, never.getValue(about)), body = body)
            val text = response.bodyAsText()
            val expected = unknown.bodyAsText().replace(never.getValue(about), about)
            withClue("${method.value} $path", "status") { response.status shouldBe unknown.status }
            withClue("${method.value} $path", "as if it never was") { text shouldBe expected }
        }
    }

    @Test
    fun `results held for someone signed in are never sent to someone signed out (M35_1)`() = testApplication {
        app()
        val ann = cookieOf("ann@example.com")
        // Signed in first: the event's results with the hidden car, now held.
        call(HttpMethod.Get, "/api/events/open-day", ann).bodyAsText() shouldContain "Secret Car"
        // Signed out at once, within the hold: the hidden car isn't there, nor its session.
        val out = call(HttpMethod.Get, "/api/events/open-day").bodyAsText()
        for (secret in secrets) withClue("signed out after signed in", secret) { out shouldNotContain secret }
        // And the other way round: signed in after signed out still sees everything.
        call(HttpMethod.Get, "/api/events/open-day", ann).bodyAsText() shouldContain "Secret Car"
    }

    @Test
    fun `signed in, everything is there`() = testApplication {
        app()
        val ann = cookieOf("ann@example.com")
        call(HttpMethod.Get, "/api/cars", ann).bodyAsText() shouldContain """"name":"Secret Car","state":"live","hidden":true,"standing":{"driver":{"name":"Secret Driver","code":"SEC"}}"""
        call(HttpMethod.Get, "/api/courses/secret-course", ann).bodyAsText() shouldContain """"hidden":true"""
        call(HttpMethod.Get, "/api/events/secret-event", ann).bodyAsText() shouldContain """"hidden":true"""
        call(HttpMethod.Get, "/api/events/open-day", ann).bodyAsText() shouldNotContain """"hidden":true"""
        call(HttpMethod.Get, "/api/courses", ann).bodyAsText() shouldContain "Secret Course"
        call(HttpMethod.Get, "/api/events", ann).bodyAsText() shouldContain "Secret Event"
        val open = call(HttpMethod.Get, "/api/events/open-day", ann).bodyAsText()
        open shouldContain "Secret Car"
        open shouldContain "Secret Course"
        for (path in reads.values.flatten()) withClue(path, "status") { call(HttpMethod.Get, path, ann).status shouldNotBe HttpStatusCode.NotFound }
    }

    @Test
    fun `a browser sends its sign-in to every public read, or signed in would see nothing hidden`() = testApplication {
        app()
        val login = call(HttpMethod.Post, "/api/admin/login", body = """{"credential":"ann"}""")
        login.status shouldBe HttpStatusCode.OK
        val set = login.headers.getAll(HttpHeaders.SetCookie)!!.first { !it.contains("Max-Age=0") }
        val path = Regex("Path=([^;]+)").find(set)!!.groupValues[1]
        for (read in reads.values.flatten()) withClue(read, "under $path") { read.startsWith("$path/") shouldBe true }
    }

    @Test
    fun `public answers aren't kept by anything shared`() = testApplication {
        app()
        val cars = call(HttpMethod.Get, "/api/cars")
        cars.headers[HttpHeaders.CacheControl] shouldBe "private, no-cache"
        cars.headers[HttpHeaders.Vary] shouldBe HttpHeaders.Cookie
    }

    @Test
    fun `whoever chooses its editors hides or shows it, and only that`() = testApplication {
        app()
        val sam = cookieOf("sam@example.com")
        val ann = cookieOf("ann@example.com")
        call(HttpMethod.Put, "/api/admin/access/car/open-car/hidden", ann, """{"hidden":true}""").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Put, "/api/admin/access/driver/d-00000001/hidden", sam, """{"hidden":true}""").status shouldBe HttpStatusCode.BadRequest
        call(HttpMethod.Put, "/api/admin/access/car/open-car/hidden", sam, """{"hidden":true}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Get, "/api/cars/open-car/sessions").status shouldBe HttpStatusCode.NotFound // at once, not after the cache
        call(HttpMethod.Put, "/api/admin/access/car/open-car/hidden", sam, """{"hidden":false}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Get, "/api/cars/open-car/sessions").status shouldBe HttpStatusCode.OK
        // Choosing editors keeps it hidden.
        call(HttpMethod.Put, "/api/admin/access/car/secret-car", sam, """{"editors":["ann@example.com"]}""").status shouldBe HttpStatusCode.OK
        access.get(Thing(Kind.CAR, "secret-car"))?.hidden shouldBe true
    }

    private inline fun withClue(path: String, what: String, block: () -> Unit) =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$path ($what): ${e.message}", e)
        }
}
