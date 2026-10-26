package com.obd2dashboard.backend

import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.timing.LapSource
import com.obd2dashboard.backend.timing.PitCrossing
import com.obd2dashboard.backend.timing.RaceSessionInput
import com.obd2dashboard.backend.timing.RunLap
import com.obd2dashboard.backend.timing.RunTiming
import com.obd2dashboard.backend.timing.StintMark
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Where a car stands (M17.3). `at` from 0; the tablet's wall is `at` + [W]. */
class StandingsTest {
    private val W = 1_000_000L
    private val sam = Driver("d-sam", "Sam Voigt", "SAM")
    private val alex = Driver("d-alex", "Alex Rider", "ALE")
    private val box = CourseAt("box", 3, "box")

    private fun lap(session: String, start: Long, time: Long = 70_000, n: Int? = null, pitIn: Boolean = false, pitOut: Boolean = false, sectors: List<Double> = listOf(20.0, 25.0, 25.0)) =
        RunLap(session, if (n != null) LapSource.TABLET else LapSource.RETIMED, start.toDouble(), (start + time).toDouble(), time / 1000.0, sectors, pitIn, pitOut, tabletLap = n)

    private fun run(vararg laps: RunLap, sessions: List<String> = laps.map { it.session }.distinct(), crossings: List<PitCrossing> = emptyList(), layout: String = "box") =
        RunTiming(2, "box", 3, layout, sessions, laps.toList(), emptyList(), sessions.associateWith { W }, crossings)

    @Test
    fun `with no event, the best on this layout, its tablet lap and driver, and the best of each sector by the pit rule`() {
        val runs = listOf(
            run(lap("a", 0, n = 1), lap("a", 70_000, 68_000, n = 2, sectors = listOf(20.0, 24.0, 24.0))),
            // The live run: an out-lap quicker than anything (never a best), and its first sector with it.
            run(lap("b", 200_000, 60_000, n = 1, pitOut = true, sectors = listOf(10.0, 25.0, 25.0)), lap("b", 260_000, 69_000, n = 2, sectors = listOf(19.0, 25.0, 25.0))),
            run(lap("c", 0, 50_000), layout = "short"), // another layout: never counts
        )
        val s = Standings.of("outback", "b", box, runs, setOf("a", "b", "c"), mapOf("a" to sam, "b" to alex))
        s.best shouldBe BestLap(68.0, listOf(20.0, 24.0, 24.0), "SAM", "a", 2)
        s.bestSectors shouldBe listOf(19.0, 24.0, 24.0)
        s.course shouldBe box
        s.race shouldBe null
        // Alex's stint: from the run's first lap, no stop in it.
        s.driver shouldBe DriverNow("Alex Rider", "ALE", W + 200_000)
        // A lap re-timed has no tablet number.
        Standings.of("outback", "a", box, listOf(run(lap("a", 0, 60_000))), setOf("a"), emptyMap()).best!!.lap shouldBe null
    }

    @Test
    fun `a stint outside a race begins where the car last left the pits`() {
        val live = run(lap("b", 0, n = 1), lap("b", 70_000, n = 2, pitIn = true), crossings = listOf(PitCrossing("b", "in", 130_000.0), PitCrossing("b", "out", 180_000.0)))
        Standings.of("outback", "b", box, listOf(live), setOf("b"), mapOf("b" to sam)).driver shouldBe DriverNow("Sam Voigt", "SAM", W + 180_000)
        // The exit in the complete run before it, when the live one has none: the same run of the app, split by an upload.
        val before = run(lap("a", 0, n = 1, pitOut = true), crossings = listOf(PitCrossing("a", "out", 10_000.0)))
        Standings.of("outback", "b", box, listOf(before, run(lap("b", 70_000, n = 2))), setOf("a", "b"), mapOf("b" to sam)).driver shouldBe DriverNow("Sam Voigt", "SAM", W + 10_000)
        // Both: the later exit.
        Standings.of("outback", "b", box, listOf(before, live), setOf("a", "b"), mapOf("b" to sam)).driver shouldBe DriverNow("Sam Voigt", "SAM", W + 180_000)
    }

    @Test
    fun `only the event's sessions count, and none makes no best`() {
        val runs = listOf(run(lap("old", 0, 50_000, n = 1)), run(lap("b", 0, n = 1)))
        Standings.of("outback", "b", box, runs, setOf("b"), emptyMap()).best!!.session shouldBe "b"
        Standings.of("outback", "b", box, runs, emptySet(), emptyMap()).let { it.best shouldBe null; it.bestSectors shouldBe null }
    }

    @Test
    fun `in a race, the lap count, when the car left the pits, and the stint's driver`() {
        val race = RaceNowInput(listOf(RaceSessionInput("p2", setOf("c1", "t1", "c2"))))
        val before = run(lap("c1", 0, n = 1), lap("c1", 70_000, n = 2))
        Standings.of("outback", "c1", box, listOf(before), setOf("c1"), mapOf("c1" to sam), race).let {
            it.race shouldBe RaceNow(2, W) // no stop yet: since the race's first lap
            it.driver shouldBe DriverNow("Sam Voigt", "SAM", W)
        }
        // In the pits: entered, not out.
        val inPits = run(lap("c1", 0, n = 1), lap("c1", 70_000, n = 2, pitIn = true), crossings = listOf(PitCrossing("c1", "in", 130_000.0)))
        Standings.of("outback", "c1", box, listOf(inPits), setOf("c1"), mapOf("c1" to sam), race).race shouldBe RaceNow(2, null)
        // Out again, Alex's session: his stint from its first lap, the out-lap (M32: stops split nothing; a new driver does).
        val after = run(
            lap("c1", 0, n = 1), lap("c1", 70_000, n = 2, pitIn = true), lap("c2", 140_000, 120_000, n = 3, pitOut = true), lap("c2", 260_000, 65_000, n = 4),
            sessions = listOf("c1", "t1", "c2"), crossings = listOf(PitCrossing("c1", "in", 130_000.0), PitCrossing("t1", "out", 200_000.0)),
        )
        Standings.of("outback", "c2", box, listOf(after), setOf("c1", "t1", "c2"), mapOf("c1" to sam, "c2" to alex), race).let {
            it.race shouldBe RaceNow(4, W + 200_000)
            it.driver shouldBe DriverNow("Alex Rider", "ALE", W + 140_000)
            it.best shouldBe BestLap(65.0, listOf(20.0, 25.0, 25.0), "ALE", "c2", 4)
        }
    }

