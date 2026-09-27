package com.obd2dashboard.backend.archive

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reading a session into its summary (M7.1). */
class SessionReaderTest {
    private fun summarise(vararg lines: String): SessionSummary =
        SessionReader().apply { read(ByteArrayInputStream(lines.joinToString("\n", postfix = "\n").toByteArray())) }.summary()

    private val header =
        """{"type":"session","v":3,"id":"s","started":"2026-09-26T12:00:00Z","vin":"TSTVEHCLE00000001","signals":[{"name":"engine.rpm","unit":"rpm","kind":"number"},{"name":"diagnostics.mil","unit":"","kind":"flag"}],"seq":0,"at":0,"wall":1790000000000}"""

    private fun lap(n: Int, time: Double, wall: Long, extra: String = "") =
        """{"type":"lap","track":"nhms","layout":"Road Course","lap":$n,"time":$time$extra,"seq":${100 + n},"at":0,"wall":$wall}"""

    @Test
    fun `every kind of record, and the times from wall`() {
        val s = summarise(
            header,
            """{"type":"sample","signal":"engine.rpm","value":900.5,"seq":1,"at":10,"wall":1790000000100}""",
            """{"type":"sample","signal":"diagnostics.mil","flag":false,"seq":2,"at":11,"wall":1790000000101}""",
            """{"type":"sample","signal":"fuel.system_1_status","code":1,"text":"Open loop","seq":3,"at":12,"wall":1790000000102}""",
            """{"type":"sample","signal":"diagnostics.monitors_complete","flags":["Catalyst"],"seq":4,"at":13,"wall":1790000000103}""",
            """{"type":"sample","signal":"gps.position","lat":43.36,"lon":-71.46,"seq":5,"at":14,"wall":1790000000104}""",
            """{"type":"stopped","signal":"engine.oil_temperature","reason":"the car stopped answering it","seq":6,"at":15,"wall":1790000000105}""",
            """{"type":"fault","codes":["P0301","P0420"],"seq":7,"at":16,"wall":1790000000106}""",
            """{"type":"fault","codes":["P0420","P0171"],"previousCodes":["P0301","P0420"],"seq":8,"at":17,"wall":1790000000107}""",
            """{"type":"gap","missed":12,"seq":9,"at":18,"wall":1790000000108}""",
            """{"type":"gap","missed":3,"seq":30,"at":19,"wall":1790000060000}""",
        )
        s.version shouldBe SessionSummary.VERSION
        s.started shouldBe 1790000000000
        s.ended shouldBe 1790000060000
        s.lines shouldBe 11
        s.signals.map { it.name } shouldBe listOf("engine.rpm", "diagnostics.mil")
        s.faults shouldBe listOf("P0301", "P0420", "P0171") // every code seen, in the order first seen
        s.gaps shouldBe 2
        s.missed shouldBe 15
        s.unreadable shouldBe 0
        s.laps shouldBe 0
        s.bestLap.shouldBeNull()
        s.track.shouldBeNull()
    }

    @Test
    fun `unknown types and fields are skipped, and a bad line is only counted`() {
        val s = summarise(
            header,
            """{"type":"weather","temp":21,"seq":1,"at":1,"wall":1790000000100}""",
            """{"type":"sample","signal":"engine.rpm","value":1000,"newField":{"x":1},"seq":2,"at":2,"wall":1790000000200}""",
            """not json""",
            """[1,2,3]""",
            """{"type":"lap","lap":"three","time":90.0,"seq":3,"at":3,"wall":1790000000300}""", // a lap it can't read
        )
        s.lines shouldBe 6
        s.unreadable shouldBe 2
        s.ended shouldBe 1790000000300
        s.laps shouldBe 0
    }

    @Test
    fun `a signals record adds to the session's signals, the latest unit winning`() {
        val s = summarise(
            header,
            """{"type":"signals","signals":[{"name":"engine.rpm","unit":"rpm","kind":"number"},{"name":"fuel.tank_level","unit":"%","kind":"number"}],"seq":50,"at":1,"wall":1790000000100}""",
            """{"type":"signals","signals":[{"name":"fuel.tank_level","unit":"L","kind":"number"}],"seq":60,"at":2,"wall":1790000000200}""",
        )
        s.signals shouldBe listOf(
            SignalInfo("engine.rpm", "rpm", "number"),
            SignalInfo("diagnostics.mil", "", "flag"),
            SignalInfo("fuel.tank_level", "L", "number"),
        )
    }

