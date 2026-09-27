package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import com.obd2dashboard.backend.live.InMemoryLiveHub
import org.junit.Test

class CarAuthTest {
    private val store = InMemoryCarStore()
    private val registry = CarRegistry(store)
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }
    private val outback = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }

    private fun ApplicationTestBuilder.app() {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), crewKey = testCrewKey()) }
    }

    private fun ApplicationTestBuilder.json() = createClient { install(ContentNegotiation) { json() } }

    @Test
    fun `a valid token is its car`() = testApplication {
        app()
        val response = json().get("/v1/whoami") { bearerAuth(yaris.token) }
        response.status shouldBe HttpStatusCode.OK
        response.body<PublicCar>() shouldBe PublicCar("yaris", "Yaris")
    }

    @Test
    fun `two cars' tokens are their own cars`() = testApplication {
        app()
        json().get("/v1/whoami") { bearerAuth(outback.token) }.body<PublicCar>().slug shouldBe "outback"
        json().get("/v1/whoami") { bearerAuth(yaris.token) }.body<PublicCar>().slug shouldBe "yaris"
    }

    @Test
    fun `an unknown token is a 401 with the contract's body`() = testApplication {
        app()
        val response = json().get("/v1/whoami") { bearerAuth(Tokens.generate(SecureRandom())) }
        response.status shouldBe HttpStatusCode.Unauthorized
        val error = response.body<ApiError>()
        error.error shouldBe "auth"
        error.skipChunk shouldBe false
        error.message shouldContain "not recognised"
        response.headers[HttpHeaders.WWWAuthenticate] shouldBe "Bearer realm=\"car\""
    }

    @Test
    fun `no token is a 401 with the contract's body`() = testApplication {
        app()
        val response = json().get("/v1/whoami")
        response.status shouldBe HttpStatusCode.Unauthorized
        response.body<ApiError>().let {
            it.error shouldBe "auth"
            it.message shouldContain "No token"
        }
    }

    @Test
    fun `a rotated token fails on the very next request`() = testApplication {
        app()
        json().get("/v1/whoami") { bearerAuth(yaris.token) }.status shouldBe HttpStatusCode.OK
        val rotated = registry.rotateToken(Slug.parse("yaris"))
        json().get("/v1/whoami") { bearerAuth(yaris.token) }.status shouldBe HttpStatusCode.Unauthorized
        json().get("/v1/whoami") { bearerAuth(rotated.token) }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `a removed car's token fails`() = testApplication {
        app()
        registry.removeCar(Slug.parse("outback"))
        json().get("/v1/whoami") { bearerAuth(outback.token) }.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `malformed Authorization headers are a 401, never a 500`() = testApplication {
        app()
        for (header in listOf("Bearer", "Bearer ", "Basic abc", "bearer", "${yaris.token}", "Bearer a b c", "Bearer \"x\"")) {
            val response = client.get("/v1/whoami") { header(HttpHeaders.Authorization, header) }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    @Test
    fun `the scheme is case-insensitive, as HTTP says`() = testApplication {
        app()
        val response = client.get("/v1/whoami") { header(HttpHeaders.Authorization, "bearer ${yaris.token}") }
        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `a corrupt store is a 500, not either car`() = testApplication {
        app()
        store.create(store.get(Slug.parse("yaris"))!!.copy(slug = Slug.parse("ghost")))
        val response = client.get("/v1/whoami") { bearerAuth(yaris.token) }
        response.status shouldBe HttpStatusCode.InternalServerError
        response.bodyAsText() shouldContain "\"error\":\"server\""
        response.bodyAsText() shouldNotContain "yaris"
        response.bodyAsText() shouldNotContain "ghost"
    }
}
