package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The admin sign-in (M6.3): the cookie, the routes, and production's configuration. */
class AdminAuthTest {
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val allowed = Allowlist.parse("sam@example.com")
    private val auth = AdminAuth(testCrewKey(), clock)

    // The cookie.

    @Test
    fun `a cookie names its email for 30 days, while the email is still allowed`() {
        val cookie = auth.issue("sam@example.com")
        auth.verify(cookie, allowed) shouldBe "sam@example.com"
        AdminAuth(testCrewKey(), Clock.offset(clock, Duration.ofDays(30).minusSeconds(1))).verify(cookie, allowed) shouldBe "sam@example.com"
        AdminAuth(testCrewKey(), Clock.offset(clock, Duration.ofDays(30))).verify(cookie, allowed) shouldBe null
        auth.verify(cookie, Allowlist.parse("someone@example.com")) shouldBe null // removed from the list: out at once
    }

    @Test
    fun `a tampered, foreign or malformed cookie is refused`() {
        val cookie = auth.issue("sam@example.com")
        val parts = cookie.split('.')
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val other = b64.encodeToString("crew@example.com".toByteArray())
        for (bad in listOf(
            null, "", "adm1", cookie + "x", cookie.dropLast(1),
            "${parts[0]}.$other.${parts[2]}.${parts[3]}", // another email, same signature
            "${parts[0]}.${parts[1]}.${parts[2].toLong() + 86_400}.${parts[3]}", // extended
            "v1.${parts[1]}.${parts[2]}.${parts[3]}", // another tag
            "${parts[0]}.!!!.${parts[2]}.${parts[3]}",
        )) {
            auth.verify(bad, Allowlist.parse("sam@example.com,crew@example.com")) shouldBe null
        }
        AdminAuth(ByteArray(32) { 7 }, clock).verify(cookie, allowed) shouldBe null // another key
    }

    @Test
    fun `a crew cookie, signed with the same key, is not an admin cookie`() = runBlocking<Unit> {
        // ("admin" can't be a car's slug at all: the site reserves it.)
        val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
        registry.addCar(Slug.parse("yaris"), "Yaris")
        registry.setPasscode(Slug.parse("yaris"), "pit-lane".toCharArray())
        val crew = CrewAuth(testCrewKey(), clock).issue(registry.get(Slug.parse("yaris"))!!)
        auth.verify(crew, Allowlist.parse("yaris")) shouldBe null
    }

    @Test
    fun `an email with dots and plus signs survives the cookie`() {
        auth.verify(auth.issue("sam.voigt+admin@gmail.com"), Allowlist.parse("sam.voigt+admin@gmail.com")) shouldBe "sam.voigt+admin@gmail.com"
    }

    @Test
    fun `same origin means the Origin header's host and port are the Host`() {
        isSameOrigin("https://badnewsbears.live", "badnewsbears.live") shouldBe true
        isSameOrigin("http://badnewsbears.live", "badnewsbears.live") shouldBe true // TLS ends in front
        isSameOrigin("http://localhost:5173", "localhost:5173") shouldBe true
        isSameOrigin("https://BadNewsBears.live", "badnewsbears.live") shouldBe true
        isSameOrigin("https://evil.example", "badnewsbears.live") shouldBe false
        isSameOrigin("https://badnewsbears.live.evil.example", "badnewsbears.live") shouldBe false
        isSameOrigin("http://localhost:5174", "localhost:5173") shouldBe false
        isSameOrigin("null", "badnewsbears.live") shouldBe false
        isSameOrigin(null, "badnewsbears.live") shouldBe false
        isSameOrigin("https://badnewsbears.live", null) shouldBe false
    }

    // The routes, with a verifier that accepts "good" and "stranger".

    private val fake = AdminConfig(
        googleClientId = "client-id",
        allowlist = allowed,
        identity = IdentityVerifier {
            when (it) {
                "good" -> SignIn.Allowed("sam@example.com")
                "stranger" -> SignIn.Refused(Refusal.NotAllowed)
                else -> SignIn.Refused(Refusal.BadSignature)
            }
        },
    )