    @Test
    fun `laps count, and the best is never a pit lap`() {
        val s = summarise(
            header,
            lap(1, 96.2, 1790000100000),
            lap(2, 94.5, 1790000200000),
            lap(3, 88.0, 1790000300000, ""","pitIn":true"""), // quicker, but into the pits
            lap(4, 91.0, 1790000400000, ""","pitOut":true"""),
            lap(5, 94.9, 1790000500000, ""","pitIn":false"""),
        )
        s.laps shouldBe 5
        s.track shouldBe "nhms"
        s.layout shouldBe "Road Course"
        s.bestLap shouldBe LapInfo("nhms", "Road Course", 2, 94.5, pitIn = false, pitOut = false, wall = 1790000200000)
    }

    @Test
    fun `only pit laps means no best lap`() {
        summarise(header, lap(1, 90.0, 1790000100000, ""","pitIn":true""")).bestLap.shouldBeNull()
    }

    @Test
    fun `the track is the one most laps were at`() {
        val s = summarise(
            header,
            """{"type":"lap","track":"lime-rock","layout":"Full","lap":1,"time":60.0,"seq":1,"at":0,"wall":1790000100000}""",
            lap(1, 94.0, 1790000200000),
            lap(2, 95.0, 1790000300000),
        )
        s.track shouldBe "nhms"
        s.layout shouldBe "Road Course"
    }

    @Test
    fun `started falls back to the header, and ended is the latest wall even if the clock stepped back`() {
        summarise(
            """{"type":"session","v":3,"id":"s","started":"2026-09-26T12:00:00Z","signals":[],"seq":0,"at":0}""",
        ).let {
            it.started shouldBe 1790424000000
            it.ended shouldBe 1790424000000
        }
        summarise(
            header,
            """{"type":"sample","signal":"engine.rpm","value":1,"seq":1,"at":1,"wall":1790000500000}""",
            """{"type":"sample","signal":"engine.rpm","value":1,"seq":2,"at":2,"wall":1790000400000}""", // corrected clock
        ).ended shouldBe 1790000500000
    }

    @Test
    fun `lines split at newlines, the last one needing none`() {
        val seen = mutableListOf<String>()
        LineSplitter { seen += String(it) }.feed(ByteArrayInputStream("a\nbb\n\nccc".toByteArray()))
        seen shouldBe listOf("a", "bb", "", "ccc")
        val many = (1..100_000).joinToString("\n") { "line $it" } // across many 64 KiB reads
        var count = 0
        var last = ""
        LineSplitter { count++; last = String(it) }.feed(ByteArrayInputStream("$many\n".toByteArray()))
        count shouldBe 100_000
        last shouldBe "line 100000"
    }

    @Test
    fun `the fixture session reads cleanly`() {
        val s = SessionReader().apply { read(ByteArrayInputStream(Fixtures.session)) }.summary()
        s.lines shouldBe Fixtures.SESSION_LINES.toLong()
        s.unreadable shouldBe 0
    }

    /** The app's real logs, when its repo sits beside this one; skipped otherwise. */
    @Test
    fun `the app's real test logs read cleanly`() {
        val dir = File("../../obd2-dashboard/test-data/sessions").takeIf { it.isDirectory }
            ?: File("../obd2-dashboard/test-data/sessions").takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val logs = dir!!.listFiles { f -> f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl.gz") }!!
        assumeTrue(logs.isNotEmpty())
        for (log in logs) {
            val s = log.inputStream().use { raw ->
                val input = if (log.name.endsWith(".gz")) GZIPInputStream(raw) else raw
                SessionReader().apply { read(input) }.summary()
            }
            println("real log ${log.name}: ${s.lines} lines, ${s.signals.size} signals, ${(s.ended - s.started) / 1000} s, unreadable ${s.unreadable}")
            // One log was cut mid-line by unplugging the adapter: its last line alone is unreadable,
            // and it is counted, not fatal. (The archive refuses such a line, so no archived session has one.)
            (s.unreadable <= 1) shouldBe true
            (s.lines > 1000) shouldBe true
        }
    }
}
