package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/** Crew messages beside a session (M18.4). */
class SessionMessagesTest {
    private val t = Instant.parse("2026-09-28T12:00:00Z")
    private fun record(offset: Long? = null, created: Instant = t) =
        SessionRecord("s", "outback", null, null, 10, emptyList(), true, null, 0, created, created.plusSeconds(3600), clockOffsetMs = offset)

    @Test
    fun `the window is the session on the server's clock, by its stored offset, else created less started`() {
        val started = t.toEpochMilli() - 40_000_000 // the tablet eleven hours slow, near enough
        val ended = started + 600_000
        // Stored: exactly.
        SessionMessages.window(record(offset = 39_999_000), started, ended, t) shouldBe
            Triple(Instant.ofEpochMilli(started + 39_999_000), Instant.ofEpochMilli(ended + 39_999_000), 39_999_000L)
        // Not stored: when the server first heard it, less its own start.
        SessionMessages.window(record(), started, ended, t) shouldBe Triple(t, t.plusMillis(600_000), 40_000_000L)
        // Still going, no summary: from first heard to now; the stored offset still places the marks.
        SessionMessages.window(record(offset = 5), null, null, t.plusSeconds(90)) shouldBe Triple(t, t.plusSeconds(90), 5L)
    }

    @Test
    fun `those sent while it ran, oldest first, each placed on the tablet's clock`() = testApplication {
        val sid = "5ace0000-1111-4111-8111-000000000184"
        val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
        val index = InMemorySessionIndex()
        val store = InMemorySegmentStore()
        val messageStore = InMemoryMessageStore()
        val lines = boxLog(0, 300).map { it.replace("\"id\":\"s\"", "\"id\":\"$sid\"") }
        val offset = 3_600_000L // the tablet an hour behind
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            index.create(SessionRecord(sid, "outback", null, null, lines.size - 1L, emptyList(), true, null, 0, t, t, clockOffsetMs = offset))
            store.put(ArchiveService.sessionKey(sid), lines.joinToString("\n", postfix = "\n").toByteArray())
        }
        application { module(registry, ArchiveService(index, store), InMemoryLiveHub(), messages = Messages(messageStore), courses = testCourses(), events = testEvents(), crewKey = testCrewKey()) }
        val json = Json { ignoreUnknownKeys = true }
        val session = json.decodeFromString<SessionDetail>(client.get("/api/sessions/$sid").bodyAsText()).session
        fun msg(id: String, text: String, sentOnTablet: Long, car: String = "outback") = runBlocking {
            val sent = Instant.ofEpochMilli(sentOnTablet + offset)
            messageStore.create(Message(id, car, text, null, sent, sent.plusSeconds(60), MessageState.Displayed, sent.plusMillis(400), sent.plusMillis(900)))
        }
        msg("m2", "Box, box", session.started + 200_000)
        msg("m1", "Push", session.started + 10_000)
        msg("before", "Warm up", session.started - 60_000)
        msg("after", "Well done", session.ended + 60_000)
        msg("theirs", "Not ours", session.started + 20_000, car = "yaris")

        val list = json.decodeFromString<List<SessionMessage>>(client.get("/api/sessions/$sid/messages").bodyAsText())
        list.map { it.text } shouldBe listOf("Push", "Box, box")
        list[0].at shouldBe session.started + 10_000
        list[0].sentAt shouldBe session.started + 10_000 + offset
        list[0].receivedAt shouldBe list[0].sentAt + 400
        list[0].displayedAt shouldBe list[0].sentAt + 900
        list[0].state shouldBe "displayed"
    }
}
