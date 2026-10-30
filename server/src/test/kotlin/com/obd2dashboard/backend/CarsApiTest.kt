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
import com.obd2dashboard.backend.live.CarStatus
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.LiveHub
import java.time.Instant
import org.junit.Test

class CarsApiTest {
    private val store = InMemoryCarStore()
    private val registry = CarRegistry(store, passcodeIterations = 1_000)

    @Test
    fun `with no cars it is an empty list, not a 404`() = testApplication {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
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
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }

        val body = client.get("/api/cars").bodyAsText()
        val cars = Json.parseToJsonElement(body).jsonArray

        cars.size shouldBe 1
        // Fails the moment any field is added: making one public must be a decision.
        // `state` was that decision, in M4 (the landing page shows who is live); `standing`, only while streaming,
        // in M36.1 (decision 37).
        cars.single().jsonObject.keys shouldBe setOf("slug", "name", "state")
        cars.single().jsonObject.getValue("state").jsonPrimitive.content shouldBe "offline"
        body shouldNotContain issued.token
        body shouldNotContain car.tokenHash
        body shouldNotContain car.tokenHint
        body shouldNotContain car.passcodeHash!!
    }

    /** A hub whose cars are as [statuses] say (offline otherwise), so a test needs no tablet. */
    private fun hubWith(statuses: Map<String, CarStatus>): LiveHub {
        val real = InMemoryLiveHub()
        return object : LiveHub by real {
            override suspend fun status(car: String): CarStatus = statuses[car] ?: real.status(car)
        }
    }

    @Test
    fun `a streaming car carries where it stands, as its page's timing frame, and one not streaming doesn't (M36_1)`() = testApplication {
        runBlocking {
            registry.addCar(Slug.parse("live-car"), "Alpha")
            registry.addCar(Slug.parse("late-car"), "Bravo")
            registry.addCar(Slug.parse("idle-car"), "Charlie")
            registry.addCar(Slug.parse("gone-car"), "Delta")
        }
        val now = Instant.now()
        val hub = hubWith(mapOf(
            "live-car" to CarStatus(connected = true, inSession = true, lastDataAt = now),
            "late-car" to CarStatus(connected = true, inSession = true, lastDataAt = now.minusSeconds(30)),
            "idle-car" to CarStatus(connected = true, inSession = false, lastDataAt = now),
        ))
        val page = TimingPage(hub)
        val standing = Standing("s1", CourseAt("nhms", 7, "road"), null, null, DriverNow("Sam Voigt", "SAM", null), RaceNow(58, null))
        runBlocking { for (car in listOf("live-car", "late-car", "idle-car", "gone-car")) page.standing(car, standing) }
        application { module(registry, testArchive(), hub, messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), timingPage = page) }

        val cars = Json.parseToJsonElement(client.get("/api/cars").bodyAsText()).jsonArray.associateBy { it.jsonObject.getValue("slug").jsonPrimitive.content }
        val expected = TimingPage.json(standing, null)
        cars.getValue("live-car").jsonObject["standing"] shouldBe expected
        cars.getValue("late-car").jsonObject["standing"] shouldBe expected // behind is still streaming
        // Held from before, but not streaming now: none, as the page would show none.
        cars.getValue("idle-car").jsonObject.keys shouldBe setOf("slug", "name", "state")
        cars.getValue("gone-car").jsonObject.keys shouldBe setOf("slug", "name", "state")
    }

    @Test
    fun `it is public and sorted by name`() = testApplication {
        runBlocking {
            registry.addCar(Slug.parse("zed"), "Alpha")
            registry.addCar(Slug.parse("outback"), "Bravo")
        }
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }

        val response = client.get("/api/cars")
        response.status shouldBe HttpStatusCode.OK
        Json.parseToJsonElement(response.bodyAsText()).jsonArray.map {
            it.jsonObject.getValue("slug").jsonPrimitive.content
        } shouldContainExactly listOf("zed", "outback")
    }
}
