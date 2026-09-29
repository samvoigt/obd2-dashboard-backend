package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/** The thinned series (M19.4). Times from W; buckets of 5 s. */
class ThinSeriesTest {
    private val W = 1_790_000_000_000L
    private var seq = 0L
    private fun rec(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun rpm(ms: Long, v: Double) = rec("""{"type":"sample","signal":"engine.rpm","value":$v,"seq":${++seq},"at":$ms,"wall":${W + ms}}""")
    private fun fix(ms: Long) = rec("""{"type":"sample","signal":"gps.position","lat":43.36,"lon":-71.46,"seq":${++seq},"at":$ms,"wall":${W + ms}}""")
    private fun mil(ms: Long, on: Boolean) = rec("""{"type":"sample","signal":"engine.mode","text":"${if (on) "sport" else "eco"}","seq":${++seq},"at":$ms,"wall":${W + ms}}""")
    private fun lap(ms: Long, n: Int) = rec("""{"type":"lap","lap":$n,"time":70.0,"seq":${++seq},"at":$ms,"wall":${W + ms}}""")

    private fun written(thin: ThinSeries, final: Boolean = false): JsonObject {
        val out = ByteArrayOutputStream()
        thin.write(out, W, emptyList(), final)
        return Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
    }

    private fun rpmPoints(s: JsonObject): List<Pair<Long, Double>> {
        val n = s["numbers"]!!.jsonObject["engine.rpm"]!!.jsonObject
        val t = n["t"]!!.jsonArray.map { it.jsonPrimitive.long }
        val v = n["v"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        return t.zip(v)
    }

    @Test
    fun `each bucket keeps its minimum and maximum, in time order, and the newest seq counts`() {
        val thin = ThinSeries()
        // Bucket 0-5 s: 3000, a dip to 1000 at 2 s, a peak of 7000 at 4 s, 4000. Bucket 5-10 s: 5000, 4000.
        listOf(0L to 3000.0, 2000L to 1000.0, 4000L to 7000.0, 4500L to 4000.0, 5000L to 5000.0, 9000L to 4000.0).forEach { (ms, v) -> thin.record(rpm(ms, v)) }
        thin.record(lap(9500, 1))
        thin.record(fix(9600))
        val s = written(thin)
        rpmPoints(s) shouldBe listOf(2000L to 1000.0, 4000L to 7000.0) // the latest bucket (5-10 s) still open
        s["lastSeq"]!!.jsonPrimitive.long shouldBe seq // the newest line, though most weren't placed
        s["events"]!!.jsonObject["lap"]!!.jsonArray.size shouldBe 1
        rpmPoints(written(thin, final = true)) shouldBe listOf(2000L to 1000.0, 4000L to 7000.0, 5000L to 5000.0, 9000L to 4000.0)
    }

    @Test
    fun `a position a second, and a state's every change`() {
        val thin = ThinSeries()
        (0L until 3000L step 250).forEach { thin.record(fix(it)) }
        thin.record(mil(100, true)); thin.record(mil(200, true)); thin.record(mil(300, false))
        val s = written(thin, final = true)
        s["positions"]!!.jsonObject["t"]!!.jsonArray.map { it.jsonPrimitive.long } shouldBe listOf(0L, 1000L, 2000L)
        s["states"]!!.jsonObject["engine.mode"]!!.jsonArray.size shouldBe 2 // sport, then eco
    }

    @Test
    fun `built as it grows, it says what one pass says`() {
        val records = (0L until 60_000L step 100).map { rpm(it, 3000 + 2000 * kotlin.math.sin(it / 7000.0)) } + lap(59_000, 1)
        val whole = ThinSeries().also { t -> records.forEach(t::record) }
        val grown = ThinSeries()
        records.chunked(37).forEach { part -> part.forEach(grown::record); written(grown) }
        written(grown, final = true) shouldBe written(whole, final = true)
    }
}