    private fun ApplicationTestBuilder.app(admin: AdminConfig = fake) {
        application {
            module(CarRegistry(InMemoryCarStore()), testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), admin = admin)
        }
    }

    private suspend fun ApplicationTestBuilder.signIn(credential: String, origin: String? = "http://localhost") =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost") // browsers always send it; the test client doesn't
            origin?.let { header(HttpHeaders.Origin, it) }
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"$credential"}""")
        }

    @Test
    fun `signing in sets a strict cookie for the admin API, and me names who`() = testApplication {
        app()
        val response = signIn("good")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "\"email\":\"sam@example.com\""
        val setCookie = response.headers[HttpHeaders.SetCookie]!!
        setCookie shouldContain "admin="
        setCookie shouldContain "Path=/api/admin"
        setCookie shouldContain "HttpOnly"
        setCookie shouldContain "Secure"
        setCookie shouldContain "SameSite=Strict"
        setCookie shouldContain "Max-Age=2592000"
        val cookie = setCookie.substringBefore(';')
        client.get("/api/admin/me") { header(HttpHeaders.Cookie, cookie) }.bodyAsText() shouldContain "sam@example.com"
        client.get("/api/admin/me").status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `a refused sign-in is 401, and says so plainly for another account`() = testApplication {
        app()
        signIn("stranger").let {
            it.status shouldBe HttpStatusCode.Unauthorized
            it.bodyAsText() shouldContain "That Google account can't use this page."
            it.headers[HttpHeaders.SetCookie] shouldBe null
        }
        signIn("forged").let {
            it.status shouldBe HttpStatusCode.Unauthorized
            it.bodyAsText() shouldContain "could not be checked"
        }
    }

    @Test
    fun `sign-in and sign-out must come from our own page`() = testApplication {
        app()
        client.post("/api/admin/login") { // no Host: nothing to compare with
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.status shouldBe HttpStatusCode.Forbidden
        signIn("good", origin = "https://evil.example").status shouldBe HttpStatusCode.Forbidden
        signIn("good", origin = null).status shouldBe HttpStatusCode.Forbidden
        client.delete("/api/admin/login") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Origin, "https://evil.example") }.status shouldBe HttpStatusCode.Forbidden
        client.delete("/api/admin/login") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Origin, "http://localhost") }.let {
            it.status shouldBe HttpStatusCode.NoContent
            it.headers[HttpHeaders.SetCookie]!! shouldContain "Max-Age=0"
        }
    }

    @Test
    fun `config tells the page what to show`() = testApplication {
        app()
        client.get("/api/admin/config").bodyAsText() shouldBe """{"enabled":true,"googleClientId":"client-id","dev":false}"""
    }

    @Test
    fun `off by default, the page says so and sign-in is 503`() = testApplication {
        app(AdminConfig.DISABLED)
        client.get("/api/admin/config").bodyAsText() shouldBe """{"enabled":false,"googleClientId":null,"dev":false}"""
        signIn("good").status shouldBe HttpStatusCode.ServiceUnavailable
    }

    // Production's configuration.

    @Test
    fun `production without a client ID is off, and never a dev sign-in`() = testApplication {
        AdminConfig.fromEnvironment({ null }).enabled shouldBe false
        AdminConfig.fromEnvironment({ if (it == "GOOGLE_CLIENT_ID") "  " else null }).enabled shouldBe false
        val prod = AdminConfig.fromEnvironment({ mapOf("GOOGLE_CLIENT_ID" to "client-id", "ADMIN_EMAILS" to "dev@localhost")[it] })
        prod.dev shouldBe false
        prod.identity.shouldBeInstanceOf<GoogleIdentity>()
        app(prod)
        signIn("dev").let {
            it.status shouldBe HttpStatusCode.Unauthorized
            it.headers[HttpHeaders.SetCookie] shouldBe null
        }
        client.get("/api/admin/config").bodyAsText() shouldNotContain "\"dev\":true"
    }
}
