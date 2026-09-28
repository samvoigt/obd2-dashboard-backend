package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.BrowserEvent
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.LiveSnapshot
import com.obd2dashboard.backend.live.LiveUpdate
import com.obd2dashboard.backend.live.CarStatus
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.Messages
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
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Contract §5.4 on the real socket: the sync after hello, pushes, clears, and the tablet's reports. */
class CrewSocketTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val registry = CarRegistry(InMemoryCarStore())
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val store = InMemoryMessageStore()
    private val messages = Messages(store)
    private var hub = InMemoryLiveHub()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun crew() = CrewMessages(messages, hub, scope)

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(InMemorySessionIndex(), InMemorySegmentStore()), hub, LiveConfig(), Clock.systemUTC(), messages = messages, courses = testCourses(), events = testEvents(), crewKey = testCrewKey())
        }
    }

    private suspend fun ApplicationTestBuilder.tablet(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        createClient { install(WebSockets) }.webSocket("/v1/live", request = {
            bearerAuth(yaris); header(HttpHeaders.SecWebSocketProtocol, LIVE_PROTOCOL)
        }) { block() }

    private suspend fun DefaultClientWebSocketSession.next(): String =
        (withTimeout(5_000) { incoming.receive() } as Frame.Text).readText()

    /** hello, then the welcome; returns the `messages` sync that follows it. */
    private suspend fun DefaultClientWebSocketSession.hello(): String {
        send(Frame.Text("""{"t":"hello","v":3}"""))
        Json.parseToJsonElement(next()).jsonObject["t"]!!.jsonPrimitive.content shouldBe "welcome"
        return next()
    }

    private fun activeIds(sync: String) =
        Json.parseToJsonElement(sync).jsonObject.getValue("active").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private suspend fun state(id: String): MessageState {
        repeat(50) { store.get(id)?.state?.takeIf { s -> s != MessageState.Queued }?.let { return it }; delay(20) }
        return store.get(id)!!.state
    }

    @Test
    fun `a message queued while the car is away arrives in the next sync`() = testApplication {
        app()
        val m = crew().send("yaris", "PIT NOW", "pit")
        tablet {
            val sync = hello()
            activeIds(sync) shouldBe listOf(m.id)
            val item = Json.parseToJsonElement(sync).jsonObject.getValue("active").jsonArray.single().jsonObject
            item.getValue("text").jsonPrimitive.content shouldBe "PIT NOW"
            (item.getValue("ttlMs").jsonPrimitive.long in 1_790_000..1_800_000) shouldBe true
        }
    }

    @Test
    fun `a message sent while attached is pushed at once`() = testApplication {
        app()
        tablet {
            hello() shouldBe """{"t":"messages","active":[]}"""
            val m = crew().send("yaris", "BOX THIS LAP", "box", Duration.ofMinutes(2))
            val frame = Json.parseToJsonElement(next()).jsonObject
            frame.getValue("t").jsonPrimitive.content shouldBe "message"
            frame.getValue("id").jsonPrimitive.content shouldBe m.id
            frame.getValue("preset").jsonPrimitive.content shouldBe "box"
            (frame.getValue("ttlMs").jsonPrimitive.long in 110_000..120_000) shouldBe true
        }
    }

    @Test
    fun `a clear while attached is pushed, while away the next sync leaves it out`() = testApplication {
        app()
        val first = crew().send("yaris", "PUSH", "push")
        tablet {
            activeIds(hello()) shouldBe listOf(first.id)
            crew().clear("yaris", first.id)
            next() shouldBe """{"t":"clear","id":"${first.id}"}"""
        }
        val second = crew().send("yaris", "FUEL", "fuel")
        crew().clear("yaris", second.id) // while away
        tablet { hello() shouldBe """{"t":"messages","active":[]}""" }
    }

    @Test
    fun `received then displayed move the state, repeats and unknown ids change nothing, with no error`() = testApplication {
        app()
        val m = crew().send("yaris", "PIT NOW", "pit")
        tablet {
            hello()
            send(Frame.Text("""{"t":"received","id":"${m.id}"}"""))
            state(m.id) shouldBe MessageState.Received
            send(Frame.Text("""{"t":"displayed","id":"${m.id}"}"""))
            repeat(50) { if (store.get(m.id)!!.state == MessageState.Displayed) return@repeat; delay(20) }
            store.get(m.id)!!.state shouldBe MessageState.Displayed
            send(Frame.Text("""{"t":"received","id":"${m.id}"}"""))
            send(Frame.Text("""{"t":"displayed","id":"m_0000000000000000"}"""))
            // No bad_message for either: the next frame the tablet sees is the crew's next message.
            val later = crew().send("yaris", "FUEL", "fuel")
            next() shouldContain later.id
            store.get(m.id)!!.state shouldBe MessageState.Replaced
        }
    }

    @Test
    fun `after a restart, the sync still carries the active message`() = testApplication {
        app()
        val m = crew().send("yaris", "PIT NOW", "pit")
        tablet { hello() }
        // A new revision: a new, empty hub over the same stored messages.
        hub = InMemoryLiveHub()
        testApplication {
            app()
            tablet { activeIds(hello()) shouldBe listOf(m.id) }
        }
    }

    @Test
    fun `each send schedules its expiry, which marks it and tells the car's browsers`() = runBlocking<Unit> {
        var now = Instant.parse("2026-09-26T12:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val timed = Messages(InMemoryMessageStore(), clock)
        val scheduled = mutableListOf<Pair<Duration, suspend () -> Unit>>()
        val changes = mutableListOf<MessageState>()
        val recording = object : LiveHub by hub {
            override suspend fun publish(car: String, update: LiveUpdate) {
                (update as? LiveUpdate.MessageChanged)?.let { changes += it.message.state }
            }
        }
        val m = CrewMessages(timed, recording, scope, clock) { d, action -> scheduled += d to action }
            .send("yaris", "SLOW DOWN", "slow", Duration.ofMinutes(1))

        scheduled.single().first shouldBe Duration.ofMinutes(1)
        now = now.plus(Duration.ofMinutes(1))
        scheduled.single().second.invoke()
        timed.get(m.id)!!.state shouldBe MessageState.Expired
        changes.last() shouldBe MessageState.Expired
    }

    @Test
    fun `a public stream's encoder drops message changes`() {
        val m = Message("m_1", "yaris", "PIT NOW", "pit", Instant.EPOCH, Instant.EPOCH, MessageState.Queued)
        encode(BrowserEvent.Update(LiveUpdate.MessageChanged(m)), Clock.systemUTC()) shouldBe null
        val empty = LiveSnapshot(CarStatus(false, false, null), null, null, emptyList(), emptyList(), null, emptyList())
        (encode(BrowserEvent.Snapshot(empty), Clock.systemUTC())!!.second.toString()).contains("PIT") shouldBe false
    }
}
