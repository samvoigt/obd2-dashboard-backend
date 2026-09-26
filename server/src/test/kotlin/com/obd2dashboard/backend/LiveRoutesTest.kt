package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.Freshness
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Contract §5.1–5.3 through the real route, with Ktor's WebSocket test client. */
class LiveRoutesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val registry = CarRegistry(InMemoryCarStore())
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val outback = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }.token
    private val index = InMemorySessionIndex()
    private val archive = ArchiveService(index, InMemorySegmentStore())
    private val hub = InMemoryLiveHub()
    private val id = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
    private val line0 = """{"type":"session","v":3,"id":"$id","device":"dev","app":"1.0","started":"2026-09-26T12:00:00Z","vin":"TSTVEHCLE00000001","signals":[],"seq":0,"at":0}"""

    private fun ApplicationTestBuilder.app(config: LiveConfig = LiveConfig()) {
        application { module(registry, archive, hub, config, Clock.systemUTC()) }
    }

    private fun ApplicationTestBuilder.ws() = createClient { install(WebSockets) }

    private suspend fun ApplicationTestBuilder.tablet(
        token: String? = yaris,
        protocol: String? = LIVE_PROTOCOL,
        block: suspend DefaultClientWebSocketSession.() -> Unit,
    ) = ws().webSocket("/v1/live", request = {
        token?.let { bearerAuth(it) }
        protocol?.let { header(HttpHeaders.SecWebSocketProtocol, it) }
    }) { block() }

    private suspend fun DefaultClientWebSocketSession.next(): Map<String, String> {
        val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
        return Json.parseToJsonElement(frame.readText()).jsonObject.mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: v.toString()
        }
    }

    private suspend fun DefaultClientWebSocketSession.hello() {
        send(Frame.Text("""{"t":"hello","v":3,"device":"dev","app":"1.0","wall":1758719312000}"""))
        next()["t"] shouldBe "welcome"
        next()["t"] shouldBe "messages"
    }

    private suspend fun DefaultClientWebSocketSession.closeCode(): Short? =
        withTimeout(10_000) { closeReason.await() }?.code

    @Test
    fun `hello is answered with welcome, then the empty messages sync`() = testApplication {
        app()
        tablet {
            send(Frame.Text("""{"t":"hello","v":3,"device":"dev","app":"1.0","wall":1758719312000}"""))
            val welcome = next()
            welcome["t"] shouldBe "welcome"
            (welcome.getValue("serverWall").toLong() > 1_758_000_000_000) shouldBe true
            val raw = (withTimeout(5_000) { incoming.receive() } as Frame.Text).readText()
            raw shouldBe """{"t":"messages","active":[]}"""
        }
    }

    @Test
    fun `no token and a bad token are refused with auth, then closed 1008`() = testApplication {
        app()
        for (token in listOf(null, "obd2_" + "A".repeat(43))) {
            tablet(token = token) {
                next().let { it["t"] shouldBe "error"; it["code"] shouldBe "auth"; it["fatal"] shouldBe "true" }
                closeCode() shouldBe 1008
            }
        }
    }

    @Test
    fun `no subprotocol is unsupported_version, then closed 1008`() = testApplication {
        app()
        tablet(protocol = null) {
            next().let { it["code"] shouldBe "unsupported_version"; it["fatal"] shouldBe "true" }
            closeCode() shouldBe 1008
        }
        tablet(protocol = "obd2-telemetry.v2") {
            next()["code"] shouldBe "unsupported_version"
        }
    }

    @Test
    fun `a second socket supersedes the first`() = testApplication {
        app()
        coroutineScope { supersede() }
    }

    private suspend fun ApplicationTestBuilder.supersede() = coroutineScope {
        val firstDone = CompletableDeferred<Pair<Map<String, String>, Short?>>()
        val attached = CompletableDeferred<Unit>()
        val job = launch {
            tablet {
                hello()
                attached.complete(Unit)
                firstDone.complete(next() to closeCode())
            }
        }
        attached.await()
        tablet {
            hello()
            val (frame, code) = firstDone.await()
            frame["code"] shouldBe "superseded"
            code shouldBe 1008
            hub.status("yaris").connected shouldBe true // the newer socket still holds the car
        }
        job.join()
    }

    @Test
    fun `an old socket is closed cleanly with 1001`() = testApplication {
        app(LiveConfig(maxAge = 300.milliseconds))
        tablet {
            hello()
            closeCode() shouldBe 1001
        }
    }

    @Test
    fun `shutting down closes every socket with 1012`() = testApplication {
        app()
        tablet {
            hello()
            application.monitor.raise(ApplicationStopping, application)
            closeCode() shouldBe 1012
        }
    }

    @Test
    fun `a rotated token is shut out with auth`() = testApplication {
        app(LiveConfig(recheck = 200.milliseconds))
        tablet {
            hello()
            registry.rotateToken(Slug.parse("yaris"))
            next().let { it["code"] shouldBe "auth"; it["message"] shouldContain "rotated" }
            closeCode() shouldBe 1008
        }
    }

    @Test
    fun `an oversize frame is bad_message and the socket stays up`() = testApplication {
        app()
        tablet {
            hello()
            send(Frame.Text("""{"t":"unknown","pad":"${"x".repeat(70_000)}"}"""))
            next().let { it["code"] shouldBe "bad_message"; it["fatal"] shouldBe "false" }
            send(Frame.Text("""{"t":"session","record":$line0}"""))
            delay(200)
            hub.status("yaris").inSession shouldBe true
        }
    }

    @Test
    fun `frames before hello, and garbage, are bad_message`() = testApplication {
        app()
        tablet {
            send(Frame.Text("""{"t":"session","record":$line0}"""))
            next()["code"] shouldBe "bad_message"
            send(Frame.Text("not json"))
            next()["code"] shouldBe "bad_message"
            send(Frame.Binary(true, byteArrayOf(1, 2)))
            next()["code"] shouldBe "bad_message"
            hello() // still up, and usable
        }
    }

    @Test
    fun `hello without a session stays connected, a parked car in the pits`() = testApplication {
        app()
        tablet {
            hello()
            delay(300)
            hub.status("yaris").freshness(java.time.Instant.now()) shouldBe Freshness.NoSession
        }
    }

    @Test
    fun `a live session creates the archive entry, and its PUT then opens it`() = testApplication {
        app()
        tablet {
            hello()
            send(Frame.Text("""{"t":"session","record":$line0}"""))
            send(Frame.Text("""{"t":"batch","session":"$id","records":[{"type":"sample","signal":"engine.rpm","value":1800,"seq":1,"at":1}]}"""))
            delay(300)
            index.get(id)!!.let { it.car shouldBe "yaris"; it.ackedThrough shouldBe -1 }
            hub.status("yaris").freshness(java.time.Instant.now()) shouldBe Freshness.Live
        }
        client.put("/v1/sessions/$id") { bearerAuth(yaris); setBody(line0) }.status shouldBe HttpStatusCode.Created
    }

    @Test
    fun `another car's session is bad_message and changes nothing`() = testApplication {
        app()
        archive.announce("outback", id)
        tablet {
            hello()
            send(Frame.Text("""{"t":"session","record":$line0}"""))
            next().let { it["code"] shouldBe "bad_message"; it["message"] shouldContain "another car" }
            hub.status("yaris").inSession shouldBe false
        }
        // and outback's own tablet may still use it
        tablet(token = outback) {
            hello()
            send(Frame.Text("""{"t":"session","record":$line0}"""))
            delay(200)
            hub.status("outback").inSession shouldBe true
        }
    }

    @Test
    fun `leaving marks the car offline`() = testApplication {
        app(LiveConfig(maxAge = 10.minutes, recheck = 30.seconds))
        tablet { hello() }
        delay(300)
        hub.status("yaris").connected shouldBe false
    }
}
