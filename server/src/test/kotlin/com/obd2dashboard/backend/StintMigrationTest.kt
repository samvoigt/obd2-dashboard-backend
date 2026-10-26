package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.DriverChange
import com.obd2dashboard.backend.events.Stint
import com.obd2dashboard.backend.timing.LapSource
import com.obd2dashboard.backend.timing.Race
import com.obd2dashboard.backend.timing.RunLap
import com.obd2dashboard.backend.timing.RunTiming
import com.obd2dashboard.backend.timing.StintMark
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Hand-edited stints into the sessions' drivers (M32.6): the race worked out from them is the race the edits made. */
class StintMigrationTest {
    private val sessions = listOf("s1", "s2", "s3", "s4")

    /** Laps of 70 s on `wall` from 0: s1 laps 1–5, s2 6–10, s3 11–15, s4 16–17. */
    private val run = RunTiming(2, "box", 1, "box", sessions,
        (0 until 17).map { i ->
            val session = when { i < 5 -> "s1"; i < 10 -> "s2"; i < 15 -> "s3"; else -> "s4" }
            RunLap(session, LapSource.RETIMED, i * 70_000.0, (i + 1) * 70_000.0, 70.0, emptyList(), false, false)
        }, emptyList(), sessions.associateWith { 0L }, emptyList())
    private fun start(lap: Int) = (lap - 1) * 70_000L

    /** NHMS's shapes: a change inside a session, a split at a session's start with the same driver, a stint with no laps, a new driver at a session's start. */
    private val marks = listOf(
        Stint(start(1), "d-sam"),
        Stint(start(4), "d-drew"), // inside s1
        Stint(start(6), "d-drew"), // s2's first lap, the same driver: the edits split there
        Stint(start(11) + 1_000, "d-paul"), // no lap ends before the next: empty
        Stint(start(11) + 2_000, "d-hugh"), // inside lap 11, s3's first
        Stint(start(16), "d-sam"), // s4's first lap, another driver
    )

    private val laps = Race.car("outback", listOf(run), sessions.toSet())!!.laps

    @Test
    fun `before - each lap the last mark's that began before it ended, the empty stint left out`() {
        StintMigration.before(laps, marks).let { d -> listOf(1, 3, 4, 5, 6, 10, 11, 15, 16, 17).map { d[it] } } shouldBe
            listOf("d-sam", "d-sam", "d-drew", "d-drew", "d-drew", "d-drew", "d-hugh", "d-hugh", "d-sam", "d-sam")
        StintMigration.beforeStints(laps, marks) shouldBe listOf(
            StintMigration.StintLaps("d-sam", 1, 3), StintMigration.StintLaps("d-drew", 4, 5), StintMigration.StintLaps("d-drew", 6, 10),
            StintMigration.StintLaps("d-hugh", 11, 15), StintMigration.StintLaps("d-sam", 16, 17),
        )
    }

    @Test
    fun `the plan - each session's driver at its first lap, a change at each stint inside it, a same-driver split kept`() {
        StintMigration.plan(laps, marks) shouldBe listOf(
            StintMigration.SessionPlan("s1", "d-sam", listOf(DriverChange(start(4), "d-drew"))),
            StintMigration.SessionPlan("s2", "d-drew", listOf(DriverChange(start(6), "d-drew"))),
            StintMigration.SessionPlan("s3", "d-hugh", listOf(DriverChange(start(11) + 2_000, "d-hugh"))),
            StintMigration.SessionPlan("s4", "d-sam", emptyList()),
        )
    }

    @Test
    fun `the race from the planned drivers is the race the edits made, lap for lap and stint for stint`() {
        val plan = StintMigration.plan(laps, marks)
        val after = Race.car("outback", listOf(run), sessions.toSet(),
            drivers = plan.associate { it.id to it.driver },
            changes = plan.associate { p -> p.id to p.changes.map { StintMark(it.from, it.driver) } })!!
        StintMigration.after(after, laps) shouldBe StintMigration.before(laps, marks)
        StintMigration.stintsOf(after, laps) shouldBe StintMigration.beforeStints(laps, marks)
    }

    @Test
    fun `a difference shows - the plan left alone, sessions' old drivers don't make the edited race`() {
        val stale = Race.car("outback", listOf(run), sessions.toSet(), drivers = mapOf("s1" to "d-sam", "s2" to "d-sam", "s3" to "d-sam", "s4" to "d-sam"))!!
        (StintMigration.after(stale, laps) == StintMigration.before(laps, marks)) shouldBe false
    }
}
