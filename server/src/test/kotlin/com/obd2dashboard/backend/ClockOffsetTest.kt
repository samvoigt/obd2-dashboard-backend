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
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Each session's clock offset, stored (M18.1): the live lane's smallest, when the session is over. */
class ClockOffsetTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val token = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }.token
    private val index = InMemorySessionIndex()
    private val archive = ArchiveService(index, InMemorySegmentStore())
    private val a = "5ace0000-1111-4111-8111-0000000018a1"
    private val b = "5ace0000-1111-4111-8111-0000000018b1"

    private fun ApplicationTestBuilder.app() {
        application { module(registry, archive, InMemoryLiveHub(), LiveConfig(), Clock.systemUTC(), messages = Messages(InMemoryMessageStore()), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
    }

    private suspend fun ApplicationTestBuilder.tablet(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        createClient { install(WebSockets) }.webSocket("/v1/live", request = { bearerAuth(token); header(HttpHeaders.SecWebSocketProtocol, LIVE_PROTOCOL) }) {
            send(Frame.Text("""{"t":"hello","v":3,"device":"tab-1","app":"1.0","wall":1758719312000}"""))
            block()
        }

    private suspend fun DefaultClientWebSocketSession.session(id: String) =
        send(Frame.Text("""{"t":"session","record":{"type":"session","v":3,"id":"$id","device":"tab-1","started":"2026-09-28T12:00:00Z","signals":[],"seq":0,"at":0}}"""))

    /** A batch whose newest `wall` is the tablet [slowMs] behind the server now. */
    private suspend fun DefaultClientWebSocketSession.batch(id: String, slowMs: Long, seq: Long) {
        val wall = System.currentTimeMillis() - slowMs
        send(Frame.Text("""{"t":"batch","session":"$id","records":[{"type":"sample","signal":"engine.rpm","value":900,"seq":$seq,"at":$seq,"wall":$wall}]}"""))
    }

    private suspend fun stored(id: String): Long {
        withTimeout(5_000) { while (index.get(id)?.clockOffsetMs == null) delay(20) }
        return index.get(id)!!.clockOffsetMs!!
    }

    @Test
    fun `stored when the session ends, the smallest of its batches`() = testApplication {
        app()
        tablet {
            session(a)
            batch(a, 60_000, 1)
            batch(a, 10_000, 2) // the least delayed: the tablet ten seconds slow
            batch(a, 60_000, 3)
            send(Frame.Text("""{"t":"end","session":"$a","lastSeq":3}"""))
            (stored(a) in 10_000L..11_000L) shouldBe true
        }
    }

    @Test
    fun `stored when the next session begins, and at a disconnect, keeping the smaller`() = testApplication {
        app()
        tablet {
            session(a)
            batch(a, 30_000, 1)
            session(b) // a is over
            (stored(a) in 30_000L..31_000L) shouldBe true
            batch(b, 20_000, 1)
        }
        // The socket closed without an end: b's is stored all the same.
        (stored(b) in 20_000L..21_000L) shouldBe true
        // b again after a reconnect, its clock now further out: the smaller stays.
        tablet {
            session(b)
            batch(b, 50_000, 2)
            send(Frame.Text("""{"t":"end","session":"$b","lastSeq":2}"""))
            delay(300)
        }
        (stored(b) in 20_000L..21_000L) shouldBe true
        // A larger one written later (another instance, a restarted server's first batches): the smaller stays.
        runBlocking { archive.setClockOffset(b, 90_000) }
        (stored(b) in 20_000L..21_000L) shouldBe true
        runBlocking { archive.setClockOffset(b, 5_000) }
        stored(b) shouldBe 5_000L
    }
}
