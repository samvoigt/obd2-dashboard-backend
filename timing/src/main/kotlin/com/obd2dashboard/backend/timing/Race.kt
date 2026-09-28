package com.obd2dashboard.backend.timing

import java.time.Instant
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/** A lap of the race (M15.2), numbered through it; times on the tablet's wall clock, milliseconds. */
@Serializable
public data class RaceLap(
    val number: Int,
    /** The session it ended in. */
    val session: String,
    val start: Long,
    val end: Long,
    /** Seconds, to the millisecond. */
    val time: Double,
    val sectors: List<Double>,
    val pitIn: Boolean,
    val pitOut: Boolean,
    /** `tablet`, `retimed`, or `restart`: the gap across a restart of the app, timed on `wall`. */
    val source: String,
    /** The stint it's in, from 1. */
    val stint: Int = 1,
    /** Where re-timing disagrees with the tablet's lap (decision 33). */
    val flag: Disagreement? = null,
)

/**
 * A stop (M15.2): the car's time in the pit lane, on [lap], the lap it entered
 * the lane in (its in-lap: the lane's entry comes before the pit line); no end
 * if the race ended there.
 */
@Serializable
public data class RaceStop(val lap: Int, val entry: Long, val exit: Long? = null, val seconds: Double? = null)

/** A stint (M15.2): a driver's run of laps, from [start] on the tablet's clock. */
@Serializable
public data class RaceStint(
    val number: Int,
    val driver: String? = null,
    val start: Long,
    val firstLap: Int? = null,
    val lastLap: Int? = null,
    val laps: Int = 0,
    val seconds: Double = 0.0,
    /** Its fastest lap on track, seconds. */
    val best: Double? = null,
)

/** One car's race (M15.2). */
@Serializable
public data class CarRace(
    val car: String,
    val laps: List<RaceLap>,
    val stops: List<RaceStop>,
    val stints: List<RaceStint>,
    /** From the first lap's start to the last lap's end, seconds. */
    val seconds: Double,
    /** The fastest lap on track (never an in- or out-lap, nor one across a restart). */
    val best: RaceLap? = null,
    /** The laps the green flag and the chequered flag fell in, if entered. */
    val greenLap: Int? = null,
    val flagLap: Int? = null,
    /** Whether the stints are the default (split at every stop) or as edited. */
    val stintsEdited: Boolean = false,
)

/** A stint's start as edited, on the tablet's clock, and who drove it. */
public data class StintMark(val start: Long, val driver: String?)

/**
 * **The race, one timeline** (M15.2): a car's race sessions, over one or more
 * runs of the app, made into laps numbered through the race, stops, stints,
 * and where the flags fell. Everything is on the tablet's wall clock, one
 * clock however far out, so every difference is right. Pure.
 */
public object Race {
    /**
     * [runs] in the server's order, each on the event's course; only laps and
     * crossings in [raceSessions] count. [drivers] is who drove each session
     * (seeding default stints); [edited], if any, replaces them; [green] and
     * [flag] are the entered start and finish, already on the tablet's clock.
     */
    public fun car(
        car: String,
        runs: List<RunTiming>,
        raceSessions: Set<String>,
        drivers: Map<String, String?> = emptyMap(),
        edited: List<StintMark>? = null,
        green: Long? = null,
        flag: Long? = null,
    ): CarRace? {
        val parts = runs.map { run -> lapsOf(run, raceSessions) to crossingsOf(run, raceSessions) }
        val raw = mutableListOf<RaceLap>()
        for ((laps, _) in parts) {
            val before = raw.lastOrNull()
            val first = laps.firstOrNull()
            // Across a restart of the app: the gap from the last crossing to the next is one lap, timed on `wall`.
            if (before != null && first != null && first.start > before.end) {
                raw += RaceLap(0, first.session, before.end, first.start, seconds(first.start - before.end), emptyList(),
                    pitIn = first.pitOut, pitOut = before.pitIn, source = RESTART)
            }
            raw += laps
        }
        if (raw.isEmpty()) return null
        val numbered = raw.mapIndexed { i, lap -> lap.copy(number = i + 1) }
        val stops = stopsOf(parts.flatMap { it.second }.sortedBy { it.second }, numbered)
        val marks = (edited?.takeIf { it.isNotEmpty() } ?: defaultStints(numbered, stops, drivers)).sortedBy { it.start }
        val laps = numbered.map { lap -> lap.copy(stint = marks.indexOfLast { it.start <= lap.end }.coerceAtLeast(0) + 1) }
        val stints = marks.mapIndexed { i, m ->
            val mine = laps.filter { it.stint == i + 1 }
            RaceStint(i + 1, m.driver, m.start, mine.firstOrNull()?.number, mine.lastOrNull()?.number, mine.size,
                seconds(mine.sumOf { it.time } * 1000), mine.filter(::onTrack).minOfOrNull { it.time })
        }
        return CarRace(
            car, laps, stops, stints,
            seconds = seconds(laps.last().end - laps.first().start),
            best = laps.filter(::onTrack).minByOrNull { it.time },
            greenLap = green?.let { t -> laps.firstOrNull { it.end > t }?.number },
            flagLap = flag?.let { t -> laps.firstOrNull { it.end > t }?.number },
            stintsEdited = edited != null && edited.isNotEmpty(),
        )
    }

