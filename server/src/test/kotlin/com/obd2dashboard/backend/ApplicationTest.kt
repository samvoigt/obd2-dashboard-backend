package com.obd2dashboard.backend

import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test

class ApplicationTest {
    private val key = "test-key"

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `health check reports ok without a key`() = testApplication {
        application { module(key) }

        val response = jsonClient().get("/healthz")

        response.status shouldBe HttpStatusCode.OK
        response.body<Health>() shouldBe Health(status = "ok")
    }

    @Test
    fun `tablet ping accepts the key`() = testApplication {
        application { module(key) }

        val response = jsonClient().get("/tablet/ping") { bearerAuth(key) }

        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `tablet ping rejects a wrong key`() = testApplication {
        application { module(key) }

        client.get("/tablet/ping") { bearerAuth("wrong") }.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `tablet ping rejects a missing key`() = testApplication {
        application { module(key) }

        client.get("/tablet/ping").status shouldBe HttpStatusCode.Unauthorized
    }
}
