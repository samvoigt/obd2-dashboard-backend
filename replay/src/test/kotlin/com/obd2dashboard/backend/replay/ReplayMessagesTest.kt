package com.obd2dashboard.backend.replay

import com.obd2dashboard.backend.LiveConfig
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.module
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The replay as the tablet's message widget (M5.6), against the real server, with crew sends through the real API. */
class ReplayMessagesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val courses = com.obd2dashboard.backend.courses.InMemoryCourseStore()
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val token = runBlocking {
        registry.addCar(Slug.parse("yaris"), "Yaris").also { registry.setPasscode(Slug.parse("yaris"), "pit-lane".toCharArray()) }.token
    }
    private val store = InMemoryMessageStore()
    private val messages = Messages(store)
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val http = HttpClient.newHttpClient()
    private val logs = mutableListOf<String>()

    private fun start(config: LiveConfig = LiveConfig()): String {
        val s = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            module(registry, ArchiveService(InMemorySessionIndex(), InMemorySegmentStore()), InMemoryLiveHub(), config, Clock.systemUTC(),
                messages = messages, crewKey = ByteArray(32), courses = courses, events = com.obd2dashboard.backend.EventStores(com.obd2dashboard.backend.events.InMemoryDriverStore(), com.obd2dashboard.backend.events.InMemoryEventStore()))
        }.start()
        server = s
        return "http://127.0.0.1:${runBlocking { s.engine.resolvedConnectors().first().port }}"
    }

    @After
    fun stop() {
        server?.stop(100, 1_000)
    }

    private fun cookie(base: String): String = http.send(
        HttpRequest.newBuilder(URI.create("$base/api/cars/yaris/login")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"passcode":"pit-lane"}""")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).headers().firstValue("set-cookie").get().substringBefore(';')

    private fun send(base: String, cookie: String, text: String, preset: String): String = http.send(
        HttpRequest.newBuilder(URI.create("$base/api/cars/yaris/messages")).header("Cookie", cookie).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"text":"$text","preset":"$preset"}""")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body().let { Json.parseToJsonElement(it).jsonObject.getValue("id").jsonPrimitive.content }

    private fun file() = SessionFile.load(Path.of(ReplayMessagesTest::class.java.getResource("/synthetic-v1.jsonl")!!.toURI()), token, "d")

    private suspend fun until(what: String, check: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) { check(System.currentTimeMillis() < end) { "not within 10 s: $what" }; delay(25) }
    }

    private fun replayer(base: String, options: LiveOptions) =
        LiveReplayer(URI.create(base), token, options, log = { synchronized(logs) { logs += it } })

    @Test
    fun `a crew message reaches the replay, which reports received then displayed`() = runBlocking<Unit> {
        val base = start()
        val c = cookie(base)
        coroutineScope {
            val run = async { replayer(base, LiveOptions(speed = 150.0, waitScale = 0.0)).replay(file()) }
            delay(500)
            val id = send(base, c, "PIT NOW", "pit")
            until("displayed") { store.get(id)?.state == MessageState.Displayed }
            synchronized(logs) { logs.any { it.startsWith("message $id \"PIT NOW\" (pit)") } } shouldBe true
            (store.get(id)!!.receivedAt != null) shouldBe true // received was reported, before displayed
            run.await().shouldBeInstanceOf<LiveResult.Ended>()
        }
    }

    @Test
    fun `with no message widget it stops at received`() = runBlocking<Unit> {
        val base = start()
        val c = cookie(base)
        coroutineScope {
            val run = async { replayer(base, LiveOptions(speed = 150.0, waitScale = 0.0, widget = false)).replay(file()) }
            delay(500)
            val id = send(base, c, "BOX THIS LAP", "box")
            until("received") { store.get(id)?.state == MessageState.Received }
            delay(500)
            store.get(id)!!.state shouldBe MessageState.Received
            run.await()
        }
    }

    @Test
    fun `a clear the replay never heard takes the message down at its next sync`() = runBlocking<Unit> {
        val base = start(LiveConfig(maxAge = 700.milliseconds)) // so it reconnects, and is synced, often
        val c = cookie(base)
        coroutineScope {
            val run = async { replayer(base, LiveOptions(speed = 150.0, waitScale = 0.0)).replay(file()) }
            delay(300)
            val id = send(base, c, "PUSH", "push")
            until("displayed") { store.get(id)?.state == MessageState.Displayed }
            // Cleared in the store only, as if while the car was out of range: no `clear` frame is sent.
            messages.clear("yaris", id)
            until("taken down by a sync") { synchronized(logs) { logs.any { it == "taken down: $id (not in the sync)" } } }
            run.await()
        }
    }
}
