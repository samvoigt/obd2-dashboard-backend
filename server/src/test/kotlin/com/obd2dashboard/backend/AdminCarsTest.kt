package com.obd2dashboard.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Passcodes
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.slf4j.LoggerFactory

/** The admin page's cars API (M6.4). */
class AdminCarsTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val store = InMemoryMessageStore()
    private val yaris = Slug.parse("yaris")
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )
    private val log = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger("admin") as Logger).apply { addAppender(log) }

    @After
    fun detach() {
        logger.detachAppender(log)
    }

    private fun logged(): String = log.list.joinToString("\n") { it.formattedMessage }

    private val hub = InMemoryLiveHub()

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), hub,
                messages = Messages(store), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), admin = config)
        }
    }

    private suspend fun ApplicationTestBuilder.signIn(): String =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    /** A request from our page, signed in unless [cookie] is null. */
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

    private fun json(response: HttpResponse) = runBlocking { Json.parseToJsonElement(response.bodyAsText()).jsonObject }

    @Test
    fun `without a sign-in every cars route is 401, and nothing changes`() = testApplication {
        app()
        registry.addCar(yaris, "Yaris")
        for ((method, path, body) in listOf(
            Triple(HttpMethod.Get, "/api/admin/cars", null),
            Triple(HttpMethod.Post, "/api/admin/cars", """{"slug":"outback","name":"Outback"}"""),
            Triple(HttpMethod.Patch, "/api/admin/cars/yaris", """{"name":"X"}"""),
            Triple(HttpMethod.Post, "/api/admin/cars/yaris/token", "{}"),
            Triple(HttpMethod.Put, "/api/admin/cars/yaris/passcode", """{"passcode":"pit-lane"}"""),
            Triple(HttpMethod.Delete, "/api/admin/cars/yaris", null),
        )) {
            call(method, path, null, body).status shouldBe HttpStatusCode.Unauthorized
            call(method, path, "admin=adm1.forged.1.x", body).status shouldBe HttpStatusCode.Unauthorized
        }
        registry.list().map { it.slug.value } shouldBe listOf("yaris")
        registry.get(yaris)!!.name shouldBe "Yaris"
    }

    @Test
    fun `a change from another origin is refused even when signed in`() = testApplication {
        app()
        val cookie = signIn()
        call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"yaris","name":"Yaris"}""", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"yaris","name":"Yaris"}""", origin = null).status shouldBe HttpStatusCode.Forbidden
        registry.get(yaris).shouldBeNull()
        call(HttpMethod.Get, "/api/admin/cars", cookie, origin = null).status shouldBe HttpStatusCode.OK // reads need no Origin
    }

    @Test
    fun `adding a car shows a generated token exactly once, and never lists it`() = testApplication {
        app()
        val cookie = signIn()
        val added = call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"yaris","name":"Yaris"}""")
        added.status shouldBe HttpStatusCode.Created
        val token = json(added).getValue("token").jsonPrimitive.content
        registry.authenticate(token)?.slug shouldBe yaris
        json(added).getValue("car").jsonObject.getValue("tokenHint").jsonPrimitive.content shouldBe token.takeLast(4)
        val list = call(HttpMethod.Get, "/api/admin/cars", cookie).bodyAsText()
        list shouldNotContain token
        list shouldNotContain registry.get(yaris)!!.tokenHash
        logged() shouldContain "car added: yaris by sam@example.com (generated token)"
        logged() shouldNotContain token
    }

    @Test
    fun `adding a car with a chosen token returns no token, and a bad one leaves no car`() = testApplication {
        app()
        val cookie = signIn()
        json(call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"yaris","name":"Yaris","token":"bears-yaris-15"}"""))["token"]?.jsonPrimitive?.contentOrNull.shouldBeNull()
        registry.authenticate("bears-yaris-15")?.slug shouldBe yaris
        call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"outback","name":"Outback","token":"short"}""").let {
            it.status shouldBe HttpStatusCode.BadRequest
            it.bodyAsText() shouldContain "A token needs at least 8 characters"
        }
        call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"outback","name":"Outback","token":"bears-yaris-15"}""").status shouldBe HttpStatusCode.Conflict
        registry.get(Slug.parse("outback")).shouldBeNull()
        call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"yaris","name":"Again"}""").status shouldBe HttpStatusCode.Conflict
        call(HttpMethod.Post, "/api/admin/cars", cookie, """{"slug":"Bad Slug!","name":"X"}""").status shouldBe HttpStatusCode.BadRequest
        logged() shouldNotContain "bears-yaris-15"
    }

    @Test
    fun `rename, replace the token either way, and set the passcode`() = testApplication {
        app()
        val cookie = signIn()
        val first = registry.addCar(yaris, "Yaris").token
        json(call(HttpMethod.Patch, "/api/admin/cars/yaris", cookie, """{"name":"Yaris GR"}""")).getValue("name").jsonPrimitive.content shouldBe "Yaris GR"

        val rotated = json(call(HttpMethod.Post, "/api/admin/cars/yaris/token", cookie, "{}")).getValue("token").jsonPrimitive.content
        registry.authenticate(first).shouldBeNull()
        registry.authenticate(rotated)?.slug shouldBe yaris

        json(call(HttpMethod.Post, "/api/admin/cars/yaris/token", cookie, """{"token":"bears-yaris-15"}"""))["token"]?.jsonPrimitive?.contentOrNull.shouldBeNull()
        registry.authenticate(rotated).shouldBeNull()
        registry.authenticate("bears-yaris-15")?.slug shouldBe yaris

        json(call(HttpMethod.Put, "/api/admin/cars/yaris/passcode", cookie, """{"passcode":"pit-lane"}""")).getValue("passcodeSet").jsonPrimitive.content shouldBe "true"
        Passcodes.verify("pit-lane".toCharArray(), registry.get(yaris)!!.passcodeHash!!) shouldBe true
        call(HttpMethod.Put, "/api/admin/cars/yaris/passcode", cookie, """{"passcode":"12345"}""").status shouldBe HttpStatusCode.BadRequest

        for (path in listOf("/api/admin/cars/nope", "/api/admin/cars/Bad!")) {
            call(HttpMethod.Patch, path, cookie, """{"name":"X"}""").status shouldBe HttpStatusCode.NotFound
        }
        call(HttpMethod.Post, "/api/admin/cars/nope/token", cookie, "{}").status shouldBe HttpStatusCode.NotFound

        val lines = logged()
        lines shouldContain "car renamed: yaris by sam@example.com"
        lines shouldContain "token replaced: yaris by sam@example.com (generated)"
        lines shouldContain "token replaced: yaris by sam@example.com (chosen)"
        lines shouldContain "passcode set: yaris by sam@example.com"
        for (secret in listOf(first, rotated, "bears-yaris-15", "pit-lane")) lines shouldNotContain secret
    }

    @Test
    fun `the list shows each car's hint, passcode, state and sessions`() = testApplication {
        app()
        val cookie = signIn()
        registry.addCar(yaris, "Yaris")
        registry.setToken(yaris, "bears-yaris-15")
        sessions.create(SessionRecord(SESSION, "yaris", null, null, -1, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
        val car = Json.parseToJsonElement(call(HttpMethod.Get, "/api/admin/cars", cookie).bodyAsText()).jsonArray.single().jsonObject
        car.getValue("tokenHint").jsonPrimitive.content shouldBe "-15"
        car.getValue("passcodeSet").jsonPrimitive.content shouldBe "false"
        car.getValue("state").jsonPrimitive.content shouldBe "offline"
        car.getValue("sessions").jsonPrimitive.content shouldBe "1"
        car.getValue("tokenIssued").jsonPrimitive.content.toLong() shouldBe registry.get(yaris)!!.tokenIssued.toEpochMilli()
        car["clockOffsetMs"]?.jsonPrimitive?.contentOrNull shouldBe null // it hasn't streamed
    }

    @Test
    fun `the list says how far a streaming tablet's clock is off (M11)`() = testApplication {
        app()
        val cookie = signIn()
        registry.addCar(yaris, "Yaris")
        val tablet = runBlocking {
            hub.attach("yaris", object : com.obd2dashboard.backend.live.TabletHandle {
                override fun superseded() {}
                override fun close(code: Short, reason: String) {}
                override fun send(frame: String) {}
            })
        }
        fun frame(text: String) = runBlocking {
            tablet.apply((com.obd2dashboard.backend.live.TabletFrames.parse(text) as com.obd2dashboard.backend.live.TabletFrames.Parsed.Ok).frame)
        }
        frame("""{"t":"session","record":{"type":"session","v":3,"id":"$SESSION","started":"2026-09-26T17:59:00Z","signals":[],"seq":0,"at":0}}""")
        val slow = Duration.ofHours(11).toMillis()
        val wall = System.currentTimeMillis() - slow
        frame("""{"t":"batch","session":"$SESSION","records":[{"type":"sample","signal":"engine.rpm","value":1,"seq":1,"at":1,"wall":$wall}]}""")
        val car = Json.parseToJsonElement(call(HttpMethod.Get, "/api/admin/cars", cookie).bodyAsText()).jsonArray.single().jsonObject
        val offset = car.getValue("clockOffsetMs").jsonPrimitive.content.toLong()
        (offset in slow until slow + 5_000) shouldBe true
    }

    @Test
    fun `removing a car is refused while it has sessions, then takes its messages`() = testApplication {
        app()
        val cookie = signIn()
        registry.addCar(yaris, "Yaris")
        store.create(Message("m_1", "yaris", "PIT NOW", "pit", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), MessageState.Cleared))
        sessions.create(SessionRecord(SESSION, "yaris", null, null, -1, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
        call(HttpMethod.Delete, "/api/admin/cars/yaris", cookie).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText() shouldContain "yaris still has 1 session(s). Delete them first."
        }
        registry.get(yaris).shouldNotBeNull()
        sessions.delete(SESSION)
        json(call(HttpMethod.Delete, "/api/admin/cars/yaris", cookie)).getValue("messages").jsonPrimitive.content shouldBe "1"
        registry.get(yaris).shouldBeNull()
        store.get("m_1").shouldBeNull()
        call(HttpMethod.Delete, "/api/admin/cars/yaris", cookie).status shouldBe HttpStatusCode.NotFound
        logged() shouldContain "car removed: yaris by sam@example.com (1 messages)"
    }

    private companion object {
        const val SESSION = "0b8f3c52-5f7e-4b7e-9c55-1d7b0a3e9f10"
    }
}