    /** Several of our cars: most laps first, then the shortest time. */
    public fun classify(cars: List<CarRace>): List<CarRace> = cars.sortedWith(compareByDescending<CarRace> { it.laps.size }.thenBy { it.seconds })

    /**
     * How far the tablet's clock is behind the server's, from its sessions:
     * the smallest `created − started`. A session the live lane announced is
     * created within seconds of its start; one uploaded at its end only ever
     * gives more.
     */
    public fun tabletOffset(sessions: List<Pair<Instant, Long>>): Long? =
        sessions.minOfOrNull { (created, started) -> created.toEpochMilli() - started }

    public fun onTrack(lap: RaceLap): Boolean = !lap.pitIn && !lap.pitOut && lap.source != RESTART

    private fun lapsOf(run: RunTiming, race: Set<String>): List<RaceLap> = run.laps.mapNotNull { lap ->
        if (lap.session !in race) return@mapNotNull null
        val offset = run.wallOffsets[lap.session] ?: return@mapNotNull null
        RaceLap(
            0, lap.session, (lap.startAt + offset).roundToLong(), (lap.endAt + offset).roundToLong(),
            (lap.time * 1000).roundToLong() / 1000.0, lap.sectors.map { (it * 1000).roundToLong() / 1000.0 },
            lap.pitIn, lap.pitOut, if (lap.source == LapSource.TABLET) "tablet" else "retimed", flag = lap.flag,
        )
    }

    private fun crossingsOf(run: RunTiming, race: Set<String>): List<Pair<String, Long>> = run.pitCrossings.mapNotNull { c ->
        if (c.session !in race) return@mapNotNull null
        val offset = run.wallOffsets[c.session] ?: return@mapNotNull null
        c.kind to (c.at + offset).roundToLong()
    }

    /** Each entry paired with the next exit; an entry with no exit is a stop with no end. */
    private fun stopsOf(crossings: List<Pair<String, Long>>, laps: List<RaceLap>): List<RaceStop> {
        val stops = mutableListOf<RaceStop>()
        var entry: Long? = null
        fun close(exit: Long?) {
            val e = entry ?: return
            stops += RaceStop(laps.firstOrNull { it.end > e }?.number ?: laps.size, e, exit, exit?.let { seconds(it - e) })
            entry = null
        }
        for ((kind, at) in crossings) {
            if (kind == "in") { close(null); entry = at } else close(at)
        }
        close(null)
        return stops
    }

    /** A stint from the first lap, and one from each stop's exit (its entry if none), each driven by its first lap's session's driver. */
    private fun defaultStints(laps: List<RaceLap>, stops: List<RaceStop>, drivers: Map<String, String?>): List<StintMark> {
        fun driverAfter(t: Long) = laps.firstOrNull { it.end > t }?.let { drivers[it.session] }
        val first = laps.first().start
        return listOf(StintMark(first, driverAfter(first))) + stops.map { s -> (s.exit ?: s.entry).let { StintMark(it, driverAfter(it)) } }
    }

    private fun seconds(ms: Number): Double = (ms.toDouble()).roundToLong() / 1000.0

    private const val RESTART = "restart"
}
