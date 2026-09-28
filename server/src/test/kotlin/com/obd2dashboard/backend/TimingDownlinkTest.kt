package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLap
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** `timing` to the tablet (M17.4, contract §22.7), through the real socket. */
class TimingDownlinkTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val outback = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }.token
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val index = InMemorySessionIndex()
    private val archive = ArchiveService(index, InMemorySegmentStore())
    private val courses = InMemoryCourseStore()
    private val events = testEvents()
    private val id = "5ace0000-1111-4111-8111-000000000174"
    private val now = Instant.now()

    /** The box, 0–160 s, laps 1 and 2 each written after the fix it ended by; `seq` in the order written. */
    private val lines: List<String> = boxLog(0, 160).toMutableList().let { out ->
        listOf(boxLap(1, 1), boxLap(2, 1)).forEachIndexed { i, lap -> out.add(out.indexOfFirst { it.contains("\"fixAt\":${12_500 + 70_000 * (i + 1) + 500}") }, lap) }
        out.mapIndexed { i, l -> l.replace("\"id\":\"s\"", "\"id\":\"$id\"").replace(Regex("\"seq\":\\d+"), "\"seq\":$i") }
    }

    init {
        runBlocking {
            courses.save("box", 0, "Box", boxGeoJson(), now)
            events.drivers.put(Driver("d-sam", "Sam Voigt", "SAM"))
            events.drivers.put(Driver("d-alex", "Alex Rider", "ALE"))
            registry.setPasscode(Slug.parse("outback"), "outback-crew".toCharArray())
        }
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, archive, InMemoryLiveHub(), LiveConfig(), Clock.systemUTC(), messages = Messages(InMemoryMessageStore()), courses = courses, events = events, crewKey = testCrewKey())
        }
    }

    private suspend fun ApplicationTestBuilder.tablet(token: String, block: suspend DefaultClientWebSocketSession.() -> Unit) =
        createClient { install(WebSockets) }.webSocket("/v1/live", request = { bearerAuth(token); header(HttpHeaders.SecWebSocketProtocol, LIVE_PROTOCOL) }) { block() }

    private suspend fun DefaultClientWebSocketSession.hello(timing: Boolean) {
        send(Frame.Text("""{"t":"hello","v":3,"device":"tab-1","app":"1.0","wall":1758719312000${if (timing) ""","features":["timing.1"]""" else ""}}"""))
    }

    /** The next `timing` frame, other frames skipped; null if none within [ms]. */
    private suspend fun DefaultClientWebSocketSession.timing(ms: Long = 5_000): JsonObject? = withTimeoutOrNull(ms) {
        while (true) {
            val f = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            if (f["t"].toString() == "\"timing\"") return@withTimeoutOrNull f
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private suspend fun DefaultClientWebSocketSession.batch(from: Int, to: Int) =
        send(Frame.Text("""{"t":"batch","session":"$id","records":[${lines.subList(from, to).joinToString(",")}]}"""))

    private suspend fun ApplicationTestBuilder.setDriver(driver: String) {
        val cookie = client.post("/api/cars/outback/login") { contentType(ContentType.Application.Json); setBody("""{"passcode":"outback-crew"}""") }
            .headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        client.put("/api/cars/outback/sessions/$id/driver") {
            header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Origin, "http://localhost"); header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json); setBody("""{"driver":"$driver"}""")
        }.status shouldBe HttpStatusCode.OK
    }

    private fun JsonObject.at(vararg path: String): String? =
        path.fold(this as kotlinx.serialization.json.JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString() }

    @Test
    fun `the frame is section 22-7's, nulls left out, ages from the tablet's wall now`() {
        val s = Standing(
            "s1", CourseAt("nhms", 7, "road"), BestLap(94.532, listOf(31.298, 32.99, 30.244), "SAM", "s0", 7), listOf(31.298, null, 30.101),
            DriverNow("Sam", "SAM", 1_000_000), RaceNow(58, 2_000_000),
        )
        TimingDownlink.frame(s, 3_412_000).toString() shouldBe
            """{"t":"timing","session":"s1","course":{"id":"nhms","version":7,"layout":"road"},""" +
            """"best":{"time":94.532,"sectors":[31.298,32.99,30.244],"driver":"SAM","session":"s0","lap":7},""" +
            """"bestSectors":[31.298,null,30.101],"driver":{"name":"Sam","code":"SAM","stintAgeMs":2412000},"race":{"lap":58,"sinceStopAgeMs":1412000}}"""
        // No offset measured yet: no ages. In the pits: no time since the stop. A re-timed best: no lap.
        TimingDownlink.frame(s.copy(best = s.best!!.copy(lap = null, driver = null), race = RaceNow(3, null)), null).toString() shouldBe
            """{"t":"timing","session":"s1","course":{"id":"nhms","version":7,"layout":"road"},""" +
            """"best":{"time":94.532,"sectors":[31.298,32.99,30.244],"session":"s0"},""" +
            """"bestSectors":[31.298,null,30.101],"driver":{"name":"Sam","code":"SAM"},"race":{"lap":3}}"""
        TimingDownlink.frame(Standing("s1"), 0).toString() shouldBe """{"t":"timing","session":"s1"}"""
    }

    @Test
    fun `sent after the session frame, again on a lap or a driver set, never when nothing changed`() = testApplication {
        app()
        tablet(outback) {
            hello(timing = true)
            send(Frame.Text("""{"t":"session","record":${lines[0]}}"""))
            timing()!!.let { it.at("session") shouldBe id; it.at("course") shouldBe null }
            batch(1, lines.indexOfFirst { it.contains("\"lap\":2") } + 1)
            val first = timing()!!
            first.at("course", "id") shouldBe "box"
            first.at("course", "version") shouldBe "1"
            first.at("best", "time") shouldBe "70.0"
            first.at("best", "lap") shouldBe "1"
            first.at("driver", "code") shouldBe null
            // On the tablet's clock, known from the batch: its newest `wall` is ~153 s into the drive, the stint began at 12.5 s.
            (first.at("driver", "stintAgeMs")!!.toLong() in 135_000L..150_000L) shouldBe true
            // Fixes and nothing else: nothing new to say.
            batch(lines.indexOfFirst { it.contains("\"lap\":2") } + 1, lines.size)
            timing(1_500) shouldBe null
            setDriver("d-sam")
            timing()!!.at("driver", "code") shouldBe "SAM"
            // The session frame again (a reconnect's): sent, though nothing changed.
            send(Frame.Text("""{"t":"session","record":${lines[0]}}"""))
            timing()!!.at("driver", "code") shouldBe "SAM"
        }
    }

    @Test
    fun `none to a tablet that didn't ask, nor to another car's`() = testApplication {
        app()
        tablet(yaris) {
            hello(timing = true)
            tablet(outback) {
                hello(timing = false)
                send(Frame.Text("""{"t":"session","record":${lines[0]}}"""))
                batch(1, lines.size)
                timing(2_000) shouldBe null
            }
            timing(500) shouldBe null
        }
    }

    @Test
    fun `in a race, a session completed is counted once, from its archive`() = testApplication {
        app()
        runBlocking {
            events.events.save(Event("day", "Day", "2026-09-28", "box", "box", listOf("outback"), listOf(Part("r", PartKind.RACE, "Race", now.minusSeconds(3600), now.plusSeconds(3600)))), 0, now)
        }
        tablet(outback) {
            hello(timing = true)
            send(Frame.Text("""{"t":"session","record":${lines[0]}}"""))
            timing()
            batch(1, lines.size)
            var frame = timing()!!
            while (frame.at("race", "lap") != "2") frame = timing()!!
            // The tablet uploads it whole and completes it.
            val body = lines.joinToString("") { "$it\n" }.toByteArray()
            client.put("/v1/sessions/$id") { bearerAuth(outback); contentType(ContentType.Application.Json); setBody(lines[0]) }
            client.post("/v1/sessions/$id/chunks") {
                bearerAuth(outback); header(HttpHeaders.ContentType, "application/x-ndjson")
                header("X-First-Index", "1"); header("X-Record-Count", (lines.size - 1).toString())
                setBody(lines.drop(1).joinToString("") { "$it\n" }.toByteArray())
            }.status shouldBe HttpStatusCode.OK
            val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            client.post("/v1/sessions/$id/complete") {
                bearerAuth(outback); contentType(ContentType.Application.Json)
                setBody("""{"lastIndex": ${lines.size - 1}, "recordCount": ${lines.size}, "sha256": "$sha"}""")
            }.status shouldBe HttpStatusCode.OK
            setDriver("d-alex")
            frame = timing()!!
            frame.at("driver", "code") shouldBe "ALE"
            frame.at("race", "lap") shouldBe "2" // not 4: the live run let go
        }
    }
}
