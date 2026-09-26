package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import com.obd2dashboard.backend.live.InMemoryLiveHub
import org.junit.Test

class ApplicationTest {
    private val registry = CarRegistry(InMemoryCarStore())

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    @Test
    fun `health check reports ok without a token`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub()) }

        val response = jsonClient().get("/health")

        response.status shouldBe HttpStatusCode.OK
        response.body<Health>() shouldBe Health(status = "ok")
    }

    @Test
    fun `the retired shared-key route is gone`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub()) }
        client.get("/tablet/ping").status shouldBe HttpStatusCode.NotFound
    }
}
