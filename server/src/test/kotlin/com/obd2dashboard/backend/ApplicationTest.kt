package com.obd2dashboard.backend

import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.Test

class ApplicationTest {
    @Test
    fun `health check reports ok`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/healthz")

        response.status shouldBe HttpStatusCode.OK
        response.body<Health>() shouldBe Health(status = "ok")
    }
}
