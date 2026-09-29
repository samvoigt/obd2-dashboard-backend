package com.obd2dashboard.backend.archive

import java.lang.management.ManagementFactory
import java.util.zip.GZIPOutputStream
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measures preparing a synthetic 3-hour race (M7.2): time, peak heap, sizes.
 * Not part of the suite: run with `MEASURE=1`, and the heap capped as Cloud
 * Run's JVM is (a quarter of 512 MiB).
 */
class MeasureSeries {
    @Test
    fun `a 3-hour race`() = runBlocking<Unit> {
        assumeTrue(System.getenv("MEASURE") != null)
        val hours = (System.getenv("MEASURE_HOURS") ?: "3").toDouble()
        val t0 = 1_790_000_000_000L
        val dir = kotlin.io.path.createTempDirectory("measure").toFile().apply { deleteOnExit() }
        // A log of your own (M19.1: `scripts/synthetic-session.py`, the tablet's real rate), else the one below.
        val given = System.getenv("MEASURE_FILE")?.let { java.io.File(it) }
        val logFile = given ?: java.io.File(dir, "log.jsonl")
        val raw = if (given != null) java.io.OutputStream.nullOutputStream() else logFile.outputStream().buffered(1 shl 16)
        var seq = 0L
        var lines = 0
        fun line(s: String) { raw.write(s.toByteArray()); raw.write('\n'.code); lines++ }
        val signals = (1..50).map { "obd.signal_$it" }
        line("""{"type":"session","v":3,"id":"m","started":"2026-09-26T12:00:00Z","signals":[${(signals + "motion.acceleration.longitudinal" + "motion.acceleration.lateral" + "gps.speed").joinToString(",") { """{"name":"$it","unit":"u","kind":"number"}""" }},{"name":"gps.position","unit":"","kind":"position"}],"seq":0,"at":0,"wall":$t0}""")
        val end = if (given != null) 0L else (hours * 3600_000).toLong()
        var ms = 0L
        var lap = 0
        while (ms < end) {
            // OBD: ~1,400 lines a minute across 50 signals (a fast pair at 5.5 Hz, the rest slower).
            if (ms % 180 == 0L) {
                line("""{"type":"sample","signal":"obd.signal_1","value":${3000 + 2000 * sin(ms / 7000.0)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
                line("""{"type":"sample","signal":"obd.signal_2","value":${100 + 60 * sin(ms / 9000.0)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
            }
            if (ms % 3000 == 0L) signals.drop(2).forEachIndexed { i, s ->
                line("""{"type":"sample","signal":"$s","value":${i + sin(ms / 1000.0)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
            }
            // The G-meter: 2 signals at 10 Hz.
            if (ms % 100 == 0L) {
                line("""{"type":"sample","signal":"motion.acceleration.longitudinal","value":${3 * sin(ms / 3000.0)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
                line("""{"type":"sample","signal":"motion.acceleration.lateral","value":${9 * sin(ms / 2000.0)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
            }
            // GPS: a position and a speed each second (a real fix brings up to 6 signals).
            if (ms % 1000 == 0L) {
                line("""{"type":"sample","signal":"gps.position","lat":${43.36 + 0.004 * sin(ms / 15000.0)},"lon":${-71.46 + 0.004 * sin(ms / 15000.0 + 1)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
                line("""{"type":"sample","signal":"gps.speed","value":${120 + 40 * sin(ms / 8000.0)},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
            }
            if (ms > 0 && ms % 95_000 == 0L) {
                line("""{"type":"lap","track":"nhms","layout":"Road Course","lap":${++lap},"time":${94 + (ms % 7) / 3.0},"seq":${++seq},"at":$ms,"wall":${t0 + ms}}""")
            }
            ms += 20
        }
        raw.close()
        if (given != null) lines = given.useLines { it.count() }
        val gzFile = java.io.File(dir, "log.jsonl.gz")
        GZIPOutputStream(gzFile.outputStream()).use { out -> logFile.inputStream().use { it.copyTo(out) } }

        val index = InMemorySessionIndex()
        // Files, not memory: Cloud Storage streams, and a test store holding 55 MB would swamp the heap being measured.
        val store = object : SegmentStore {
            fun file(key: String) = java.io.File(dir, key.replace('/', '_'))
            override suspend fun put(key: String, lines: ByteArray) = file(key).writeBytes(lines)
            override suspend fun read(key: String): ByteArray = file(key).readBytes()
            override suspend fun <T> readStream(key: String, body: suspend (java.io.InputStream) -> T): T =
                (if (key.endsWith("session.jsonl.gz")) logFile else file(key)).inputStream().buffered(1 shl 16).use { body(it) }
            override suspend fun write(key: String, body: suspend (java.io.OutputStream) -> Unit) =
                GZIPOutputStream(file(key).outputStream().buffered(1 shl 16)).use { body(it) }
            override suspend fun <T> readRaw(key: String, body: suspend (java.io.InputStream) -> T): T = file(key).inputStream().use { body(it) }
            override suspend fun deletePrefix(prefix: String): Int = 0
            override suspend fun delete(key: String) { file(key).delete() }
            override suspend fun list(prefix: String): List<String> = emptyList()
        }
        val archive = ArchiveService(index, store)
        val id = "0b8f3c52-5f7e-4b7e-9c55-1d7b0a3e9f10"
        index.create(SessionRecord(id, "car", null, null, lines - 1L, emptyList(), true, null, 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH))

        val memory = ManagementFactory.getMemoryMXBean()
        System.gc()
        val before = memory.heapMemoryUsage.used
        var peak = before
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                peak = maxOf(peak, memory.heapMemoryUsage.used)
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        val started = System.nanoTime()
        val key = archive.prepare(id)!!
        val took = (System.nanoTime() - started) / 1_000_000
        sampler.interrupt()
        val seriesGz = store.file(key).length()
        val seriesRaw = java.util.zip.GZIPInputStream(store.file(key).inputStream()).use { it.readBytes().size }
        println(
            "MEASURE ${hours}h: $lines lines; log ${logFile.length() / 1_000_000} MB raw, ${gzFile.length() / 1_000} KB gz; " +
                "series ${seriesRaw / 1_000_000} MB raw, ${seriesGz / 1_000} KB gz; prepared in $took ms; " +
                "heap max ${Runtime.getRuntime().maxMemory() / 1_048_576} MiB, in use before ${before / 1_048_576} MiB, peak ${peak / 1_048_576} MiB",
        )
    }
}
