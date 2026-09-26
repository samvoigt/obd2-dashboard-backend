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
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages()) }
        for (path in listOf("/", "/cars/yaris", "/cars/yaris/")) {
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.ContentType]!! shouldStartWith "text/html"
            response.bodyAsText() shouldContain "SITE-STUB"
            response.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
        }
    }

    @Test
    fun `assets are served and cached for a year`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages()) }
        val response = client.get("/assets/app-test.js")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "stub"
        response.headers[HttpHeaders.CacheControl]!! shouldContain "max-age=31536000"
    }

    @Test
    fun `the site never shadows the API or the tablet's paths`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages()) }
        client.get("/api/cars").let {
            it.status shouldBe HttpStatusCode.OK
            it.headers[HttpHeaders.ContentType]!! shouldStartWith "application/json"
        }
        for (path in listOf("/api/nope", "/api/cars/x/y", "/v1/nope", "/assets/missing.js", "/elsewhere")) {
            val response = client.get(path)
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldNotContain "SITE-STUB"
        }
        client.get("/v1/whoami").status shouldBe HttpStatusCode.Unauthorized
    }
}
