package com.obd2dashboard.backend.replay

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SegmentStore
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
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The replay against the real server module, over real HTTP on a free port, with in-memory stores. */
class ReplayTest {
    /** A replay stuck in a loop must fail, not hang the build. */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val registry = CarRegistry(InMemoryCarStore())
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val outback = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }.token
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val work: Path = Files.createTempDirectory("replay-test")
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val device = "00000000-0000-4000-8000-00000000d0e5"

    private fun start(segments: SegmentStore = store): URI {
        val s = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(registry, ArchiveService(index, segments)) }.start()
        server = s
        val port = runBlocking { s.engine.resolvedConnectors().first().port }
        return URI.create("http://127.0.0.1:$port")
    }

    @After
    fun stop() {
        server?.stop(100, 1000)
        work.toFile().deleteRecursively()
    }

    private fun resource(name: String): Path =
        Path.of(ReplayTest::class.java.getResource("/$name")!!.toURI())

    private fun load(name: String, token: String = yaris) = SessionFile.load(resource(name), token, device)

    private val logs = mutableListOf<String>()

    private fun replayer(uri: URI, token: String = yaris, options: ReplayOptions = ReplayOptions(waitScale = 0.0)) =
        Replayer(uri, token, work.resolve(token.takeLast(6)), options, log = { synchronized(logs) { logs += it } })

    /** The first line of each chunk sent, in order. */
    private fun chunkStarts(): List<Long> =
        logs.mapNotNull { Regex("^chunk (\\d+)\\.\\.").find(it)?.groupValues?.get(1)?.toLong() }

    /** A v3 file of [lines] samples, each about [pad] bytes: for backlogs over 1 MiB. */
    private fun bigFile(lines: Int, pad: Int): SessionFile {
        val id = "11111111-1111-4111-8111-111111111111"
        val header = """{"type":"session","v":3,"id":"$id","started":"2026-09-26T12:00:00Z","seq":0,"at":0}"""
        val body = (1..lines).map { """{"type":"sample","signal":"engine.rpm","value":$it,"pad":"${"x".repeat(pad)}","seq":$it,"at":$it}""" }
        return SessionFile((listOf(header) + body).map { it.toByteArray() }, id)
    }

    private fun storedBytes(id: String): ByteArray = store.objects.getValue(ArchiveService.sessionKey(id))

    @Test
    fun `a v1 log is upgraded, uploaded in 2-minute chunks, and stored byte for byte`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v1.jsonl")
        val result = replayer(uri).replay(file)

        result.shouldBeInstanceOf<ReplayResult.Completed>().lines shouldBe 701
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
        index.get(file.id)!!.sha256 shouldBe file.sha256()
        // ~11.7 minutes of log time in 2-minute chunks is six chunks.
        chunkStarts().size shouldBe 6
        index.get(file.id)!!.hashResets shouldBe 0
        index.get(file.id)!!.car shouldBe "yaris"
    }

    @Test
    fun `a gzipped v2 log uploads whole`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v2.jsonl.gz")
        replayer(uri).replay(file).shouldBeInstanceOf<ReplayResult.Completed>().lines shouldBe 4001
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `with lost answers and duplicated chunks it still stores byte for byte`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v2.jsonl.gz")
        val options = ReplayOptions(chunkMillis = null, chunkLines = 97, loseResponses = 0.3, duplicate = 0.3, waitScale = 0.0, random = Random(3))
        replayer(uri, options = options).replay(file).shouldBeInstanceOf<ReplayResult.Completed>()
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `stopped part-way, a later run resumes and completes`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v2.jsonl.gz")
        val first = replayer(uri, options = ReplayOptions(stopAfter = 3, waitScale = 0.0)).replay(file)
        val stoppedAt = first.shouldBeInstanceOf<ReplayResult.Stopped>().ackedThrough
        index.get(file.id)!!.ackedThrough shouldBe stoppedAt
        index.get(file.id)!!.complete shouldBe false

        logs.clear()
        replayer(uri).replay(load("synthetic-v2.jsonl.gz")).shouldBeInstanceOf<ReplayResult.Completed>()
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
        // It resumed from its saved position rather than restarting at line 1.
        chunkStarts().first() shouldBe stoppedAt + 1
    }

    @Test
    fun `a tablet that lost its position trusts the server's ackedThrough and skips ahead`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v2.jsonl.gz")
        val stoppedAt = replayer(uri, options = ReplayOptions(stopAfter = 4, waitScale = 0.0)).replay(file)
            .shouldBeInstanceOf<ReplayResult.Stopped>().ackedThrough
        Files.delete(work.resolve(yaris.takeLast(6)).resolve("${file.id}.state.json"))

        logs.clear()
        replayer(uri).replay(load("synthetic-v2.jsonl.gz")).shouldBeInstanceOf<ReplayResult.Completed>()
        chunkStarts().take(2) shouldBe listOf(1L, stoppedAt + 1)
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `a server that lost the session is re-opened and sent everything again`() = runBlocking<Unit> {
        val uri = start()
        val file = load("synthetic-v2.jsonl.gz")
        replayer(uri, options = ReplayOptions(stopAfter = 3, waitScale = 0.0)).replay(file)
        // Mid-run, on the next chunk, the server loses the session entirely.
        index.beforeAppend = { index.delete(file.id); store.objects.clear() }

        logs.clear()
        replayer(uri).replay(load("synthetic-v2.jsonl.gz")).shouldBeInstanceOf<ReplayResult.Completed>()
        logs.any { it.startsWith("404") } shouldBe true
        // The fresh session answers 409 from line 1, and the very next chunk starts there.
        val afterConflict = logs.dropWhile { !it.startsWith("409") }.drop(1).first { it.startsWith("chunk") }
        afterConflict shouldBe afterConflict.replaceFirst(Regex("^chunk \\d+"), "chunk 1")
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `a backlog over 1 MiB is split, so the server never says 413`() = runBlocking<Unit> {
        val uri = start()
        val file = bigFile(lines = 12_000, pad = 100) // about 1.7 MB
        replayer(uri, options = ReplayOptions(chunkMillis = null, chunkLines = 1_000_000, waitScale = 0.0))
            .replay(file).shouldBeInstanceOf<ReplayResult.Completed>()
        logs.none { it.startsWith("413") } shouldBe true
        (chunkStarts().size > 1) shouldBe true
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `a 413 halves the chunk until it fits`() = runBlocking<Unit> {
        val uri = start()
        val file = bigFile(lines = 12_000, pad = 100)
        val options = ReplayOptions(chunkMillis = null, chunkLines = 1_000_000, maxChunkBytes = 4 shl 20, waitScale = 0.0)
        replayer(uri, options = options).replay(file).shouldBeInstanceOf<ReplayResult.Completed>()
        logs.any { it.startsWith("413") } shouldBe true
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `two cars at once each end as their own car's session`() = runBlocking<Unit> {
        val uri = start()
        val a = load("synthetic-v1.jsonl", yaris)
        val b = load("synthetic-v1.jsonl", outback) // the same log on another car is another session
        (a.id == b.id) shouldBe false
        coroutineScope {
            listOf(async { replayer(uri, yaris).replay(a) }, async { replayer(uri, outback).replay(b) }).awaitAll()
        }.forEach { it.shouldBeInstanceOf<ReplayResult.Completed>() }
        index.get(a.id)!!.car shouldBe "yaris"
        index.get(b.id)!!.car shouldBe "outback"
        storedBytes(a.id).contentEquals(a.bytes()) shouldBe true
        storedBytes(b.id).contentEquals(b.bytes()) shouldBe true
    }

    @Test
    fun `a wrong token stops with the refusal explained`() = runBlocking<Unit> {
        val uri = start()
        val result = replayer(uri, token = "obd2_" + "A".repeat(43)).replay(load("synthetic-v1.jsonl"))
        result.shouldBeInstanceOf<ReplayResult.Failed>().reason shouldContain "token was refused"
    }

    @Test
    fun `storage outages are waited out, as the tablet does`() = runBlocking<Unit> {
        var failures = 3
        val flaky = object : SegmentStore by store {
            override suspend fun put(key: String, lines: ByteArray) {
                if (key.contains("segments/0000000001") && failures-- > 0) throw IOException("storage unreachable")
                store.put(key, lines)
            }
        }
        val uri = start(flaky)
        val file = load("synthetic-v1.jsonl")
        replayer(uri).replay(file).shouldBeInstanceOf<ReplayResult.Completed>()
        storedBytes(file.id).contentEquals(file.bytes()) shouldBe true
    }

    @Test
    fun `the upgrade writes a v3 record and keeps every other line`() {
        val source = Files.readAllBytes(resource("synthetic-v1.jsonl")).decodeToString().split('\n').dropLast(1)
        val file = load("synthetic-v1.jsonl")
        val header = Json.parseToJsonElement(file.lines[0].decodeToString()).jsonObject
        header.getValue("v").jsonPrimitive.content shouldBe "3"
        header.getValue("id").jsonPrimitive.content shouldBe file.id
        header.getValue("device").jsonPrimitive.content shouldBe device
        header.getValue("seq").jsonPrimitive.content shouldBe "0"
        header.getValue("started").jsonPrimitive.content shouldBe "2026-09-09T10:50:59.876517Z"
        header.getValue("signals").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("kind").jsonPrimitive.content
        } shouldContainExactly listOf(
            "diagnostics.mil" to "flag",
            "diagnostics.monitors_complete" to "flags",
            "engine.coolant_temperature" to "number",
            "engine.rpm" to "number",
            "fuel.system_1_status" to "state",
            "vehicle.speed" to "number",
        )
        file.lines.drop(1).map { it.decodeToString() } shouldBe source.drop(1)
    }

    @Test
    fun `a cut-short last line is dropped, as the tablet drops it`() {
        val path = work.resolve("partial.jsonl")
        Files.createDirectories(work)
        Files.write(path, Files.readAllBytes(resource("synthetic-v1.jsonl")) + "{\"type\":\"sam".toByteArray())
        SessionFile.load(path, yaris, device).lines.size shouldBe 701
    }

    @Test
    fun `the same log on the same token is the same session, so a rerun resumes it`() {
        load("synthetic-v1.jsonl").id shouldBe load("synthetic-v1.jsonl").id
    }
}
