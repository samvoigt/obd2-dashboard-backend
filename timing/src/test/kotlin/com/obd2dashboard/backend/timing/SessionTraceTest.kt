package com.obd2dashboard.backend.timing

import io.kotest.matchers.shouldBe
import org.junit.Test

/** A session's fixes and laps (M13.2). */
class SessionTraceTest {
    private fun trace(vararg lines: String) = SessionTrace().apply { lines.forEach { line(it.toByteArray()) } }

    @Test
    fun `fixes are timed on fixAt, else at, and one going back in time is left out`() {
        val t = trace(
            """{"type":"session","v":3,"id":"s","started":"2026-09-26T12:00:00Z","signals":[],"seq":0,"at":0}""",
            """{"type":"sample","signal":"gps.position","lat":43.1,"lon":-71.1,"fixAt":1000,"seq":1,"at":1150}""",
            """{"type":"sample","signal":"gps.position","lat":43.2,"lon":-71.2,"seq":2,"at":2150}""", // an older log: at only
            """{"type":"sample","signal":"engine.rpm","value":900,"seq":3,"at":2200}""",
            """{"type":"sample","signal":"gps.position","lat":43.3,"lon":-71.3,"fixAt":2100,"seq":4,"at":2300}""", // back in time: out
            """{"type":"sample","signal":"gps.position","lat":43.4,"lon":-71.4,"fixAt":3000,"seq":5,"at":3200}""",
            """not json""",
        )
        t.allFixes shouldBe listOf(Fix(-71.1, 43.1, 1000.0), Fix(-71.2, 43.2, 2150.0), Fix(-71.4, 43.4, 3000.0))
    }

    @Test
    fun `laps are read as the tablet sent them, old and new side by side`() {
        val t = trace(
            """{"type":"lap","track":"nhms","layout":"Road Course","lap":1,"time":96.0,"seq":10,"at":1,"wall":1}""",
            """{"type":"lap","track":"nhms","course":"nhms","courseVersion":7,"layout":"road","lap":2,"time":94.532,"sectors":[31.298,32.99,30.244],"startAt":4315701,"endAt":4410233,"pitIn":true,"seq":11,"at":2,"wall":2}""",
            """{"type":"lap","lap":"three","time":90}""", // unreadable: left out
        )
        t.tabletLaps shouldBe listOf(
            TabletLap("nhms", null, "Road Course", 1, 96.0, emptyList(), null, null, pitIn = false, pitOut = false, seq = 10, at = 1),
            TabletLap("nhms", 7, "road", 2, 94.532, listOf(31.298, 32.99, 30.244), 4315701, 4410233, pitIn = true, pitOut = false, seq = 11, at = 2),
        )
    }
}

/** Runs of the app (§22.8, M13.2). */
class RunsTest {
    private fun part(id: String, started: Long, ended: Long, firstAt: Long?, lastAt: Long?, device: String? = "tab-1", car: String = "outback") =
        RunPart(id, car, device, started, ended, firstAt, lastAt)

    @Test
    fun `one device's sessions back to back, at rising, are one run`() {
        val a = part("a", 0, 600_000, 1_000, 601_000)
        val b = part("b", 610_000, 1_200_000, 611_000, 1_201_000) // the OBD link dropped: 10 s later, at goes on
        val c = part("c", 1_210_000, 1_800_000, 1_211_000, 1_801_000)
        runs(listOf(c, a, b)).map { r -> r.map { it.id } } shouldBe listOf(listOf("a", "b", "c"))
    }

    @Test
    fun `an app restart, another device, another car, a long gap, or no device start a new run`() {
        val a = part("a", 0, 600_000, 1_000, 601_000)
        runs(listOf(a, part("restart", 610_000, 700_000, 500, 90_000))).size shouldBe 2 // at fell: the app restarted
        runs(listOf(a, part("other", 610_000, 700_000, 611_000, 700_000, device = "tab-2"))).size shouldBe 2
        runs(listOf(a, part("car", 610_000, 700_000, 611_000, 700_000, car = "yaris"))).size shouldBe 2
        runs(listOf(a, part("tomorrow", 600_000 + 13 * 3_600_000L, 600_000 + 14 * 3_600_000L, 700_000_000, 700_100_000))).size shouldBe 2
        runs(listOf(part("old", 0, 1_000, 0, 1_000, device = null), part("old2", 2_000, 3_000, 2_000, 3_000, device = null))).size shouldBe 2 // no device: alone
    }
}
