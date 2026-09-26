package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The website's stream, read as raw SSE bytes while a tablet streams over the
 * real socket. **On real Netty**, not Ktor's test engine, which buffers a
 * response until it ends, and an SSE stream never does (found building M4.4).
 */
class BrowserRoutesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val registry = CarRegistry(InMemoryCarStore())
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val hub = InMemoryLiveHub()
    private val messageStore = InMemoryMessageStore()
    private val messages = Messages(messageStore)
    private val archive = ArchiveService(InMemorySessionIndex(), InMemorySegmentStore())
    private val id = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
    private val vin = "TSTVEHCLE00000001"
    private val sessionFrame = """{"t":"session","record":{"type":"session","v":3,"id":"$id","device":"dev","app":"1.0","started":"2026-09-26T12:00:00Z","vin":"$vin","signals":[{"name":"engine.rpm","unit":"rpm","kind":"number"}],"seq":0,"at":0}}"""
    private fun batch(value: Int, extra: String = "") =
        """{"t":"batch","session":"$id","records":[{"type":"sample","signal":"engine.rpm","value":$value,"seq":$value,"at":$value$extra}]}"""

    private val http = HttpClient.newHttpClient()
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            module(registry, archive, hub, LiveConfig(), Clock.systemUTC(), messages = messages)
        }.start()
    private val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"

    @After
    fun stop() = server.stop(100, 1_000)

    private data class Event(val name: String, val data: JsonObject)

    /** An open SSE stream: every raw line kept, and events as they complete. */
    private inner class Stream(path: String = "/api/cars/yaris/live") {
        private val raw = StringBuilder()
        private val events = LinkedBlockingQueue<Event>()
        private val response = http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofLines())
        val status: Int = response.statusCode()

        init {
            thread(isDaemon = true) {
                var name = ""
                val data = StringBuilder()
                runCatching {
                    response.body().forEach { line ->
                        synchronized(raw) { raw.append(line).append('\n') }
                        when {
                            line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            line.isEmpty() && data.isNotEmpty() -> {
                                events += Event(name, Json.parseToJsonElement(data.toString()).jsonObject)
                                name = ""
                                data.clear()
                            }
                        }
                    }
                }
            }
        }

        fun next(): Event = events.poll(10, TimeUnit.SECONDS) ?: error("no event within 10 s")
        fun take(n: Int): List<Event> = List(n) { next() }
        fun rawText(): String = synchronized(raw) { raw.toString() }
    }

    /** A tablet on the real socket (the JDK's client), already past hello. */
    private inner class Tablet : WebSocket.Listener {
        private val frames = LinkedBlockingQueue<String>()
        private val socket: WebSocket = http.newWebSocketBuilder()
            .header("Authorization", "Bearer $yaris")
            .subprotocols(LIVE_PROTOCOL)
            .buildAsync(URI.create(base.replace("http", "ws") + "/v1/live"), this)
            .get(10, TimeUnit.SECONDS)

        init {
            send("""{"t":"hello","v":3}""")
            repeat(2) { frames.poll(10, TimeUnit.SECONDS) ?: error("no welcome/messages") }
        }

        fun send(text: String) {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS)
        }

        fun close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            frames += data.toString()
            ws.request(1)
            return CompletableFuture.completedFuture(null)
        }
    }

    private fun get(path: String): Pair<Int, String> =
        http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString())
            .let { it.statusCode() to it.body() }

    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "condition not met within 5 s" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `an unknown car, or a bad slug, is 404 before any stream`() {
        get("/api/cars/nope/live").let { (status, body) ->
            status shouldBe 404
            body shouldContain "not_found"
        }
        get("/api/cars/NOT%20A%20SLUG/live").first shouldBe 404
    }

    @Test
    fun `a browser gets a snapshot first, then the tablet's events in order`() {
        val stream = Stream()
        stream.status shouldBe 200
        val first = stream.next()
        first.name shouldBe "snapshot"
        first.data.getValue("status").jsonObject.getValue("state").jsonPrimitive.content shouldBe "offline"

        val tablet = Tablet()
        tablet.send(sessionFrame)
        tablet.send(batch(1800))
        val events = stream.take(5)
        events.map { it.name } shouldContainInOrder listOf("status", "session", "status", "records", "status")
        val records = events.first { it.name == "records" }.data
        (records.getValue("serverNow").jsonPrimitive.content.toLong() > 0) shouldBe true
        records.getValue("records").jsonArray.single().jsonObject.getValue("value").jsonPrimitive.content shouldBe "1800"

        tablet.close()
        val last = stream.next()
        last.name shouldBe "status"
        last.data.getValue("state").jsonPrimitive.content shouldBe "offline"
    }

    @Test
    fun `a browser arriving mid-session gets the whole state in its snapshot`() {
        val tablet = Tablet()
        tablet.send(sessionFrame)
        tablet.send(batch(900))
        tablet.send(batch(1800))
        eventually { runBlocking { hub.status("yaris").lastDataAt } != null }
        Thread.sleep(100)

        val snapshot = Stream().next()
        snapshot.name shouldBe "snapshot"
        val data = snapshot.data
        data.getValue("status").jsonObject.getValue("state").jsonPrimitive.content shouldBe "live"
        data.getValue("session").jsonObject.getValue("started").jsonPrimitive.content shouldBe "2026-09-26T12:00:00Z"
        data.getValue("latest").jsonArray.single().jsonObject.getValue("value").jsonPrimitive.content shouldBe "1800"
        data.getValue("history").jsonArray.size shouldBe 2
        data.getValue("signals").jsonArray.size shouldBe 1
        tablet.close()
    }

    @Test
    fun `no byte the browser receives holds the VIN`() {
        val stream = Stream()
        stream.next() // the snapshot
        val tablet = Tablet()
        tablet.send(sessionFrame) // the session record carries it
        tablet.send(batch(1800, extra = ",\"vin\":\"$vin\"")) // and so, here, does a record
        stream.take(5)
        val late = Stream() // a browser arriving later, whose snapshot is built from the same state
        late.next()
        tablet.close()

        for (raw in listOf(stream.rawText(), late.rawText())) {
            raw shouldContain "engine.rpm"
            raw shouldNotContain vin
            raw shouldNotContain "\"vin\""
        }
    }

    @Test
    fun `the landing page shows each car's state`() {
        get("/api/cars").second shouldBe """[{"slug":"yaris","name":"Yaris","state":"offline"}]"""
        val tablet = Tablet()
        eventually { get("/api/cars").second.contains("\"state\":\"no_session\"") }
        tablet.send(sessionFrame)
        tablet.send(batch(1))
        eventually { get("/api/cars").second.contains("\"state\":\"live\"") }
        tablet.close()
        eventually { get("/api/cars").second.contains("\"state\":\"offline\"") }
    }
}
