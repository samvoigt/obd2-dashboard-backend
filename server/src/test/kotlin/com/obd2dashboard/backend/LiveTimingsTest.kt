package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.LineBlock
import com.obd2dashboard.backend.live.TabletFrame
import com.obd2dashboard.backend.timing.boxLap
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test

/** The live run (M17.2): sessions held from the live lane, and from the archive where it has what the lane missed. */
class LiveTimingsTest {
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = AtomicInteger()
    private var now = Instant.parse("2026-09-28T12:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
    private val timings = LiveTimings(archive, scope, clock, refillAfter = 50.milliseconds) { changes.incrementAndGet() }
    private val id = "5ace0000-1111-4111-8111-000000000017"

    /** The box, 300 s at 40 m/s: four laps, each written into the log as it ends. */
    private val log: List<JsonObject> = boxLog(0, 300).let { fixes ->
        val laps = (1..4).map { boxLap(it, version = 1) }
        // Each lap goes in after the fix it ended by, as the tablet writes it.
        val out = fixes.toMutableList()
        laps.forEachIndexed { i, lap -> out.add(out.indexOfFirst { it.contains("\"fixAt\":${12_500 + 70_000 * (i + 1) + 500}") }.coerceAtLeast(0), lap) }
        // `seq` in the order written, as a real log's.
        out.mapIndexed { i, line -> Json.parseToJsonElement(line.replace("\"id\":\"s\"", "\"id\":\"$id\"").replace(Regex("\"seq\":\\d+"), "\"seq\":$i")).jsonObject }
    }
    private val header = log.first()
    private val body = log.drop(1)

    @After
    fun stop() = scope.cancel()

    private fun laps() = runBlocking { timings.read("outback") { s -> s.flatMap { it.trace.tabletLaps.map { l -> l.lap } }.sorted() } }

    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "not within 5 s" }
            Thread.sleep(10)
        }
    }

    private fun archived(lines: List<JsonObject>) = runBlocking {
        archive.open("outback", id, lines.first().toString().toByteArray())
        val rest = lines.drop(1).joinToString("") { "$it\n" }.toByteArray()
        archive.append("outback", id, 1, (LineBlock.split(rest) as LineBlock.Split.Ok).lines)
    }

    @Test
    fun `laps are held whole however long the session, a lap sent twice once, a snapshot never`() {
        timings.offer("outback", TabletFrame.Session(id, header))
        body.chunked(10).forEach { timings.offer("outback", TabletFrame.Batch(id, it)) }
        val lap2 = body.first { it["lap"]?.toString() == "2" }
        timings.offer("outback", TabletFrame.Batch(id, listOf(lap2)))
        timings.offer("outback", TabletFrame.Snapshot(id, listOf(Json.parseToJsonElement(boxLap(9, version = 1).replace("\"seq\":1009", "\"seq\":99999")).jsonObject)))
        eventually { laps() == listOf(1, 2, 3, 4) }
        Thread.sleep(50)
        laps() shouldBe listOf(1, 2, 3, 4)
        runBlocking { timings.read("outback") { it.single().trace.allFixes.size } } shouldBe 301
    }

    @Test
    fun `a session first seen mid-drive is read from its archive, then carries on live without a lap lost or doubled`() {
        // The archive has up to the second lap's record; the live lane resumes from somewhat before it.
        val cut = log.indexOfFirst { it["lap"]?.toString() == "2" }
        archived(log.subList(0, cut + 1))
        timings.offer("outback", TabletFrame.Session(id, header))
        timings.offer("outback", TabletFrame.Batch(id, log.subList(cut - 20, log.size)))
        eventually { laps() == listOf(1, 2, 3, 4) }
        runBlocking { timings.read("outback") { it.single().trace.allFixes.size } } shouldBe 301
    }

    @Test
    fun `after a reconnect, laps sent while the link was down come from the archive`() {
        val lap3 = log.indexOfFirst { it["lap"]?.toString() == "3" }
        timings.offer("outback", TabletFrame.Session(id, header))
        timings.offer("outback", TabletFrame.Batch(id, log.subList(1, lap3 - 5)))
        eventually { laps() == listOf(1, 2) }
        // The link drops; the tablet goes on; the archive has everything; the lane resumes after lap 3.
        archived(log)
        timings.offer("outback", TabletFrame.Session(id, header))
        timings.offer("outback", TabletFrame.Batch(id, log.subList(lap3 + 5, log.size)))
        eventually { laps() == listOf(1, 2, 3, 4) }
    }

    @Test
    fun `a completed session is let go, and one not heard for 12 hours too`() {
        timings.offer("outback", TabletFrame.Session(id, header))
        timings.offer("outback", TabletFrame.Batch(id, body))
        eventually { laps().size == 4 }
        val before = changes.get()
        timings.completed("outback", id)
        eventually { laps().isEmpty() }
        changes.get() shouldBe before + 1
        timings.offer("outback", TabletFrame.Session(id, header))
        eventually { runBlocking { timings.read("outback") { it.size } } == 1 }
        now = now.plusSeconds(12 * 3600 + 1)
        timings.offer("outback", TabletFrame.Session("5ace0000-1111-4111-8111-000000000018", header))
        eventually { runBlocking { timings.read("outback") { s -> s.map { it.id } } } == listOf("5ace0000-1111-4111-8111-000000000018") }
    }
}