    @Test
    fun `in a race, a session set to a new driver starts that driver's stint, one nobody set carries on (M32)`() {
        // No stop; the crew says Alex took over in c2 (from the car page): his stint from c2's first lap.
        val runs = listOf(run(lap("c1", 0, n = 1), lap("c2", 70_000, n = 2)))
        val race = RaceNowInput(listOf(RaceSessionInput("p2", setOf("c1", "c2"))))
        Standings.of("outback", "c2", box, runs, setOf("c1", "c2"), mapOf("c1" to sam, "c2" to alex), race).driver shouldBe DriverNow("Alex Rider", "ALE", W + 70_000)
        Standings.of("outback", "c2", box, runs, setOf("c1", "c2"), mapOf("c1" to sam), race).driver shouldBe DriverNow("Sam Voigt", "SAM", W)
    }

    @Test
    fun `stints as edited name the driver, of the car now and of the best lap`() {
        // The crew says Alex drove from lap 2, with no stop, in Sam's session.
        val runs = listOf(run(lap("c1", 0, n = 1), lap("c1", 70_000, 65_000, n = 2)))
        val race = RaceNowInput(listOf(RaceSessionInput("p2", setOf("c1"), edited = listOf(StintMark(W, "d-sam"), StintMark(W + 70_000, "d-alex")))))
        Standings.of("outback", "c1", box, runs, setOf("c1"), mapOf("c1" to sam), race, roster = mapOf("d-sam" to sam, "d-alex" to alex)).let {
            it.driver shouldBe DriverNow("Alex Rider", "ALE", W + 70_000)
            it.best!!.driver shouldBe "ALE"
        }
    }

    @Test
    fun `on Sunday, the race's lap count goes on from Saturday's, and the car left the pits at Sunday's first lap (M26)`() {
        // Saturday: two laps and a stop (in and out). Sunday, the app afresh 16 hours on: Alex's first lap so far.
        val night = 16 * 3_600_000L
        val saturday = run(lap("sat", 0, n = 1), lap("sat", 70_000, n = 2, pitIn = true), lap("sat", 140_000, n = 3, pitOut = true),
            crossings = listOf(PitCrossing("sat", "in", 130_000.0), PitCrossing("sat", "out", 160_000.0)))
        val sunday = RunTiming(2, "box", 3, "box", listOf("sun"), listOf(lap("sun", 0, n = 1)), emptyList(), mapOf("sun" to W + 210_000 + night), emptyList())
        val race = RaceNowInput(listOf(RaceSessionInput("p2", setOf("sat")), RaceSessionInput("p3", setOf("sun"))))
        Standings.of("outback", "sun", box, listOf(saturday, sunday), setOf("sat", "sun"), mapOf("sat" to sam, "sun" to alex), race).let {
            it.race shouldBe RaceNow(4, W + 210_000 + night)
            it.driver shouldBe DriverNow("Alex Rider", "ALE", W + 210_000 + night)
        }
        // Before Sunday's first crossing: nothing of the race yet, as at its very start; not Saturday's stint going on.
        val sundayYet = RunTiming(2, "box", 3, "box", listOf("sun"), emptyList(), emptyList(), mapOf("sun" to W + 210_000 + night), emptyList())
        Standings.of("outback", "sun", box, listOf(saturday, sundayYet), setOf("sat", "sun"), mapOf("sat" to sam, "sun" to alex), race).let {
            it.race shouldBe null
            it.driver!!.name shouldBe "Alex Rider"
        }
    }

    @Test
    fun `a driver change within the session (M27) - who's driving now, and the best lap's driver its lap's`() {
        // Sam's session, Alex from lap 3 on: Alex is driving now; Sam's lap 2 the best, Sam's.
        val runs = listOf(run(lap("c1", 0, n = 1), lap("c1", 70_000, 65_000, n = 2), lap("c1", 135_000, n = 3)))
        val changes = mapOf("c1" to listOf(StintMark(W + 140_000, "d-alex")))
        val roster = mapOf("d-sam" to sam, "d-alex" to alex)
        Standings.of("outback", "c1", box, runs, setOf("c1"), mapOf("c1" to sam), roster = roster, changes = changes).let {
            it.driver!!.code shouldBe "ALE"
            it.driver.stintStart shouldBe W + 140_000
            it.best!!.driver shouldBe "SAM"
        }
        // In a race: the change is a stint, Alex's.
        val race = RaceNowInput(listOf(RaceSessionInput("p2", setOf("c1"))))
        Standings.of("outback", "c1", box, runs, setOf("c1"), mapOf("c1" to sam), race, roster = roster, changes = changes).driver shouldBe
            DriverNow("Alex Rider", "ALE", W + 140_000)
    }
}
