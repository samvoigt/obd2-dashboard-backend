package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test

/** The website's routes, against the stub page in test resources (no npm needed). */
class WebRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore())

    @Test
    fun `the landing page and a car's page are the site`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        for (path in listOf("/", "/cars/yaris", "/cars/yaris/", "/cars/yaris/sessions", "/cars/yaris/sessions/", "/cars/yaris/sessions/7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f", "/courses", "/courses/nhms",
            // M21: the Cars page, a car's management, and the editors where courses and events are shown.
            "/cars", "/cars/", "/cars/yaris/manage", "/courses/new", "/courses/nhms/edit", "/events", "/events/new", "/events/box-day/edit", "/drivers",
            "/users", "/users/")) {
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType]!! shouldStartWith "text/html"
            response.bodyAsText() shouldContain "SITE-STUB"
            response.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
        }
    }

    @Test
    fun `no page can be framed by anyone, since any can carry the admin's edits (M21)`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        for (path in listOf("/", "/cars", "/cars/yaris", "/cars/yaris/manage", "/courses/nhms/edit", "/events/new", "/drivers")) {
            val response = client.get(path)
            response.headers["X-Frame-Options"] shouldBe "DENY"
            response.headers["Content-Security-Policy"] shouldBe "frame-ancestors 'none'"
        }
    }

    @Test
    fun `the admin page is gone, and the admin API is never stored (M21)`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        for (path in listOf("/admin", "/admin/", "/admin/courses", "/admin/courses/nhms", "/admin/events", "/admin/events/x", "/admin/drivers")) {
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldNotContain "SITE-STUB"
        }
        for (path in listOf("/api/admin/me", "/api/admin/config", "/api/admin/cars", "/api/admin/nope")) {
            client.get(path).headers[HttpHeaders.CacheControl] shouldBe "no-store"
        }
        client.get("/api/cars").headers[HttpHeaders.CacheControl] shouldBe null // the public API is unchanged
    }

    @Test
    fun `assets are served and cached for a year`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        val response = client.get("/assets/app-test.js")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "stub"
        response.headers[HttpHeaders.CacheControl]!! shouldContain "max-age=31536000"
    }

    @Test
    fun `the home-screen icon and the favicon are served at the root, cached for a day (M11)`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        for ((path, body, type) in listOf(
            Triple("/apple-touch-icon.png", "stub-png", "image/png"),
            Triple("/apple-touch-icon-precomposed.png", "stub-png", "image/png"),
            Triple("/favicon.ico", "stub-ico", "image/x-icon"),
        )) {
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe body
            response.headers[HttpHeaders.ContentType]!! shouldContain type
            response.headers[HttpHeaders.CacheControl]!! shouldContain "max-age=86400"
        }
        client.get("/apple-touch-icon-120x120.png").status shouldBe HttpStatusCode.NotFound // named, never a fallback
    }

    @Test
    fun `the site never shadows the API or the tablet's paths`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        client.get("/api/cars").let {
            it.status shouldBe HttpStatusCode.OK
            it.headers[HttpHeaders.ContentType]!! shouldStartWith "application/json"
        }
        for (path in listOf("/api/nope", "/api/cars/x/y", "/api/admin/nope", "/v1/nope", "/assets/missing.js", "/elsewhere", "/admin/x", "/cars/yaris/sessions/a/b", "/api/sessions/x/y/z")) {
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldNotContain "SITE-STUB"
        }
        client.get("/v1/whoami").status shouldBe HttpStatusCode.Unauthorized
    }
}
