package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** A session prepared as columns, with gaps as nulls (M7.2). */
class SeriesBuilderTest {
    private val t0 = 1_790_000_000_000L

    private fun build(vararg lines: String): JsonObject {
        val builder = SeriesBuilder()
        lines.forEach { builder.record(Json.parseToJsonElement(it).jsonObject) }
        val out = ByteArrayOutputStream()
        builder.write(out, t0, listOf(SignalInfo("engine.rpm", "rpm", "number")))
        return Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject // it must be valid JSON
    }

    private fun sample(signal: String, at: Long, field: String) =
        """{"type":"sample","signal":"$signal",$field,"seq":$at,"at":$at,"wall":${t0 + at}}"""

    private fun rpm(at: Long, value: Double = 1000.0) = sample("engine.rpm", at, "\"value\":$value")

    private fun JsonObject.numbers(signal: String): Pair<List<String>, List<String>> {
        val n = getValue("numbers").jsonObject.getValue(signal).jsonObject
        fun list(key: String) = n.getValue(key).jsonArray.map { if (it is JsonNull) "null" else it.jsonPrimitive.content }
        return list("t") to list("v")
    }

    private fun JsonElement.texts(): List<String> = jsonArray.map { it.toString() }

    @Test
    fun `numbers and flags become columns, times after t0`() {
        val s = build(
            rpm(100, 900.5), rpm(300, 1200.0),
            sample("diagnostics.mil", 150, "\"flag\":false"), sample("diagnostics.mil", 350, "\"flag\":true"),
        )
        s.getValue("version").jsonPrimitive.content shouldBe "2"
        s.getValue("lastSeq").jsonPrimitive.content shouldBe "350"
        s.getValue("t0").jsonPrimitive.content shouldBe t0.toString()
        s.getValue("signals").jsonArray.single().jsonObject.getValue("unit").jsonPrimitive.content shouldBe "rpm"
        s.numbers("engine.rpm") shouldBe (listOf("100", "300") to listOf("900.5", "1200.0"))
        s.numbers("diagnostics.mil") shouldBe (listOf("150", "350") to listOf("0.0", "1.0"))
    }

    @Test
    fun `a signal breaks where its samples are over 5 times its median interval apart`() {
        // Every 200 ms, then a pause of 1.2 s: over 5 × 200 ms, so a break (a null just after the last sample).
        val s = build(rpm(0), rpm(200), rpm(400), rpm(600), rpm(1800), rpm(2000))
        s.numbers("engine.rpm") shouldBe (
            listOf("0", "200", "400", "600", "601", "1800", "2000") to
                listOf("1000.0", "1000.0", "1000.0", "1000.0", "null", "1000.0", "1000.0")
            )
        // Exactly 5 × the median is not a break.
        build(rpm(0), rpm(200), rpm(400), rpm(600), rpm(1600)).numbers("engine.rpm").second.contains("null") shouldBe false
    }

    @Test
    fun `the break is never under one second`() {
        // Samples 10 ms apart would make 50 ms a break; the floor keeps a 900 ms pause whole.
        build(rpm(0), rpm(10), rpm(20), rpm(30), rpm(930)).numbers("engine.rpm").second.contains("null") shouldBe false
        build(rpm(0), rpm(10), rpm(20), rpm(30), rpm(1031)).numbers("engine.rpm").second.contains("null") shouldBe true
    }

    /** A sample whose seq is its time, so a gap's seq places it among them. */
    private fun rpmSeq(at: Long, seq: Long) = """{"type":"sample","signal":"engine.rpm","value":1000.0,"seq":$seq,"at":$at,"wall":${t0 + at}}"""

    private fun gap(seq: Long, wall: Long) = """{"type":"gap","missed":4,"seq":$seq,"at":1,"wall":${t0 + wall}}"""

    @Test
    fun `a gap record between two samples by seq breaks the line, one elsewhere doesn't`() {
        build(rpmSeq(0, 10), rpmSeq(200, 20), rpmSeq(400, 30), gap(25, 300)).numbers("engine.rpm").second shouldBe
            listOf("1000.0", "1000.0", "null", "1000.0")
        build(rpmSeq(0, 10), rpmSeq(200, 20), rpmSeq(400, 30), gap(35, 500)).numbers("engine.rpm").second.contains("null") shouldBe false
        build(rpmSeq(0, 10), rpmSeq(200, 20), rpmSeq(400, 30), gap(5, 0)).numbers("engine.rpm").second.contains("null") shouldBe false
    }

    @Test
    fun `without seq a gap can't be placed, so it breaks nothing`() {
        val noSeq = { at: Long -> """{"type":"sample","signal":"engine.rpm","value":1000.0,"at":$at,"wall":${t0 + at}}""" }
        build(noSeq(0), noSeq(200), rpmSeq(400, 30), gap(25, 300)).numbers("engine.rpm").second.contains("null") shouldBe false
    }

    @Test
    fun `seq places a gap that shares a millisecond with a sample`() {
        // Written in the same ms as the sample at 200, but after it: the break is after 200, not before.
        build(rpmSeq(0, 10), rpmSeq(200, 20), rpmSeq(400, 30), gap(21, 200)).numbers("engine.rpm").second shouldBe
            listOf("1000.0", "1000.0", "null", "1000.0")
        build(rpmSeq(0, 10), rpmSeq(200, 20), rpmSeq(400, 30), gap(19, 200)).numbers("engine.rpm").second shouldBe
            listOf("1000.0", "null", "1000.0", "1000.0")
    }

