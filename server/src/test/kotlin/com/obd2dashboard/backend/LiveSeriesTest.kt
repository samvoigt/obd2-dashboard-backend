package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.LineBlock
import com.obd2dashboard.backend.archive.SessionReader
import com.obd2dashboard.backend.archive.ThinSeries
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/** A session still uploading as a thinned series (M19.4): built as it grows, one build shared. */
class LiveSeriesTest {
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private var now = Instant.parse("2026-09-29T12:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
    private val live = LiveSeries(archive, clock)
    private val id = "5ace0000-1111-4111-8111-000000000194"
    private val W = 1_790_000_000_000L
    private val lines: List<String> =
        listOf("""{"type":"session","v":3,"id":"$id","started":"2026-09-29T12:00:00Z","signals":[{"name":"engine.rpm","unit":"rpm","kind":"number"}],"seq":0,"at":0,"wall":$W}""") +
            (1..600).map { """{"type":"sample","signal":"engine.rpm","value":${3000 + it % 50},"seq":$it,"at":${it * 100},"wall":${W + it * 100}}""" }

    private suspend fun upload(from: Int, to: Int) {
        if (from == 0) archive.open("outback", id, lines[0].toByteArray())
        val start = maxOf(from, 1)
        for (i in start until to step 50) {
            val part = lines.subList(i, minOf(i + 50, to)).joinToString("") { "$it\n" }.toByteArray()
            archive.append("outback", id, i.toLong(), (LineBlock.split(part) as LineBlock.Split.Ok).lines)
        }
    }

    private fun unzip(gz: ByteArray) = GZIPInputStream(gz.inputStream()).readBytes().decodeToString()

    private fun onePass(to: Int): String {
        val thin = ThinSeries()
        val reader = SessionReader(also = thin::record)
        lines.take(to).forEach { reader.line(it.toByteArray()) }
        val out = ByteArrayOutputStream()
        thin.write(out, reader.summary().started, reader.summary().signals)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `built as it grows, reading only what's new, the same as one pass`() = runTest {
        upload(0, 301) // line 0 and six chunks
        val first = live.series(index.get(id)!!)
        first.first shouldBe 300
        unzip(first.second) shouldBe onePass(301)
        val read = store.streamed
        upload(301, 401) // two more chunks
        val second = live.series(index.get(id)!!)
        store.streamed - read shouldBe 2 // only the new ones
        unzip(second.second) shouldBe onePass(401)
        // Asked again with nothing new: the same answer, nothing read.
        val again = store.streamed
        live.series(index.get(id)!!) shouldBe second
        store.streamed shouldBe again
    }

    @Test
    fun `viewers asking at once share one build`() = runTest {
        upload(0, 601)
        val read = store.streamed
        val record = index.get(id)!!
        store.beforeRead = { yield() }
        val answers = (1..5).map { async { live.series(record) } }.awaitAll()
        answers.map { it.first }.toSet() shouldBe setOf(600L)
        store.streamed - read shouldBe record.segments.size // read once, for all five
    }

    @Test
    fun `let go once complete, or half an hour after it was last asked for`() = runTest {
        upload(0, 101)
        live.series(index.get(id)!!)
        live.held() shouldBe 1
        live.completed(id)
        live.held() shouldBe 0
        live.series(index.get(id)!!)
        now = now.plus(Duration.ofMinutes(31))
        live.series(index.get(id)!!.copy(id = "5ace0000-1111-4111-8111-000000000195"))
        live.held() shouldBe 1 // the idle one gone, the new one held
    }
}
