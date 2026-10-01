package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import org.junit.Test

/**
 * Signed in, every page shows its edits (M21), but **the public API says the
 * same to everyone**: admin-only facts come only from `/api/admin/…`, so a
 * public answer never changes with the admin's cookie.
 */
class PublicWithSignInTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )

    @Test
    fun `no public answer changes with an admin cookie, and none carries a secret`() = testApplication {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), InMemoryLiveHub(),
                messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), admin = config)
        }
        val yaris = Slug.parse("yaris")
        registry.addCar(yaris, "Yaris")
        registry.setToken(yaris, "bears-yaris-15")
        registry.setPasscode(yaris, "pit-wall-77".toCharArray())
        sessions.create(SessionRecord(SESSION, "yaris", null, null, -1, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))

        val cookie = client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        client.get("/api/admin/me") { header(HttpHeaders.Cookie, cookie) }.status shouldBe HttpStatusCode.OK // a real sign-in

        for (path in listOf("/api/cars", "/api/cars/yaris/sessions", "/api/sessions/$SESSION", "/api/courses", "/api/events", "/api/drivers")) {
            val out = client.get(path)
            val signedIn = client.get(path) { header(HttpHeaders.Cookie, cookie) }
            signedIn.status shouldBe out.status
            val body = signedIn.bodyAsText()
            body shouldBe out.bodyAsText()
            body shouldNotContain "bears-yaris-15"
            body shouldNotContain "pit-wall-77"
            body shouldNotContain "tokenHint"
            body shouldNotContain "passcode"
        }
    }

    private companion object {
        const val SESSION = "0b8f3c52-5f7e-4b7e-9c55-1d7b0a3e9f10"
    }
}
