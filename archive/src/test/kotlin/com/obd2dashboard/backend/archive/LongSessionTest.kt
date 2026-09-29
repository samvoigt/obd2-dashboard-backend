package com.obd2dashboard.backend.archive

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Long sessions (M19.2, M19.3): pieces composed as it uploads, and `complete` answered by the running hash. */
class LongSessionTest {
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val id = Fixtures.SESSION_ID
    private val car = "yaris"

    /** Line 0 of the fixture, then [n] samples: one chunk a line, as the tablet's many small ones. */
    private val lines: List<ByteArray> by lazy {
        val line0 = Fixtures.session.decodeToString().lineSequence().first()
        listOf(line0.toByteArray()) + (1..200).map { """{"type":"sample","signal":"engine.rpm","value":$it,"seq":$it,"at":$it}""".toByteArray() }
    }
    private val log: ByteArray by lazy { lines.fold(ByteArrayOutputStream()) { o, l -> o.write(l); o.write('\n'.code); o }.toByteArray() }
    private val sha: String by lazy { MessageDigest.getInstance("SHA-256").digest(log).joinToString("") { "%02x".format(it) } }

    private fun block(from: Int, count: Int) =
        (LineBlock.split(lines.subList(from, from + count).fold(ByteArray(0)) { a, l -> a + l + '\n'.code.toByte() }) as LineBlock.Split.Ok).lines

    private suspend fun upload(to: Int = lines.size, compact: Boolean = true) {
        if (index.get(id) == null) archive.open(car, id, lines[0])
        for (i in (index.get(id)!!.ackedThrough + 1).toInt() until to) {
            archive.append(car, id, i.toLong(), block(i, 1))
            if (compact) archive.compact(id)
        }
    }

    private suspend fun readBack(record: SessionRecord? = null): ByteArray =
        ByteArrayOutputStream().also { out -> archive.read(record ?: index.get(id)!!) { it.copyTo(out) } }.toByteArray()

    @Test
    fun `a session compacted as it uploads stays a few objects and reads back byte for byte`() = runTest {
        upload()
        val record = index.get(id)!!
        record.segments.size shouldBeLessThanOrEqual 33 // 200 chunks: line 0, one piece, fewer than 32 after it
        store.composes shouldBe 6 // every 31 chunks after the first 32
        readBack().contentEquals(log) shouldBe true
    }

    @Test
    fun `what a compaction replaces is deleted by the next, so objects stay few (M19_4)`() = runTest {
        upload()
        // Line 0, the piece, the chunks after it, and only the last compaction's replaced 32, not every piece before.
        val listed = index.get(id)!!.segments.size
        store.list(ArchiveService.segmentsPrefix(id)).size shouldBe listed + ArchiveService.COMPACT_AT
    }

    @Test
    fun `a reader holding the list from before a compaction still reads it whole`() = runTest {
        upload(to = 60, compact = false)
        val before = index.get(id)!!
        archive.compact(id) shouldBe true
        index.get(id)!!.segments.size shouldBe 60 - 32 + 1 // line 0, the piece, and 27
        readBack(before).contentEquals(readBack()) shouldBe true // what it replaced is still there
    }

    @Test
    fun `a failed compose leaves the segments as they were`() = runTest {
        upload(to = 40, compact = false)
        val before = index.get(id)!!
        val failing = ArchiveService(index, object : SegmentStore by store {
            override suspend fun compose(target: String, sources: List<String>) = throw IllegalStateException("storage unavailable")
        })
        shouldThrow<IllegalStateException> { failing.compact(id) }
        index.get(id)!!.segments shouldBe before.segments
        readBack().size shouldBe readBack(before).size
    }

