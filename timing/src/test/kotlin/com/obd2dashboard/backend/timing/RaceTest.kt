package com.obd2dashboard.backend.timing

import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

/** The race, one timeline (M15.2). Laps of 70 s; `at` from 0; the tablet's wall is `at` + [W]. */
class RaceTest {
    private val W = 1_000_000L

    private fun lap(session: String, start: Long, time: Long = 70_000, pitIn: Boolean = false, pitOut: Boolean = false, source: LapSource = LapSource.RETIMED, sectors: List<Double> = emptyList()) =
        RunLap(session, source, start.toDouble(), (start + time).toDouble(), time / 1000.0, sectors, pitIn, pitOut)

    private fun run(vararg laps: RunLap, crossings: List<PitCrossing> = emptyList(), offset: Long = W, sessions: List<String> = laps.map { it.session }.distinct()) =
        RunTiming(2, "box", 1, "box", sessions, laps.toList(), emptyList(), sessions.associateWith { offset } + crossings.associate { it.session to offset }, crossings)

    /**
     * One run of the app, two driver changes: car session c1 (laps 1–4, lap 4 an
     * in-lap), the stop through tablet-only session t1, car session c2 (an
     * out-lap, then laps), another stop, c3. The out-laps carry the stops.
     */
    private val oneRun = run(
        lap("c1", 0), lap("c1", 70_000), lap("c1", 140_000), lap("c1", 210_000, pitIn = true),
        lap("c2", 280_000, time = 190_000, pitOut = true), lap("c2", 470_000), lap("c2", 540_000, pitIn = true),
        lap("c3", 610_000, time = 200_000, pitOut = true), lap("c3", 810_000, time = 69_000),
        crossings = listOf(PitCrossing("c1", "in", 270_000.0), PitCrossing("t1", "out", 400_000.0), PitCrossing("c2", "in", 600_000.0), PitCrossing("c3", "out", 740_000.0)),
        sessions = listOf("c1", "t1", "c2", "c3"),
    )
    private val raceSessions = setOf("c1", "t1", "c2", "c3")

