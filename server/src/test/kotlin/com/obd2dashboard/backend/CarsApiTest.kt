package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class CarsApiTest {
    private val store = InMemoryCarStore()
    private val registry = CarRegistry(store, passcodeIterations = 1_000)

    @Test
    fun `with no cars it is an empty list, not a 404`() = testApplication {
        application { module(registry, testArchive()) }
        val response = client.get("/api/cars")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "[]"
    }

    @Test
    fun `each car is exactly its slug and name, even with every field set`() = testApplication {
        val issued = runBlocking {
            registry.addCar(Slug.parse("yaris"), "Yaris").also {
                registry.setPasscode(Slug.parse("yaris"), "pit-lane".toCharArray())
            }
        }
        val car = runBlocking { registry.get(Slug.parse("yaris"))!! }
        application { module(registry, testArchive()) }

        val body = client.get("/api/cars").bodyAsText()
        val cars = Json.parseToJsonElement(body).jsonArray

        cars.size shouldBe 1
        // Fails the moment any field is added: making one public must be a decision.
        cars.single().jsonObject.keys shouldBe setOf("slug", "name")
        body shouldNotContain issued.token
        body shouldNotContain car.tokenHash
        body shouldNotContain car.tokenHint
        body shouldNotContain car.passcodeHash!!
    }

    @Test
    fun `it is public and sorted by name`() = testApplication {
        runBlocking {
            registry.addCar(Slug.parse("zed"), "Alpha")
            registry.addCar(Slug.parse("outback"), "Bravo")
        }
        application { module(registry, testArchive()) }

        val response = client.get("/api/cars")
        response.status shouldBe HttpStatusCode.OK
        Json.parseToJsonElement(response.bodyAsText()).jsonArray.map {
            it.jsonObject.getValue("slug").jsonPrimitive.content
        } shouldContainExactly listOf("zed", "outback")
    }
}