    @Test
    fun `a number JSON can't hold is written as a break`() {
        build(rpm(0), rpm(200, 5.0), """{"type":"sample","signal":"engine.rpm","value":1e999,"seq":3,"at":400,"wall":${t0 + 400}}""")
            .numbers("engine.rpm").second shouldBe listOf("1000.0", "5.0", "null")
    }

    @Test
    fun `samples are sorted by time, since a corrected clock can step back`() {
        build(rpm(0, 1.0), rpm(200, 2.0), rpm(100, 3.0)).numbers("engine.rpm") shouldBe
            (listOf("0", "100", "200") to listOf("1.0", "3.0", "2.0"))
    }

    @Test
    fun `states and flag sets keep only their changes`() {
        val s = build(
            sample("fuel.status", 0, "\"code\":1,\"text\":\"Open loop\""),
            sample("fuel.status", 100, "\"code\":1,\"text\":\"Open loop\""),
            sample("fuel.status", 200, "\"code\":2,\"text\":\"Closed loop\""),
            sample("monitors", 0, "\"flags\":[\"Catalyst\"]"),
            sample("monitors", 100, "\"flags\":[\"Catalyst\"]"),
            sample("monitors", 200, "\"flags\":[]"),
        )
        s.getValue("states").jsonObject.getValue("fuel.status").texts() shouldBe
            listOf("""[0,1,"Open loop"]""", """[200,2,"Closed loop"]""")
        s.getValue("sets").jsonObject.getValue("monitors").texts() shouldBe listOf("""[0,["Catalyst"]]""", "[200,[]]")
    }

    @Test
    fun `positions travel together, and events keep what the page needs`() {
        val s = build(
            sample("gps.position", 0, "\"lat\":43.36,\"lon\":-71.46"),
            sample("gps.position", 1000, "\"lat\":43.37,\"lon\":-71.47"),
            """{"type":"stopped","signal":"engine.oil_temperature","reason":"it stopped answering","seq":1,"at":1,"wall":${t0 + 500}}""",
            """{"type":"fault","codes":["P0301"],"seq":2,"at":2,"wall":${t0 + 600}}""",
            """{"type":"gap","missed":12,"seq":3,"at":3,"wall":${t0 + 700}}""",
            """{"type":"lap","track":"nhms","layout":"Road Course","lap":1,"time":94.5,"pitIn":true,"seq":4,"at":4,"wall":${t0 + 800}}""",
        )
        val p = s.getValue("positions").jsonObject
        p.getValue("t").texts() shouldBe listOf("0", "1000")
        p.getValue("lat").texts() shouldBe listOf("43.36", "43.37")
        p.getValue("lon").texts() shouldBe listOf("-71.46", "-71.47")
        val e = s.getValue("events").jsonObject
        e.getValue("stopped").texts() shouldBe listOf("""[500,"engine.oil_temperature","it stopped answering"]""")
        e.getValue("fault").texts() shouldBe listOf("""[600,["P0301"]]""")
        e.getValue("gap").texts() shouldBe listOf("[700,12]")
        val lap = e.getValue("lap").jsonArray.single().jsonArray
        lap[0].jsonPrimitive.content shouldBe "800"
        lap[1].jsonObject.getValue("pitIn").jsonPrimitive.content shouldBe "true" // the whole record, as it came
    }

    @Test
    fun `lastSeq is the highest seq in the file, whatever its type, and null without any`() {
        build(rpm(100), """{"type":"weather","seq":900,"at":1,"wall":$t0}""", rpm(50)).getValue("lastSeq").jsonPrimitive.content shouldBe "900"
        build().getValue("lastSeq").toString() shouldBe "null"
    }

    @Test
    fun `records without wall, unknown types, and odd samples are skipped`() {
        val s = build(
            """{"type":"sample","signal":"engine.rpm","value":1,"seq":1,"at":1}""",
            """{"type":"weather","temp":21,"seq":2,"at":2,"wall":$t0}""",
            sample("engine.rpm", 5, "\"value\":\"fast\""),
            sample("gps.position", 5, "\"lat\":43.36"), // a half position is none at all
            rpm(10),
        )
        s.numbers("engine.rpm") shouldBe (listOf("10") to listOf("1000.0"))
        s.getValue("positions").jsonObject.getValue("t").jsonArray shouldBe JsonArray(emptyList())
    }

    @Test
    fun `strings are escaped, so any text is still valid JSON`() {
        val s = build(
            """{"type":"stopped","signal":"a\"b","reason":"line\nbreak \\ and é","seq":1,"at":1,"wall":$t0}""",
        )
        val stopped = s.getValue("events").jsonObject.getValue("stopped").jsonArray.single().jsonArray
        stopped[1].jsonPrimitive.content shouldBe "a\"b"
        stopped[2].jsonPrimitive.content shouldBe "line\nbreak \\ and é"
    }

    @Test
    fun `the threshold is 5 times the median, never under a second, and none for one sample`() {
        SeriesBuilder.breakThreshold(longArrayOf(0L)) shouldBe null
        SeriesBuilder.breakThreshold(longArrayOf(0L, 300L, 600L, 5000L)) shouldBe 1500L
        SeriesBuilder.breakThreshold(longArrayOf(0L, 10L, 20L)) shouldBe 1000L
    }
}