    @Test
    fun `one run through two driver changes, numbered straight through, stops, and a stint at each new driver`() {
        val race = Race.car("outback", listOf(oneRun), raceSessions, drivers = mapOf("c1" to "d-sam", "c2" to "d-alex", "c3" to "d-sam"))!!
        race.laps.map { it.number } shouldBe (1..9).toList()
        race.laps.first().start shouldBe W
        race.seconds shouldBe 879.0
        // On the in-laps, 4 and 7: the lane's entry comes before the pit line that ends them.
        race.stops shouldBe listOf(RaceStop(4, W + 270_000, W + 400_000, 130.0), RaceStop(7, W + 600_000, W + 740_000, 140.0))
        race.stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 4), Triple("d-alex", 5, 7), Triple("d-sam", 8, 9))
        // The in-lap is the outgoing driver's; the out-lap, carrying the stop, the incoming one's.
        race.laps[3].stint shouldBe 1
        race.laps[4].stint shouldBe 2
        race.best!!.number shouldBe 9 // 69 s, on track; the out-laps never count
        race.stints[2].best shouldBe 69.0
        race.stints[0].seconds shouldBe 280.0
        // A stint's best is never its in-lap, however quick.
        Race.car("outback", listOf(run(lap("c1", 0), lap("c1", 70_000, time = 60_000, pitIn = true))), setOf("c1"))!!.stints.single().best shouldBe 70.0
        race.stintsEdited shouldBe false
    }

    @Test
    fun `a restart of the app is bridged by one lap on wall, an in- or out-lap as the pit line says`() {
        // Run A ends with an in-lap; the app restarts (at from 0 again, another offset); run B's first crossing is on track.
        val a = run(lap("a1", 0), lap("a1", 70_000, pitIn = true), offset = W)
        val b = run(lap("b1", 20_000), lap("b1", 90_000), offset = W + 140_000 + 60_000 - 20_000) // B's first crossing 60 s after A's last
        val race = Race.car("outback", listOf(a, b), setOf("a1", "b1"))!!
        race.laps.map { it.number to it.source } shouldBe listOf(1 to "retimed", 2 to "retimed", 3 to "restart", 4 to "retimed", 5 to "retimed")
        val bridge = race.laps[2]
        bridge.time shouldBe 60.0
        bridge.pitOut shouldBe true // A ended on the pit line
        bridge.pitIn shouldBe false
        bridge.session shouldBe "b1"
        race.best!!.source shouldBe "retimed" // never the restart's lap, though it's the shortest
        // B beginning on the pit line instead: the bridge is the in-lap.
        val b2 = run(lap("b1", 20_000, pitOut = true), offset = W + 180_000)
        Race.car("outback", listOf(run(lap("a1", 0), offset = W), b2), setOf("a1", "b1"))!!.laps[1].let { it.pitIn shouldBe true; it.pitOut shouldBe false }
        // A bridge on track, 30 s, quicker than any lap: never the best.
        val onTrack = Race.car("outback", listOf(run(lap("a1", 0), offset = W), run(lap("b1", 0), offset = W + 100_000)), setOf("a1", "b1"))!!
        onTrack.laps[1].let { it.source shouldBe "restart"; it.pitIn shouldBe false; it.pitOut shouldBe false; it.time shouldBe 30.0 }
        onTrack.best!!.time shouldBe 70.0
        // No gap (a clock set back between), no bridge.
        Race.car("outback", listOf(run(lap("a1", 0), offset = W), run(lap("b1", 0), offset = W)), setOf("a1", "b1"))!!.laps.size shouldBe 2
    }

    @Test
    fun `only the race's sessions count, though the run began in practice`() {
        val race = Race.car("outback", listOf(run(lap("practice", 0), lap("c1", 70_000))), setOf("c1"))!!
        race.laps.map { it.session } shouldBe listOf("c1")
        // A stop made in practice isn't the race's.
        val withPits = run(lap("practice", 0), lap("c1", 70_000), crossings = listOf(PitCrossing("practice", "in", 10_000.0), PitCrossing("practice", "out", 20_000.0)))
        Race.car("outback", listOf(withPits), setOf("c1"))!!.stops shouldBe emptyList()
        Race.car("outback", listOf(run(lap("practice", 0))), setOf("c1")) shouldBe null
    }

    @Test
    fun `the green flag and the flag are marked on the laps they fell in, and cut nothing`() {
        val race = Race.car("outback", listOf(oneRun), raceSessions, green = W + 100_000, flag = W + 850_000)!!
        race.greenLap shouldBe 2
        race.flagLap shouldBe 9
        race.laps.size shouldBe 9 // laps before the start and after the flag still counted
        Race.car("outback", listOf(oneRun), raceSessions, flag = W + 2_000_000)!!.flagLap shouldBe null
        Race.car("outback", listOf(oneRun), raceSessions).let { it!!.greenLap shouldBe null; it.flagLap shouldBe null }
    }

    @Test
    fun `stints as edited replace the default, merging a fuel stop and splitting where a change had no stop`() {
        val edited = listOf(StintMark(W, "d-sam"), StintMark(W + 740_000, "d-alex")) // the first stop was fuel only
        val race = Race.car("outback", listOf(oneRun), raceSessions, edited = edited)!!
        race.stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 7), Triple("d-alex", 8, 9))
        race.stintsEdited shouldBe true
        val split = listOf(StintMark(W, "d-sam"), StintMark(W + 100_000, "d-alex")) // a change on track, in lap 2
        Race.car("outback", listOf(oneRun), raceSessions, edited = split)!!.stints.map { it.firstLap to it.laps } shouldBe listOf(1 to 1, 2 to 8)
        // Split exactly where lap 3 starts (lap 2's end): lap 3 is the new stint's, lap 2 the old one's.
        val atLap = listOf(StintMark(W, "d-sam"), StintMark(W + 140_000, "d-alex"))
        Race.car("outback", listOf(oneRun), raceSessions, edited = atLap)!!.stints.map { it.firstLap to it.lastLap } shouldBe listOf(1 to 2, 3 to 9)
    }

    @Test
    fun `an entry with no exit is a stop with no end, and an exit with no entry is nothing`() {
        val r = run(lap("c1", 0), lap("c1", 70_000, pitIn = true),
            crossings = listOf(PitCrossing("c1", "out", 5_000.0), PitCrossing("c1", "in", 130_000.0), PitCrossing("c1", "in", 150_000.0)))
        Race.car("outback", listOf(r), setOf("c1"))!!.stops shouldBe listOf(RaceStop(2, W + 130_000), RaceStop(2, W + 150_000))
    }

    @Test
    fun `two cars classified by laps then time, and the tablet's offset from its sessions`() {
        val quick = Race.car("yaris", listOf(run(lap("y", 0, time = 60_000), lap("y", 60_000, time = 60_000))), setOf("y"))!!
        val long = Race.car("outback", listOf(oneRun), raceSessions)!!
        val short = Race.car("miata", listOf(run(lap("m", 0), lap("m", 70_000))), setOf("m"))!!
        Race.classify(listOf(short, quick, long)).map { it.car } shouldBe listOf("outback", "yaris", "miata")
        val t = Instant.parse("2026-10-04T13:00:00Z")
        // Streamed: created 2 s after its start; uploaded at its end: created 11 min after.
        Race.tabletOffset(listOf(t to t.toEpochMilli() - 39_600_000 - 2_000, t to t.toEpochMilli() - 39_600_000 - 660_000)) shouldBe 39_602_000
        Race.tabletOffset(emptyList()) shouldBe null
    }

    @Test
    fun `a car's theoretical best never counts an in-lap's last sector nor an out-lap's first, and each stint's consistency its laps on track (M16_2)`() {
        val r = run(
            lap("c1", 0, sectors = listOf(35.0, 35.0)),
            lap("c1", 70_000, time = 69_000, sectors = listOf(34.0, 35.0)),
            lap("c1", 139_000, time = 60_000, pitIn = true, sectors = listOf(36.0, 24.0)), // its 24 s ends in the pits
            lap("c1", 199_000, time = 150_000, pitOut = true, sectors = listOf(10.0, 34.5)), // its 10 s starts there
        )
        val race = Race.car("outback", listOf(r), setOf("c1"))!!
        race.bestSectors shouldBe listOf(34.0, 34.5)
        race.theoretical shouldBe 68.5
        race.stints.single().consistency shouldBe Consistency.of(listOf(70.0, 69.0))
        // A lap across a restart has no sectors: the others still make a theoretical best.
        Race.car("outback", listOf(run(lap("a", 0, sectors = listOf(35.0, 35.0)), offset = W), run(lap("b", 0, sectors = listOf(34.0, 36.0)), offset = W + 100_000)),
            setOf("a", "b"))!!.theoretical shouldBe 69.0
    }

    // M26: a race in sessions, Saturday and Sunday, the car off track overnight.
    private val night = 16 * 3_600_000L
    private val saturday = run(lap("sat", 0), lap("sat", 70_000), lap("sat", 140_000, pitIn = true), offset = W)
    // Sunday: the app started afresh (`at` from 0), its first crossing 16 hours after Saturday's last.
    private val sunday = run(lap("sun", 30_000, time = 100_000, pitOut = true), lap("sun", 130_000), offset = W + 210_000 + night - 30_000)

    @Test
    fun `a race in two sessions, one car - laps numbered on, no lap across the night, the night never counted (M26)`() {
        val race = Race.sessions("outback", listOf(saturday, sunday),
            listOf(RaceSessionInput("p2", setOf("sat")), RaceSessionInput("p3", setOf("sun"))),
            drivers = mapOf("sat" to "d-sam", "sun" to "d-alex"))!!
        race.laps.map { it.number to it.session } shouldBe listOf(1 to "sat", 2 to "sat", 3 to "sat", 4 to "sun", 5 to "sun")
        race.laps.none { it.source == "restart" } shouldBe true
        race.seconds shouldBe 210.0 + 170.0
        // Sunday's new driver is Sunday's first stint, numbered on.
        race.stints.map { listOf(it.number, it.driver, it.firstLap, it.lastLap) } shouldBe listOf(listOf(1, "d-sam", 1, 3), listOf(2, "d-alex", 4, 5))
        race.laps.map { it.stint } shouldBe listOf(1, 1, 1, 2, 2)
        race.sessions shouldBe listOf(RaceSessionSpan("p2", 1, 3, 210.0), RaceSessionSpan("p3", 4, 2, 170.0))
        race.best!!.number shouldBe 1
    }

    @Test
    fun `each session's flags fall on its own laps, the race has the first green flag and the last chequered flag (M26)`() {
        val race = Race.sessions("outback", listOf(saturday, sunday), listOf(
            RaceSessionInput("p2", setOf("sat"), green = W + 10_000, flag = W + 150_000),
            RaceSessionInput("p3", setOf("sun"), green = W + 210_000 + night + 50_000),
        ))!!
        race.sessions.map { it.greenLap to it.flagLap } shouldBe listOf(1 to 3, 4 to null)
        race.greenLap shouldBe 1
        race.flagLap shouldBe null
    }

    @Test
    fun `one session is exactly the race as before, and a session the car missed is noted with no laps (M26)`() {
        Race.sessions("outback", listOf(oneRun), listOf(RaceSessionInput("p1", raceSessions)), drivers = mapOf("c1" to "d-sam", "c2" to "d-alex", "c3" to "d-sam"))!!
            .copy(sessions = emptyList()) shouldBe Race.car("outback", listOf(oneRun), raceSessions, drivers = mapOf("c1" to "d-sam", "c2" to "d-alex", "c3" to "d-sam"))
        val missed = Race.sessions("outback", listOf(saturday), listOf(RaceSessionInput("p2", setOf("sat")), RaceSessionInput("p3", setOf("sun"))))!!
        missed.sessions shouldBe listOf(RaceSessionSpan("p2", 1, 3, 210.0), RaceSessionSpan("p3"))
        Race.sessions("outback", listOf(saturday), listOf(RaceSessionInput("p3", setOf("sun")))) shouldBe null
    }

    @Test
    fun `a restart within a session is still bridged, and edited stints stay in their session (M26)`() {
        // Saturday's app restarted 60 s after its last crossing: bridged. Then the night: not.
        val satB = run(lap("sat2", 20_000), offset = W + 210_000 + 60_000 - 20_000)
        val sunday2 = run(lap("sun", 30_000, time = 100_000, pitOut = true), lap("sun", 130_000), offset = W + 340_000 + night - 30_000)
        val race = Race.sessions("outback", listOf(saturday, satB, sunday2), listOf(
            RaceSessionInput("p2", setOf("sat", "sat2"), edited = listOf(StintMark(W, "d-sam"), StintMark(W + 140_000, "d-kim"))),
            RaceSessionInput("p3", setOf("sun")),
        ), drivers = mapOf("sun" to "d-alex"))!!
        race.laps.map { it.source } shouldBe listOf("retimed", "retimed", "retimed", "restart", "retimed", "retimed", "retimed")
        race.stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 2), Triple("d-kim", 3, 5), Triple("d-alex", 6, 7))
        race.stintsEdited shouldBe true
    }

    @Test
    fun `two cars over two sessions, classified by laps across both, then time (M26)`() {
        val inputs = listOf(RaceSessionInput("p2", setOf("sat")), RaceSessionInput("p3", setOf("sun")))
        val both = Race.sessions("outback", listOf(saturday, sunday), inputs)!!
        val saturdayOnly = Race.sessions("gti", listOf(run(lap("sat", 0, time = 60_000), lap("sat", 60_000, time = 60_000), lap("sat", 120_000, time = 60_000), lap("sat", 180_000, time = 60_000))), inputs)!!
        Race.classify(listOf(saturdayOnly, both)).map { it.car } shouldBe listOf("outback", "gti")
    }

    @Test
    fun `a driver change within a session starts a default stint, its laps the new driver's, edited stints still winning (M27)`() {
        // One session, c1: Sam, then Alex from mid-lap 3 (the lap under way is the new driver's, as a stint's is).
        val one = run(lap("c1", 0), lap("c1", 70_000), lap("c1", 140_000), lap("c1", 210_000))
        val changes = mapOf("c1" to listOf(StintMark(W + 160_000, "d-alex")))
        val race = Race.car("outback", listOf(one), setOf("c1"), drivers = mapOf("c1" to "d-sam"), changes = changes)!!
        race.stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 2), Triple("d-alex", 3, 4))
        // A change in a session outside the race, or before its first lap, makes no stint.
        Race.car("outback", listOf(one), setOf("c1"), drivers = mapOf("c1" to "d-sam"), changes = mapOf("p1" to listOf(StintMark(W + 160_000, "d-alex"))))!!
            .stints.single().driver shouldBe "d-sam"
        // Stints as edited replace the default, the change's included.
        Race.car("outback", listOf(one), setOf("c1"), drivers = mapOf("c1" to "d-sam"), edited = listOf(StintMark(W, "d-kim")), changes = changes)!!
            .stints.single().driver shouldBe "d-kim"
        // Through a race in sessions too.
        Race.sessions("outback", listOf(one), listOf(RaceSessionInput("p2", setOf("c1"))), drivers = mapOf("c1" to "d-sam"), changes = changes)!!
            .stints.map { it.driver } shouldBe listOf("d-sam", "d-alex")
    }

    @Test
    fun `stops split no stint, and a change to the same driver at a stop starts one (M32)`() {
        // The whole run with one driver: two fuel stops, one stint.
        val same = mapOf("c1" to "d-sam", "c2" to "d-sam", "c3" to "d-sam")
        Race.car("outback", listOf(oneRun), raceSessions, drivers = same)!!.stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe
            listOf(Triple("d-sam", 1, 9))
        // A double stint: the same driver entered again at the second stop's exit.
        val again = mapOf("c3" to listOf(StintMark(W + 740_000, "d-sam")))
        Race.car("outback", listOf(oneRun), raceSessions, drivers = same, changes = again)!!.stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe
            listOf(Triple("d-sam", 1, 7), Triple("d-sam", 8, 9))
    }

    @Test
    fun `sessions back to back with the same driver are one stint, a new driver at a session's start starts one (M32)`() {
        // Three sessions in one run (restarts of the app between, say), Sam throughout, then Alex in the third.
        val three = run(lap("a", 0), lap("a", 70_000), lap("b", 140_000), lap("b", 210_000), lap("c", 280_000), lap("c", 350_000))
        Race.car("outback", listOf(three), setOf("a", "b", "c"), drivers = mapOf("a" to "d-sam", "b" to "d-sam", "c" to "d-sam"))!!
            .stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 6))
        Race.car("outback", listOf(three), setOf("a", "b", "c"), drivers = mapOf("a" to "d-sam", "b" to "d-sam", "c" to "d-alex"))!!
            .stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 4), Triple("d-alex", 5, 6))
        // A session nobody's set is a stint of nobody's, not a continuation.
        Race.car("outback", listOf(three), setOf("a", "b", "c"), drivers = mapOf("a" to "d-sam", "c" to "d-sam"))!!
            .stints.map { Triple(it.driver, it.firstLap, it.lastLap) } shouldBe listOf(Triple("d-sam", 1, 2), Triple(null, 3, 4), Triple("d-sam", 5, 6))
    }

    @Test
    fun `a stint never crosses a race part, though the driver is the same (M32)`() {
        val race = Race.sessions("outback", listOf(saturday, sunday), listOf(RaceSessionInput("p2", setOf("sat")), RaceSessionInput("p3", setOf("sun"))),
            drivers = mapOf("sat" to "d-sam", "sun" to "d-sam"))!!
        race.stints.map { it.driver to it.firstLap } shouldBe listOf("d-sam" to 1, "d-sam" to 4)
    }
}