    @Test
    fun `complete compares the running hash and answers, reading nothing, and finish makes it one object`() = runTest {
        upload()
        store.beforeRead = { error("complete read the log back") }
        archive.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) shouldBe ArchiveService.Complete.Done
        store.beforeRead = null
        // Answered, not yet one object: read as its pieces.
        index.get(id)!!.let { it.complete shouldBe true; (it.segments.size > 0) shouldBe true }
        readBack().contentEquals(log) shouldBe true
        archive.unfinished() shouldBe listOf(id)
        archive.finish(id) shouldBe true
        index.get(id)!!.segments.shouldBeEmpty()
        store.objects.getValue(ArchiveService.sessionKey(id)).contentEquals(log) shouldBe true
        store.list(ArchiveService.segmentsPrefix(id)).shouldBeEmpty() // pieces and what they replaced, gone
        archive.unfinished() shouldBe emptyList()
        archive.finish(id) shouldBe false
        readBack().contentEquals(log) shouldBe true
    }

    @Test
    fun `the hash carries over a restart, and one left unfinished is finished by the next`() = runTest {
        upload(to = 100)
        val restarted = ArchiveService(index, store) // a new server: the hash's state is the index's
        for (i in 100 until lines.size) restarted.append(car, id, i.toLong(), block(i, 1))
        restarted.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) shouldBe ArchiveService.Complete.Done
        val next = ArchiveService(index, store)
        next.unfinished().forEach { next.finish(it) }
        store.objects.getValue(ArchiveService.sessionKey(id)).contentEquals(log) shouldBe true
    }

    @Test
    fun `a wrong hash starts again from line 0, the hash with it, and the resend completes`() = runTest {
        upload()
        archive.complete(car, id, lines.size - 1L, lines.size.toLong(), "0".repeat(64)) shouldBe ArchiveService.Complete.Gap(1)
        index.get(id)!!.let { it.ackedThrough shouldBe 0; it.segments.size shouldBe 1 }
        store.list(ArchiveService.segmentsPrefix(id)).size shouldBe 1 // pieces and what they replaced, gone
        upload()
        archive.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) shouldBe ArchiveService.Complete.Done
    }

    @Test
    fun `a session opened before M19 is never compacted, and completes as before`() = runTest {
        upload(to = 1, compact = false)
        val record = index.get(id)!!
        index.delete(id)
        index.create(record.copy(hashState = null))
        upload(compact = false)
        archive.compact(id) shouldBe false
        archive.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) shouldBe ArchiveService.Complete.Done
        index.get(id)!!.segments.shouldBeEmpty()
        store.objects.getValue(ArchiveService.sessionKey(id)).contentEquals(log) shouldBe true
    }

    @Test
    fun `finish composes more than 32 in rounds`() = runTest {
        upload(to = 41, compact = false) // line 0 and 40 chunks: more than one compose takes
        archive.complete(car, id, 40, 41, MessageDigest.getInstance("SHA-256").digest(log.copyOf(lineEnd(40))).joinToString("") { "%02x".format(it) }) shouldBe
            ArchiveService.Complete.Done
        archive.finish(id) shouldBe true
        store.objects.getValue(ArchiveService.sessionKey(id)).contentEquals(log.copyOf(lineEnd(40))) shouldBe true
    }

    @Test
    fun `a compaction whose segments have moved on is refused`() = runTest {
        upload(to = 5, compact = false)
        val record = index.get(id)!!
        val keys = record.segments.drop(1).map { it.key }
        val piece = Segment(1, 4, "piece", null, null)
        compacted(record, keys, piece, record.updated)!!.segments.map { it.key } shouldBe listOf(record.segments[0].key, "piece")
        compacted(record, keys + "gone", piece, record.updated) shouldBe null
        compacted(record.copy(complete = true), keys, piece, record.updated) shouldBe null
    }

    /** Where line [i] ends in the log, its newline included. */
    private fun lineEnd(i: Int): Int = lines.take(i + 1).sumOf { it.size + 1 }

    @Test
    fun `a session the live lane announced first gets its running hash from its PUT`() = runTest {
        archive.announce(car, id)
        archive.open(car, id, lines[0]) shouldBe ArchiveService.Open.Created(0) // its PUT supplies line 0
        upload()
        store.beforeRead = { error("complete read the log back") }
        archive.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) shouldBe ArchiveService.Complete.Done
    }

    @Test
    fun `compaction starts at exactly 32 chunks after line 0`() = runTest {
        upload(to = 32, compact = false) // 31 after line 0
        archive.compact(id) shouldBe false
        upload(to = 33, compact = false) // 32
        archive.compact(id) shouldBe true
        index.get(id)!!.segments.size shouldBe 2 // line 0 and the piece
    }
}

