package com.obd2dashboard.backend.timing

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** The live run (M17.2): sessions fed a record at a time, re-timed as complete ones are. */
class ProvisionalTest {
    private fun live(id: String, lines: List<String>) =
        LiveSession(id, "outback").also { s -> lines.forEach { s.trace.record(Json.parseToJsonElement(it).jsonObject) } }

    @Test
    fun `a session fed a record at a time re-times as its whole log does`() {
        val lines = boxLog(0, 300, (1..4).map { boxLap(it, version = 1) })
        Provisional.runs(boxCourse(1), listOf(live("a", lines))).single() shouldBe Retiming.retime(boxCourse(1), listOf("a" to boxTrace(lines)))
    }

    @Test
    fun `a lap sent twice is kept once`() {
        val lap = boxLap(2, version = 1)
        // Lap 1 again after lap 2, as a refill from the archive would: the largest `seq` stays.
        val s = live("a", boxLog(0, 300, listOf(boxLap(1, version = 1), lap, lap, boxLap(1, version = 1))))
        s.trace.tabletLaps.map { it.lap } shouldBe listOf(1, 2)
        s.trace.lastSeq shouldBe 1002
    }

    @Test
    fun `sessions of one run join, and a restart of the app parts them`() {
        val one = Provisional.runs(boxCourse(1), listOf(live("a", boxLog(0, 40)), live("b", boxLog(41, 300))))
        one.map { it.sessions } shouldBe listOf(listOf("a", "b"))
        one.single().laps.first().startAt shouldBe 12_500.0 // the lap across the two, whole
        // `at` starting again, from 20 s (under a's last, over its first): two runs, each timed afresh.
        val two = Provisional.runs(boxCourse(1), listOf(live("a", boxLog(0, 40)), live("b", boxLog(41, 300, atShift = -21_000))))
        two.map { it.sessions } shouldBe listOf(listOf("a"), listOf("b"))
    }

    @Test
    fun `a session with nothing yet is left out`() {
        Provisional.runs(boxCourse(1), listOf(LiveSession("a", "outback"), live("b", boxLog(0, 300)))).map { it.sessions } shouldBe listOf(listOf("b"))
    }

    @Test
    fun `the race counts a live session continuing a complete one, bridging only a restart`() {
        // Complete: session a, laps 1-2 (to 152.5 s). Live: b, the same run, laps 3-4.
        val a = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 160, (1..2).map { boxLap(it, version = 1) }))))!!
        val b = Provisional.runs(boxCourse(1), listOf(live("b", boxLog(161, 300, (3..4).map { boxLap(it, version = 1) }))))
        val race = Race.car("outback", listOf(a) + b, setOf("a", "b"))!!
        race.laps.map { it.number to it.source } shouldBe (1..4).map { it to "tablet" }
        // After a restart of the app (`at` from 0 again), the gap is one lap, marked.
        val shifted = Provisional.runs(boxCourse(1), listOf(live("c", boxLog(230, 450, atShift = -230_000))))
        Race.car("outback", listOf(a) + shifted, setOf("a", "c"))!!.laps.map { it.source } shouldBe listOf("tablet", "tablet", "restart", "retimed", "retimed")
    }
}
