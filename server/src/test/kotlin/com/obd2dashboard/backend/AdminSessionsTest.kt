package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.TabletFrames
import com.obd2dashboard.backend.live.TabletHandle
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The admin page's sessions (M6.7): which is live, and when one may be deleted. */
class AdminSessionsTest {
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val hub = InMemoryLiveHub(clock)
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore(), clock), hub, clock = clock,
                messages = testMessages(), crewKey = testCrewKey(), admin = config)
        }
    }

    private suspend fun ApplicationTestBuilder.signIn(): String =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, cookie: String?): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }

    private suspend fun record(id: String, complete: Boolean, updated: Instant, created: Instant = updated) =
        sessions.create(SessionRecord(id, "yaris", null, null, 41, emptyList(), complete, null, 0, created, updated))

    /** A tablet streaming [id] live on yaris. */
    private suspend fun streaming(id: String) {
        val attachment = hub.attach("yaris", object : TabletHandle {
            override fun superseded() {}
            override fun close(code: Short, reason: String) {}
            override fun send(frame: String) {}
        })
        val frame = """{"t":"session","record":{"type":"session","v":3,"id":"$id","device":"dev","app":"1.0","started":"2026-09-26T11:00:00Z","signals":[],"seq":0,"at":0}}"""
        attachment.apply((TabletFrames.parse(frame) as TabletFrames.Parsed.Ok).frame)
    }

    @Test
    fun `sessions list newest first, with the live one marked, and never a VIN`() = testApplication {
        app()
        val cookie = signIn()
        registry.addCar(Slug.parse("yaris"), "Yaris")
        record(LIVE, complete = false, updated = now, created = now.minusSeconds(60))
        record(UPLOADING, complete = false, updated = now.minusSeconds(60), created = now.minusSeconds(3600))
        record(QUIET, complete = false, updated = now.minusSeconds(600), created = now.minusSeconds(7200))
        record(DONE, complete = true, updated = now.minusSeconds(9000), created = now.minusSeconds(9000))
        streaming(LIVE)

        val body = call(HttpMethod.Get, "/api/admin/cars/yaris/sessions", cookie).bodyAsText()
        val list = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        list.map { it.getValue("id").jsonPrimitive.content } shouldBe listOf(LIVE, UPLOADING, QUIET, DONE)
        list.map { it.getValue("state").jsonPrimitive.content } shouldBe listOf("live", "uploading", "incomplete", "complete")
        list.first().getValue("lines").jsonPrimitive.content shouldBe "42"
        body shouldNotContain "vin"

        val car = Json.parseToJsonElement(call(HttpMethod.Get, "/api/admin/cars", cookie).bodyAsText()).jsonArray.single().jsonObject
        car.getValue("liveSession").jsonPrimitive.content shouldBe LIVE

        call(HttpMethod.Get, "/api/admin/cars/nope/sessions", cookie).status shouldBe HttpStatusCode.NotFound
        call(HttpMethod.Get, "/api/admin/cars/yaris/sessions", null).status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `a session can't be deleted while live or uploading, and can once quiet`() = testApplication {
        app()
        val cookie = signIn()
        registry.addCar(Slug.parse("yaris"), "Yaris")
        record(LIVE, complete = false, updated = now) // live, and uploading as it goes: live is the reason given
        record(UPLOADING, complete = false, updated = now.minusSeconds(60))
        record(QUIET, complete = false, updated = now.minusSeconds(600))
        streaming(LIVE)

        call(HttpMethod.Delete, "/api/admin/sessions/$LIVE", cookie).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText() shouldContain "That session is live"
        }
        call(HttpMethod.Delete, "/api/admin/sessions/$UPLOADING", cookie).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText() shouldContain "still uploading"
        }
        sessions.get(LIVE).shouldNotBeNull()
        sessions.get(UPLOADING).shouldNotBeNull()

        call(HttpMethod.Delete, "/api/admin/sessions/$QUIET", null).status shouldBe HttpStatusCode.Unauthorized
        call(HttpMethod.Delete, "/api/admin/sessions/${QUIET.uppercase()}", cookie).status shouldBe HttpStatusCode.NoContent // ids are lower-case
        sessions.get(QUIET).shouldBeNull()
        call(HttpMethod.Delete, "/api/admin/sessions/$QUIET", cookie).status shouldBe HttpStatusCode.NotFound
    }

    private companion object {
        const val LIVE = "11111111-1111-4111-8111-111111111111"
        const val UPLOADING = "22222222-2222-4222-8222-222222222222"
        const val QUIET = "3a3b3c3d-3333-4333-8333-33333333abcd"
        const val DONE = "44444444-4444-4444-8444-444444444444"
    }
}
