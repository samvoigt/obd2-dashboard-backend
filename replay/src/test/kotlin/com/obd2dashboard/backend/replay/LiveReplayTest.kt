package com.obd2dashboard.backend.replay

import com.obd2dashboard.backend.LiveConfig
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.BrowserEvent
import com.obd2dashboard.backend.live.Attachment
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.TabletHandle
import com.obd2dashboard.backend.module
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The live replay against the real server module, over a real socket on a free port. */
class LiveReplayTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val registry = CarRegistry(InMemoryCarStore())
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val hub = InMemoryLiveHub()
    private val index = InMemorySessionIndex()
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val logs = mutableListOf<String>()

    private fun start(config: LiveConfig = LiveConfig(), liveHub: LiveHub = hub): URI {
        val s = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            module(registry, ArchiveService(index, InMemorySegmentStore()), liveHub, config, Clock.systemUTC())
        }.start()
        server = s
        return URI.create("http://127.0.0.1:${runBlocking { s.engine.resolvedConnectors().first().port }}")
    }

    @After
    fun stop() {
        server?.stop(100, 1_000)
    }

    private fun resource(name: String): Path = Path.of(LiveReplayTest::class.java.getResource("/$name")!!.toURI())
    private fun load(name: String) = SessionFile.load(resource(name), yaris, "00000000-0000-4000-8000-00000000d0e5")
    private fun replayer(uri: URI, options: LiveOptions = LiveOptions(speed = 0.0, waitScale = 0.0)) =
        LiveReplayer(uri, yaris, options, log = { synchronized(logs) { logs += it } })

    private suspend fun snapshot() = (hub.subscribe("yaris").first() as BrowserEvent.Snapshot).snapshot

    /** The last sample of each signal in the log, as the replay's coalescing must leave it. */
    private fun lastSamples(file: SessionFile): Map<String, String> = file.lines.drop(1)
        .map { Json.parseToJsonElement(it.decodeToString()).jsonObject }
        .filter { it.str("type") == "sample" }
        .associate { it.str("signal")!! to it.toString() }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `a log replayed live leaves the hub holding its last sample of every signal`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v2.jsonl.gz")
        val result = replayer(uri).replay(file).shouldBeInstanceOf<LiveResult.Ended>()
        (result.batches > 100) shouldBe true
        delay(300)
        val snap = snapshot()
        snap.latest.associate { it.str("signal")!! to it.toString() } shouldBe lastSamples(file)
        snap.header!!.str("id") shouldBe file.id
        snap.status.inSession shouldBe false // `end` was sent
        (snap.history.isNotEmpty()) shouldBe true
        index.get(file.id)!!.car shouldBe "yaris" // the live session announced it to the archive
    }

    @Test
    fun `a dropped socket reconnects, resnapshots and carries on`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v1.jsonl") // ~700 s of log time
        val result = replayer(uri, LiveOptions(speed = 700.0, dropEveryMillis = 250, waitScale = 0.0)).replay(file)
            .shouldBeInstanceOf<LiveResult.Ended>()
        (result.reconnects >= 1) shouldBe true
        logs.any { it.startsWith("dropping the socket") } shouldBe true
        delay(300)
        snapshot().latest.associate { it.str("signal")!! to it.toString() } shouldBe lastSamples(file)
    }

    @Test
    fun `a clean 1001 reconnects at once, and a second within 10 s backs off`() = runBlocking<Unit> {
        val uri = start(LiveConfig(maxAge = 300.milliseconds))
        val file = load("synthetic-v1.jsonl")
        replayer(uri, LiveOptions(speed = 700.0, waitScale = 0.0)).replay(file).shouldBeInstanceOf<LiveResult.Ended>()
        val reconnects = logs.filter { it.startsWith("reconnecting after close 1001") }
        (reconnects.size >= 2) shouldBe true
        reconnects.first() shouldContain "at once"
        reconnects[1] shouldContain "waited"
    }

    /** A hub that forgets everything on each new socket, as a restarted server would. */
    private class RestartingHub : LiveHub {
        @Volatile var current = InMemoryLiveHub()
        override suspend fun attach(car: String, tablet: TabletHandle): Attachment {
            current = InMemoryLiveHub()
            return current.attach(car, tablet)
        }
        override fun subscribe(car: String) = current.subscribe(car)
        override suspend fun status(car: String) = current.status(car)
        override suspend fun closeAll(code: Short, reason: String) = current.closeAll(code, reason)
    }

    @Test
    fun `each reconnect's snapshot restores what a restarted server forgot`() = runBlocking<Unit> {
        val restarting = RestartingHub()
        val uri = start(LiveConfig(maxAge = 400.milliseconds), restarting)
        val id = "33333333-3333-4333-8333-333333333333"
        // One signal sampled once, at the start; then two seconds of engine speed.
        val lines = listOf(
            """{"type":"session","v":3,"id":"$id","started":"2026-09-26T12:00:00Z","seq":0,"at":0}""",
            """{"type":"sample","signal":"early.only","value":42,"seq":1,"at":1000}""",
        ) + (1..40).map { """{"type":"sample","signal":"engine.rpm","value":$it,"seq":${it + 1},"at":${1000 + it * 50}}""" }
        val result = replayer(uri, LiveOptions(speed = 1.0, waitScale = 0.0)).replay(SessionFile(lines.map { it.toByteArray() }, id))
        (result.shouldBeInstanceOf<LiveResult.Ended>().reconnects >= 2) shouldBe true
        delay(300)
        // The last server to hear from the tablet never saw early.only in a batch; only a snapshot carried it.
        val latest = (restarting.current.subscribe("yaris").first() as BrowserEvent.Snapshot).snapshot.latest
        latest.map { it.str("signal") }.toSet() shouldBe setOf("early.only", "engine.rpm")
    }

    @Test
    fun `superseded stops, and does not reconnect by itself`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v1.jsonl")
        val results = coroutineScope {
            val first = async { replayer(uri, LiveOptions(speed = 350.0, waitScale = 0.0)).replay(file) }
            delay(400)
            val second = async { replayer(uri, LiveOptions(speed = 0.0, waitScale = 0.0)).replay(file) }
            listOf(first, second).awaitAll()
        }
        results[0].shouldBeInstanceOf<LiveResult.Stopped>().reason shouldContain "superseded"
        results[1].shouldBeInstanceOf<LiveResult.Ended>()
    }

    @Test
    fun `a wrong token stops with auth`() = runBlocking<Unit> {
        val uri = start()
        val result = LiveReplayer(uri, "obd2_" + "A".repeat(43), LiveOptions(speed = 0.0, waitScale = 0.0))
            .replay(load("synthetic-v1.jsonl"))
        result.shouldBeInstanceOf<LiveResult.Stopped>().reason shouldContain "auth"
    }

    @Test
    fun `samples in one 200 ms window are coalesced to the latest per signal`() = runBlocking<Unit> {
        val uri = start()
        val id = "22222222-2222-4222-8222-222222222222"
        val lines = listOf(
            """{"type":"session","v":3,"id":"$id","started":"2026-09-26T12:00:00Z","seq":0,"at":0}""",
            """{"type":"sample","signal":"engine.rpm","value":1,"seq":1,"at":1000}""",
            """{"type":"sample","signal":"engine.rpm","value":2,"seq":2,"at":1050}""",
            """{"type":"stopped","signal":"engine.oil_temperature","reason":"r","seq":3,"at":1060}""",
            """{"type":"sample","signal":"engine.rpm","value":3,"seq":4,"at":1100}""",
            """{"type":"sample","signal":"engine.rpm","value":4,"seq":5,"at":1300}""",
        ).map { it.toByteArray() }
        replayer(uri).replay(SessionFile(lines, id)).shouldBeInstanceOf<LiveResult.Ended>()
        delay(300)
        // Window one (1000–1199): rpm 3 and the stopped record; window two: rpm 4.
        snapshot().history.map { it.record.toString() }.map { r ->
            Json.parseToJsonElement(r).jsonObject.let { it.str("type") + ":" + (it["value"]?.jsonPrimitive?.content ?: it.str("signal")) }
        } shouldContainExactly listOf("stopped:engine.oil_temperature", "sample:3", "sample:4")
    }

    @Test
    fun `units come from the contract's appendix and nowhere else`() {
        val units = LiveReplayer.unitsFrom(resource("contract-excerpt.md"))
        units shouldBe mapOf(
            "diagnostics.mil" to "",
            "engine.coolant_temperature" to "°C",
            "engine.rpm" to "rpm",
            "vehicle.speed" to "km/h",
        )
        val file = SessionFile.load(resource("synthetic-v1.jsonl"), yaris, "d", units)
        val signals = Json.parseToJsonElement(file.lines[0].decodeToString()).jsonObject.getValue("signals").jsonArray
            .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("unit").jsonPrimitive.content }
        signals.getValue("engine.rpm") shouldBe "rpm"
        signals.getValue("vehicle.speed") shouldBe "km/h"
        signals.getValue("fuel.system_1_status") shouldBe "" // not in the excerpt
    }
}
