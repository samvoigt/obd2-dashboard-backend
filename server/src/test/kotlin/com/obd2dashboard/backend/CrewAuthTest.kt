package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Crew login (M5.4): the cookie, what it refuses, and the limit on guessing. */
class CrewAuthTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val yaris = Slug.parse("yaris")
    private val outback = Slug.parse("outback")

    init {
        runBlocking {
            registry.addCar(yaris, "Yaris")
            registry.addCar(outback, "Outback")
            registry.setPasscode(yaris, "pit-lane".toCharArray())
            registry.setPasscode(outback, "outback-crew".toCharArray())
        }
    }

    private fun ApplicationTestBuilder.app() {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
    }

    private suspend fun ApplicationTestBuilder.login(car: String, passcode: String): HttpResponse =
        client.post("/api/cars/$car/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"passcode":"$passcode"}""")
        }

    private fun HttpResponse.cookie(): String = headers.getAll(HttpHeaders.SetCookie)!!.single()

    /** `name=value` from a Set-Cookie header. */
    private fun pair(setCookie: String): String = setCookie.substringBefore(';')

    private suspend fun ApplicationTestBuilder.isCrew(car: String, cookie: String?): Boolean =
        client.get("/api/cars/$car/crew") { cookie?.let { header(HttpHeaders.Cookie, it) } }.bodyAsText() == """{"crew":true}"""

    @Test
    fun `the right passcode gives a 30-day, HttpOnly, Secure, Strict cookie for this car's paths only`() = testApplication {
        app()
        val response = login("yaris", "pit-lane")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe """{"crew":true}"""
        val c = response.cookie()
        c shouldContain "crew_yaris="
        c shouldContain "Max-Age=2592000"
        c shouldContain "Path=/api/cars/yaris"
        c shouldContain "Secure"
        c shouldContain "HttpOnly"
        c shouldContain "SameSite=Strict"
        isCrew("yaris", pair(c)) shouldBe true
        isCrew("yaris", null) shouldBe false
    }

    @Test
    fun `a wrong passcode is 401, and a car with none is 409`() = testApplication {
        app()
        login("yaris", "pit-lanf").let {
            it.status shouldBe HttpStatusCode.Unauthorized
            it.bodyAsText() shouldContain "\"error\":\"auth\""
            it.headers[HttpHeaders.SetCookie] shouldBe null
        }
        registry.addCar(Slug.parse("nopass"), "No passcode")
        login("nopass", "anything").let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText() shouldContain "no_passcode"
        }
        login("ghost", "x").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `another car's cookie, and a tampered one, are refused`() = testApplication {
        app()
        val outbackValue = pair(login("outback", "outback-crew").cookie()).substringAfter('=')
        isCrew("yaris", "crew_yaris=$outbackValue") shouldBe false
        val yarisValue = pair(login("yaris", "pit-lane").cookie()).substringAfter('=')
        val tampered = yarisValue.replaceRange(8, 9, if (yarisValue[8] == 'a') "b" else "a")
        isCrew("yaris", "crew_yaris=$tampered") shouldBe false
        isCrew("yaris", "crew_yaris=$yarisValue") shouldBe true
    }

    @Test
    fun `changing the passcode logs everyone out`() = testApplication {
        app()
        val old = pair(login("yaris", "pit-lane").cookie())
        registry.setPasscode(yaris, "new-code".toCharArray())
        isCrew("yaris", old) shouldBe false
        isCrew("yaris", pair(login("yaris", "new-code").cookie())) shouldBe true
    }

    @Test
    fun `a cookie expires after 30 days`() {
        var now = Instant.parse("2026-09-26T12:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
        }
        val auth = CrewAuth(testCrewKey(), clock)
        val car = runBlocking { registry.get(yaris)!! }
        val cookie = auth.issue(car)
        now = now.plus(Duration.ofDays(30)).minusSeconds(1)
        auth.verify(cookie, car) shouldBe true
        now = now.plusSeconds(1)
        auth.verify(cookie, car) shouldBe false
    }

    @Test
    fun `a cookie names its car, even if two cars' stored hashes were identical`() {
        val auth = CrewAuth(testCrewKey())
        val y = runBlocking { registry.get(yaris)!! }
        // Salting makes this unreachable in practice; the slug check must hold without relying on it.
        val o = runBlocking { registry.get(outback)!! }.copy(passcodeHash = y.passcodeHash)
        auth.verify(auth.issue(o), y) shouldBe false
        auth.verify(auth.issue(y), y) shouldBe true
    }

    @Test
    fun `a cookie from another key is refused`() {
        val car = runBlocking { registry.get(yaris)!! }
        val other = CrewAuth(ByteArray(32) { 7 }).issue(car)
        CrewAuth(testCrewKey()).verify(other, car) shouldBe false
    }

    @Test
    fun `ten wrong passcodes in ten minutes, and the eleventh attempt is 429 with Retry-After`() = testApplication {
        app()
        repeat(10) { login("yaris", "wrong-$it").status shouldBe HttpStatusCode.Unauthorized }
        val limited = login("yaris", "pit-lane") // even the right one waits
        limited.status shouldBe HttpStatusCode.TooManyRequests
        (limited.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..600) shouldBe true
        login("outback", "outback-crew").status shouldBe HttpStatusCode.OK // per car
    }

    @Test
    fun `the limiter lets attempts through again once the window passes`() {
        var now = Instant.parse("2026-09-26T12:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?) = this
        }
        val limiter = LoginLimiter(clock)
        repeat(10) { limiter.failed("yaris") }
        limiter.waitFor("yaris") shouldBe Duration.ofMinutes(10)
        now = now.plus(Duration.ofMinutes(10))
        limiter.waitFor("yaris") shouldBe null
    }

    @Test
    fun `a form-encoded login is refused`() = testApplication {
        app()
        client.post("/api/cars/yaris/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("passcode=pit-lane")
        }.status shouldBe HttpStatusCode.UnsupportedMediaType
    }

    @Test
    fun `logging out expires the cookie`() = testApplication {
        app()
        val out = client.delete("/api/cars/yaris/login")
        out.bodyAsText() shouldBe """{"crew":false}"""
        out.cookie() shouldContain "Max-Age=0"
    }
}
