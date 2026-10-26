package com.obd2dashboard.backend

import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.timing.CarRace
import com.obd2dashboard.backend.timing.Race
import com.obd2dashboard.backend.timing.RaceSessionInput
import com.obd2dashboard.backend.timing.RunLap
import com.obd2dashboard.backend.timing.RunTiming
import com.obd2dashboard.backend.timing.StintMark
import kotlin.math.roundToLong

/** The course, version and layout the server knows a car to be at (§22.7). */
data class CourseAt(val id: String, val version: Int, val layout: String)

/** A best lap (§22.7): who set it, and in which session and lap (the tablet's own number; null if re-timed). */
data class BestLap(val time: Double, val sectors: List<Double>, val driver: String?, val session: String, val lap: Int?)

/** Who's driving, and when the stint began on the tablet's `wall`. */
data class DriverNow(val name: String?, val code: String?, val stintStart: Long?)

/** During a race: its lap count, and when the car last left the pits on the tablet's `wall` (null while in them). */
data class RaceNow(val lap: Int, val leftPits: Long?)

/**
 * **Where a car stands** (M17.3): what the server knows and the tablet can't,
 * for its session [session]. Moments are on the tablet's `wall`; a frame turns
 * them into ages when it's sent (§22.8).
 */
data class Standing(
    val session: String,
    val course: CourseAt? = null,
    val best: BestLap? = null,
    val bestSectors: List<Double?>? = null,
    val driver: DriverNow? = null,
    val race: RaceNow? = null,
)

/**
 * The race's say, when the car's session is in it: its sessions so far (M26:
 * Saturday's and Sunday's, the car's in the last), flags already on the
 * tablet's clock.
 */
data class RaceNowInput(val sessions: List<RaceSessionInput>)

/** Pure: the car's runs in, where it stands out (M17.3). */
object Standings {
    /**
     * [runs]: the car's complete and provisional runs on [course], in order.
     * [counted]: the sessions whose laps can be the best (the event's, or all
     * at the layout). [drivers]: who drove each session.
     */
    fun of(
        car: String,
        session: String,
        course: CourseAt?,
        runs: List<RunTiming>,
        counted: Set<String>,
        drivers: Map<String, Driver?>,
        race: RaceNowInput? = null,
        /** Every driver by id: a driver change can name one whose session isn't in [drivers]. */
        roster: Map<String, Driver> = drivers.values.filterNotNull().associateBy { it.id },
        /** Driver changes within sessions (M27), by session, on `wall`: who drove from then. */
        changes: Map<String, List<StintMark>> = emptyMap(),
    ): Standing {
        val onLayout = runs.filter { course == null || it.layout == course.layout }
        // Until the session the car is in has a lap, nothing of the race, as at its very start (M26: Sunday's, before
        // its first crossing, isn't Saturday's stint going on).
        val carRace = race?.let { r ->
            Race.sessions(car, onLayout, r.sessions, drivers.mapValues { it.value?.id }, changes)
        }?.takeIf { it.sessions.lastOrNull()?.firstLap != null }
        val byId = roster
        // Who drove [session] at [at] on `wall` (M27): its last change before then, else its driver.
        fun driverAt(session: String, at: Long?): Driver? {
            val change = at?.let { t -> changes[session]?.lastOrNull { it.start < t } } ?: return drivers[session]
            return change.driver?.let(byId::get)
        }
        // In the race, a lap's driver is its stint's; elsewhere its session's, or the change's before it ended.
        fun driverOf(run: RunTiming, lap: RunLap): Driver? {
            val raceLap = carRace?.laps?.firstOrNull { it.session == lap.session && it.start == wall(run, lap.session, lap.startAt) }
            return raceLap?.let { l -> carRace.stints.getOrNull(l.stint - 1)?.driver?.let(byId::get) } ?: driverAt(lap.session, wall(run, lap.session, lap.endAt))
        }
        val counting = onLayout.flatMap { run -> run.laps.filter { it.session in counted }.map { run to it } }
        val best = counting.filter { (_, l) -> !l.pitIn && !l.pitOut }.minByOrNull { (_, l) -> l.time }?.let { (run, l) ->
            BestLap(ms(l.time), l.sectors.map(::ms), driverOf(run, l)?.code, l.session, l.tabletLap)
        }
        val sectors = counting.maxOfOrNull { it.second.sectors.size }?.takeIf { it > 0 }?.let { n ->
            (0 until n).map { i -> counting.map { it.second }.filter { it.sectors.size > i && countsForBest(it, i) }.minOfOrNull { ms(it.sectors[i]) } }
        }
        return Standing(
            session, course, best, sectors,
            // Who's driving now: the session's last change, if it has any (M27), else its driver.
            driver = driverNow(carRace, onLayout, session,
                drivers + (session to driverAt(session, Long.MAX_VALUE)), byId, changes[session]?.lastOrNull()?.start),
            race = carRace?.let { RaceNow(it.laps.size, leftPits(it)) },
        )
    }

    /** §22.6: an in-lap's last sector and an out-lap's first never count. */
    private fun countsForBest(lap: RunLap, i: Int) = !(lap.pitIn && i == lap.sectors.size - 1) && !(lap.pitOut && i == 0)

    private fun driverNow(race: CarRace?, runs: List<RunTiming>, session: String, drivers: Map<String, Driver?>, byId: Map<String, Driver>, changedAt: Long? = null): DriverNow? {
        if (race != null) {
            // In a race, the last stint's: its stints follow from who drove (decision 48), one rule.
            val stint = race.stints.lastOrNull()
            val driver = stint?.driver?.let(byId::get)
            return DriverNow(driver?.name, driver?.code, stint?.start)
        }
        val driver = drivers[session]
        // Outside a race, a stint runs from the last time the car left the pits (in any of its runs: a complete
        // one and a live one can be one run of the app), else the first lap of the run it's in.
        val run = runs.lastOrNull { session in it.sessions }
        val exit = runs.flatMap { r -> r.pitCrossings.filter { it.kind == "out" }.mapNotNull { c -> wall(r, c.session, c.at) } }.maxOrNull()
        val first = run?.laps?.firstOrNull()?.let { wall(run, it.session, it.startAt) }
        if (driver == null && run == null) return null
        // A driver change (M27) begins a stint too, if it's the latest.
        return DriverNow(driver?.name, driver?.code, listOfNotNull(exit ?: first, changedAt).maxOrNull())
    }

    /**
     * When the car last left the pits: the last stop's exit; before any stop, the first lap of the session it's in (M26:
     * Sunday's, not a stop on Saturday); null while in the pits.
     */
    private fun leftPits(race: CarRace): Long? {
        val firstLap = race.sessions.lastOrNull { it.firstLap != null }?.firstLap ?: 1
        val last = race.stops.lastOrNull { it.lap >= firstLap } ?: return race.laps.getOrNull(firstLap - 1)?.start
        return last.exit
    }

    private fun wall(run: RunTiming, session: String, at: Double): Long? = run.wallOffsets[session]?.let { (at + it).roundToLong() }

    private fun ms(seconds: Double): Double = (seconds * 1000).roundToLong() / 1000.0
}
