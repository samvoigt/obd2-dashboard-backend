package com.obd2dashboard.backend.archive

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class ArchiveServiceTest {
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val id = Fixtures.SESSION_ID
    private val car = "yaris"

    /** The fixture's lines, without their newlines. */
    private val lines: List<ByteArray> =
        (LineBlock.split(Fixtures.session) as LineBlock.Split.Ok).lines.let { b -> (0 until b.size).map { b.line(it) } }
    private val lastIndex = lines.size - 1L

    private fun block(from: Int, count: Int): LineBlock {
        val body = lines.subList(from, from + count).fold(ByteArray(0)) { acc, l -> acc + l + '\n'.code.toByte() }
        return (LineBlock.split(body) as LineBlock.Split.Ok).lines
    }

    private suspend fun openFixture() = archive.open(car, id, lines[0])

    private suspend fun append(from: Int, count: Int, asCar: String = car) =
        archive.append(asCar, id, from.toLong(), block(from, count))

    private suspend fun completeFixture(sha: String = Fixtures.SESSION_SHA256) =
        archive.complete(car, id, lastIndex, lastIndex + 1, sha)

    private fun storedSession(): ByteArray = store.objects.getValue(ArchiveService.sessionKey(id))

    @Test
    fun `a session in random chunk sizes completes byte for byte`() = runTest {
        val random = Random(7)
        repeat(20) {
            index.delete(id); store.objects.clear()
            openFixture() shouldBe ArchiveService.Open.Created(0)
            var next = 1
            while (next <= lastIndex) {
                val n = minOf(random.nextInt(1, 9), lines.size - next)
                append(next, n) shouldBe ArchiveService.Append.Acked(next + n - 1L)
                next += n
            }
            completeFixture() shouldBe ArchiveService.Complete.Done
            storedSession().contentEquals(Fixtures.session) shouldBe true
            // Only the session remains: its segments, and any orphans, are gone.
            store.list("sessions/$id/") shouldBe listOf(ArchiveService.sessionKey(id))
            index.get(id)!!.segments.shouldBeEmpty()
        }
    }

    @Test
    fun `line 0 may come with or without its newline, but only one line`() = runTest {
        archive.open(car, id, lines[0] + '\n'.code.toByte()) shouldBe ArchiveService.Open.Created(0)
        archive.open(car, id, lines[0]) shouldBe ArchiveService.Open.Existing(0)
        index.delete(id)
        archive.open(car, id, lines[0] + '\n'.code.toByte() + "{}".toByteArray())
            .shouldBeInstanceOf<ArchiveService.Open.BadRecord>().reason shouldContain "single line"
    }

    @Test
    fun `a repeated PUT is Existing with what is stored, and a different one is refused`() = runTest {
        openFixture()
        append(1, 10)
        openFixture() shouldBe ArchiveService.Open.Existing(10)
        val altered = lines[0].decodeToString().replace("\"app\":\"1.0 (42)\"", "\"app\":\"1.1\"").toByteArray()
        archive.open(car, id, altered).shouldBeInstanceOf<ArchiveService.Open.BadRecord>().reason shouldContain "differs"
    }

    @Test
    fun `duplicates and overlaps answer truthfully and store nothing twice`() = runTest {
        openFixture()
        append(1, 10) shouldBe ArchiveService.Append.Acked(10)
        val before = store.objects.keys.toSet()

        append(1, 10) shouldBe ArchiveService.Append.Acked(10) // a resend after a lost response
        append(5, 3) shouldBe ArchiveService.Append.Acked(10) // wholly inside
        store.objects.keys shouldBe before

        append(8, 6) shouldBe ArchiveService.Append.Acked(13) // overlap: only 11..13 are new
        store.objects.getValue(ArchiveService.segmentKey(id, 11, 13)).contentEquals(block(11, 3).bytesFrom(0)) shouldBe true
    }

    @Test
    fun `a gap is a 409 and writes nothing`() = runTest {
        openFixture()
        append(1, 5)
        val before = store.objects.keys.toSet()
        append(7, 3) shouldBe ArchiveService.Append.Gap(6)
        store.objects.keys shouldBe before
        index.get(id)!!.ackedThrough shouldBe 5
    }

    @Test
    fun `a chunk for a session never opened is NotOpen`() = runTest {
        append(1, 3) shouldBe ArchiveService.Append.NotOpen
        archive.complete(car, id, 0, 1, "x") shouldBe ArchiveService.Complete.NotOpen
    }

    @Test
    fun `another car's token is WrongCar on every operation`() = runTest {
        openFixture()
        archive.open("outback", id, lines[0]) shouldBe ArchiveService.Open.WrongCar
        append(1, 3, asCar = "outback") shouldBe ArchiveService.Append.WrongCar
        archive.complete("outback", id, lastIndex, lastIndex + 1, Fixtures.SESSION_SHA256) shouldBe
            ArchiveService.Complete.WrongCar
        index.get(id)!!.ackedThrough shouldBe 0
    }

    @Test
    fun `a line that is not a JSON object is a bad record, naming its index`() = runTest {
        openFixture()
        val body = lines[1] + '\n'.code.toByte() + "not json\n".toByteArray()
        archive.append(car, id, 1, (LineBlock.split(body) as LineBlock.Split.Ok).lines)
            .shouldBeInstanceOf<ArchiveService.Append.BadRecord>().reason shouldContain "line 2"
        index.get(id)!!.ackedThrough shouldBe 0
    }

    @Test
    fun `two racing appends of the same range, one recorded, both answers true`() = runTest {
        openFixture()
        var rival: ArchiveService.Append? = null
        // The rival stores and records lines 1..5 after our object is written, before we record ours.
        store.afterPut = { rival = append(1, 5) }

        val ours = append(1, 8)

        rival shouldBe ArchiveService.Append.Acked(5)
        ours shouldBe ArchiveService.Append.Acked(5) // we lost; the truth is 5, not our 8
        index.get(id)!!.segments.map { it.first to it.last } shouldBe listOf(0L to 0L, 1L to 5L)
        store.objects.keys.contains(ArchiveService.segmentKey(id, 1, 8)) shouldBe true // our orphan

        append(6, lines.size - 6) shouldBe ArchiveService.Append.Acked(lastIndex)
        completeFixture() shouldBe ArchiveService.Complete.Done
        storedSession().contentEquals(Fixtures.session) shouldBe true
        store.objects.keys.contains(ArchiveService.segmentKey(id, 1, 8)) shouldBe false // swept on completion
    }

    @Test
    fun `a failure between storing and recording leaves nothing acked, and a resend recovers`() = runTest {
        openFixture()
        index.failNextAppend = true
        shouldThrow<IllegalStateException> { append(1, 10) }
        index.get(id)!!.ackedThrough shouldBe 0

        append(1, lines.size - 1) shouldBe ArchiveService.Append.Acked(lastIndex)
        completeFixture() shouldBe ArchiveService.Complete.Done
        storedSession().contentEquals(Fixtures.session) shouldBe true
    }

    @Test
    fun `a storage failure acks nothing, so the lines are never claimed durable`() = runTest {
        openFixture()
        store.failNextPut = true
        shouldThrow<IllegalStateException> { append(1, 10) }
        index.get(id)!!.ackedThrough shouldBe 0
        append(1, 10) shouldBe ArchiveService.Append.Acked(10)
    }

    @Test
    fun `a corrupt index with a hole in its segments refuses to assemble`() = runTest {
        openFixture()
        append(1, 10)
        append(11, lines.size - 11)
        // Corrupt it: drop the 1..10 segment while ackedThrough still claims everything.
        val record = index.get(id)!!
        index.delete(id)
        index.create(record.copy(segments = record.segments.filter { it.first != 1L }))

        shouldThrow<IllegalStateException> { completeFixture() }.message shouldContain "not contiguous"
        store.objects.containsKey(ArchiveService.sessionKey(id)) shouldBe false
        index.get(id)!!.complete shouldBe false
    }

    @Test
    fun `a segment holding the wrong number of lines refuses to assemble`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        val key = index.get(id)!!.segments.last().key
        store.objects[key] = store.objects.getValue(key).dropLast(1).toByteArray().let { b ->
            b.copyOf(b.lastIndexOf('\n'.code.toByte()) + 1) // one line short, still newline-terminated
        }
        shouldThrow<IllegalStateException> { completeFixture() }.message shouldContain "does not hold"
        index.get(id)!!.complete shouldBe false
    }

    @Test
    fun `complete before every line is stored is a gap`() = runTest {
        openFixture()
        append(1, 20)
        completeFixture() shouldBe ArchiveService.Complete.Gap(21)
    }

    @Test
    fun `complete with more lines stored than claimed is a bad record`() = runTest {
        openFixture()
        append(1, 20)
        archive.complete(car, id, 10, 11, "x").shouldBeInstanceOf<ArchiveService.Complete.BadRecord>()
        archive.complete(car, id, 20, 20, "x").shouldBeInstanceOf<ArchiveService.Complete.BadRecord>().reason shouldContain
            "recordCount"
    }

    @Test
    fun `a hash mismatch resets to line 0 and the resend completes`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture(sha = "0".repeat(64)) shouldBe ArchiveService.Complete.Gap(1)
        index.get(id)!!.let {
            it.ackedThrough shouldBe 0
            it.hashResets shouldBe 1
        }
        store.objects.containsKey(ArchiveService.sessionKey(id)) shouldBe false

        append(1, lines.size - 1) shouldBe ArchiveService.Append.Acked(lastIndex)
        completeFixture() shouldBe ArchiveService.Complete.Done
        storedSession().contentEquals(Fixtures.session) shouldBe true
    }

    @Test
    fun `after two resets a mismatch is a bad record, not a loop`() = runTest {
        openFixture()
        repeat(ArchiveService.MAX_HASH_RESETS) {
            append(1, lines.size - 1)
            completeFixture(sha = "0".repeat(64)) shouldBe ArchiveService.Complete.Gap(1)
        }
        append(1, lines.size - 1)
        completeFixture(sha = "0".repeat(64)).shouldBeInstanceOf<ArchiveService.Complete.BadRecord>()
    }

    @Test
    fun `complete is idempotent, and a complete session takes no new lines`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture() shouldBe ArchiveService.Complete.Done
        completeFixture() shouldBe ArchiveService.Complete.AlreadyDone
        completeFixture(sha = "0".repeat(64)).shouldBeInstanceOf<ArchiveService.Complete.BadRecord>()

        append(1, 5) shouldBe ArchiveService.Append.Acked(lastIndex) // a late resend: already stored
        val extra = (LineBlock.split("{\"type\":\"sample\"}\n".toByteArray()) as LineBlock.Split.Ok).lines
        archive.append(car, id, lastIndex + 1, extra).shouldBeInstanceOf<ArchiveService.Append.BadRecord>()
        openFixture() shouldBe ArchiveService.Open.Existing(lastIndex)
    }

    @Test
    fun `a session the live lane created is opened by its PUT`() = runTest {
        val now = Instant.parse("2026-09-26T12:00:00Z")
        index.create(SessionRecord(id, car, null, null, -1, emptyList(), false, null, 0, now, now))
        append(1, 3) shouldBe ArchiveService.Append.NotOpen
        archive.complete(car, id, 0, 1, "x") shouldBe ArchiveService.Complete.NotOpen

        openFixture() shouldBe ArchiveService.Open.Created(0)
        index.get(id)!!.header!!.started shouldBe "2026-09-24T13:08:32.623Z"
        append(1, lines.size - 1) shouldBe ArchiveService.Append.Acked(lastIndex)
        completeFixture() shouldBe ArchiveService.Complete.Done
    }

    @Test
    fun `a live announcement creates the session, the same car again is fine, another car is not`() = runTest {
        archive.announce(car, id) shouldBe ArchiveService.Announce.Ok
        index.get(id)!!.let {
            it.ackedThrough shouldBe -1
            it.header shouldBe null
        }
        archive.announce(car, id) shouldBe ArchiveService.Announce.Ok
        archive.announce("outback", id) shouldBe ArchiveService.Announce.WrongCar
        openFixture() shouldBe ArchiveService.Open.Created(0) // the PUT then supplies line 0
        archive.announce(car, id) shouldBe ArchiveService.Announce.Ok // and a reconnect's announcement changes nothing
        index.get(id)!!.ackedThrough shouldBe 0
    }

    @Test
    fun `segments carry the seq of their first and last lines`() = runTest {
        openFixture()
        append(1, 5)
        val segment = index.get(id)!!.segments.last()
        segment.firstSeq shouldBe 1
        segment.lastSeq shouldBe 5
        index.get(id)!!.segments.first().firstSeq shouldBe 0 // the session record's seq
    }

    @Test
    fun `delete removes every object and the index entry`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture()
        archive.delete(id) shouldBe true
        store.list("sessions/$id/").shouldBeEmpty()
        index.get(id) shouldBe null
        index.list().shouldHaveSize(0)
    }

    // Summaries (M7.1)

    @Test
    fun `a complete session's summary is built once and stored`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture() shouldBe ArchiveService.Complete.Done
        index.get(id)!!.summary.shouldBeNull() // not built by complete itself: the route does it after answering
        val summary = archive.summary(id)!!
        summary.lines shouldBe Fixtures.SESSION_LINES.toLong()
        index.get(id)!!.summary shouldBe summary
        store.objects.remove(ArchiveService.sessionKey(id)) // a second call must not read the log again
        archive.summary(id) shouldBe summary
    }

    @Test
    fun `a session still uploading has no summary, and an unknown one none either`() = runTest {
        openFixture()
        append(1, 10)
        archive.summary(id).shouldBeNull()
        index.get(id)!!.summary.shouldBeNull()
        archive.summary("00000000-0000-4000-8000-000000000000").shouldBeNull()
    }

    @Test
    fun `an older summary is rebuilt`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture()
        val current = archive.summary(id)!!
        index.setSummary(id, current.copy(version = SessionSummary.VERSION - 1, lines = 1))
        archive.summary(id) shouldBe current
    }

    @Test
    fun `reading a session streams its segments in order, up to what is acked`() = runTest {
        openFixture()
        append(1, 20)
        append(21, 10)
        val read = java.io.ByteArrayOutputStream()
        archive.read(index.get(id)!!) { it.copyTo(read) }
        val expected = lines.take(31).fold(ByteArray(0)) { acc, l -> acc + l + '\n'.code.toByte() }
        read.toByteArray().contentEquals(expected) shouldBe true
    }

    @Test
    fun `the summary survives the record being rewritten`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture()
        val summary = archive.summary(id)!!
        completeFixture() shouldBe ArchiveService.Complete.AlreadyDone // idempotent, and it must not drop the summary
        index.get(id)!!.summary shouldBe summary
    }

    // The prepared series (M7.2)

    private fun seriesKeys() = store.objects.keys.filter { it.startsWith(ArchiveService.seriesPrefix(id)) }.sorted()

    @Test
    fun `a complete session is prepared once, with its summary`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture()
        val key = archive.prepare(id)
        key shouldBe ArchiveService.seriesKey(id, null)
        key shouldBe "sessions/$id/series-v2.json.gz"
        index.get(id)!!.summary!!.lines shouldBe Fixtures.SESSION_LINES.toLong()
        val first = store.objects.getValue(key!!)
        store.objects.remove(ArchiveService.sessionKey(id)) // a second call must not read the log again
        archive.prepare(id) shouldBe key
        store.objects.getValue(key).contentEquals(first) shouldBe true
    }

    @Test
    fun `a session uploading is prepared per ackedThrough, the older one deleted, and all go once complete`() = runTest {
        openFixture()
        append(1, 10)
        archive.prepare(id) shouldBe ArchiveService.seriesKey(id, 10)
        index.get(id)!!.summary.shouldBeNull() // a moving session gets no stored summary
        append(11, 10)
        archive.prepare(id) shouldBe ArchiveService.seriesKey(id, 20)
        seriesKeys() shouldBe listOf(ArchiveService.seriesKey(id, 20))
        append(21, lines.size - 21)
        completeFixture()
        archive.prepare(id) shouldBe ArchiveService.seriesKey(id, null)
        seriesKeys() shouldBe listOf(ArchiveService.seriesKey(id, null))
    }

    @Test
    fun `an older version's file is replaced, and nothing is prepared without lines`() = runTest {
        archive.prepare(id).shouldBeNull()
        index.create(SessionRecord(id, car, null, null, -1, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
        archive.prepare(id).shouldBeNull() // created by the live lane: no lines yet
        index.delete(id)
        openFixture()
        append(1, lines.size - 1)
        completeFixture()
        store.objects["sessions/$id/series-v0.json.gz"] = ByteArray(1)
        archive.prepare(id)
        seriesKeys() shouldBe listOf(ArchiveService.seriesKey(id, null))
    }

    @Test
    fun `deleting a session deletes its prepared series too`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture()
        archive.prepare(id)
        archive.delete(id)
        seriesKeys() shouldBe emptyList()
    }

    @Test
    fun `a complete sent again answers the same, and does nothing again`() = runTest {
        openFixture()
        append(1, lines.size - 1)
        completeFixture() shouldBe ArchiveService.Complete.Done
        val record = index.get(id)
        val writes = store.writes
        completeFixture() shouldBe ArchiveService.Complete.AlreadyDone
        store.writes shouldBe writes
        index.get(id) shouldBe record
        storedSession().contentEquals(Fixtures.session) shouldBe true
    }

    @Test
    fun `two completes at once, one assembles and the other waits and finds it done`() = runTest {
        openFixture()
        var next = 1
        while (next <= lastIndex) {
            val n = minOf(3, lines.size - next)
            append(next, n)
            next += n
        }
        store.beforeRead = { yield() } // the rival runs between every segment read
        val both = listOf(async { completeFixture() }, async { completeFixture() }).awaitAll()
        both.toSet() shouldBe setOf(ArchiveService.Complete.Done, ArchiveService.Complete.AlreadyDone)
        store.writes shouldBe 1
        storedSession().contentEquals(Fixtures.session) shouldBe true
    }
}
