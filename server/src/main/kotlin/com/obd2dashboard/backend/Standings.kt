package com.obd2dashboard.backend

import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.timing.CarRace
import com.obd2dashboard.backend.timing.Race
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

/** The race part's say, when the car's session is in it. Flags already on the tablet's clock. */
data class RaceNowInput(val sessions: Set<String>, val edited: List<StintMark>?, val green: Long?, val flag: Long?)

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
        /** Every driver by id: edited stints can name one who drove no session. */
        roster: Map<String, Driver> = drivers.values.filterNotNull().associateBy { it.id },
    ): Standing {
        val onLayout = runs.filter { course == null || it.layout == course.layout }
        val carRace = race?.let { r ->
            Race.car(car, onLayout, r.sessions, drivers.mapValues { it.value?.id }, r.edited?.takeIf { it.isNotEmpty() }, r.green, r.flag)
        }
        val byId = roster
        // In the race, a lap's driver is its stint's; elsewhere its session's.
        fun driverOf(run: RunTiming, lap: RunLap): Driver? {
            val raceLap = carRace?.laps?.firstOrNull { it.session == lap.session && it.start == wall(run, lap.session, lap.startAt) }
            return raceLap?.let { l -> carRace.stints.getOrNull(l.stint - 1)?.driver?.let(byId::get) } ?: drivers[lap.session]
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
            driver = driverNow(carRace, race?.edited?.isNotEmpty() == true, onLayout, session, drivers, byId),
            race = carRace?.let { RaceNow(it.laps.size, leftPits(it)) },
        )
    }

    /** §22.6: an in-lap's last sector and an out-lap's first never count. */
    private fun countsForBest(lap: RunLap, i: Int) = !(lap.pitIn && i == lap.sectors.size - 1) && !(lap.pitOut && i == 0)

    private fun driverNow(race: CarRace?, edited: Boolean, runs: List<RunTiming>, session: String, drivers: Map<String, Driver?>, byId: Map<String, Driver>): DriverNow? {
        if (race != null) {
            // Stints the crew edited say who; else the session's driver as set (from the car page, say), else the stint's.
            val stint = race.stints.lastOrNull()
            val ofStint = stint?.driver?.let(byId::get)
            val driver = if (edited) ofStint ?: drivers[session] else drivers[session] ?: ofStint
            return DriverNow(driver?.name, driver?.code, stint?.start)
        }
        val driver = drivers[session]
        // Outside a race, a stint runs from the last time the car left the pits (in any of its runs: a complete
        // one and a live one can be one run of the app), else the first lap of the run it's in.
        val run = runs.lastOrNull { session in it.sessions }
        val exit = runs.flatMap { r -> r.pitCrossings.filter { it.kind == "out" }.mapNotNull { c -> wall(r, c.session, c.at) } }.maxOrNull()
        val first = run?.laps?.firstOrNull()?.let { wall(run, it.session, it.startAt) }
        if (driver == null && run == null) return null
        return DriverNow(driver?.name, driver?.code, exit ?: first)
    }

    /** When the car last left the pits: the last stop's exit; before any stop, the race's first lap; null while in the pits. */
    private fun leftPits(race: CarRace): Long? {
        val last = race.stops.lastOrNull() ?: return race.laps.firstOrNull()?.start
        return last.exit
    }

    private fun wall(run: RunTiming, session: String, at: Double): Long? = run.wallOffsets[session]?.let { (at + it).roundToLong() }

    private fun ms(seconds: Double): Double = (seconds * 1000).roundToLong() / 1000.0
}
