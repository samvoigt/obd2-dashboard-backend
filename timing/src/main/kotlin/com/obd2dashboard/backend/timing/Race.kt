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
    /** In a session still being driven or uploaded: provisional (M17.6). */
    val live: Boolean = false,
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
    /** How consistent its laps on track were (M16.2). */
    val consistency: Consistency? = null,
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
    /** A race in sessions (M26): where each began, its laps, time and flags. One for a race run in one. */
    val sessions: List<RaceSessionSpan> = emptyList(),
    /** The best of each sector over the race (§22.6's rule), and their sum when every sector has one (M16.2). */
    val bestSectors: List<Double?> = emptyList(),
    val theoretical: Double? = null,
)

/** One session of a race in sessions (M26): its part, its first lap (none if the car didn't run in it), its laps and time, and where its flags fell. */
@Serializable
public data class RaceSessionSpan(
    val part: String,
    val firstLap: Int? = null,
    val laps: Int = 0,
    val seconds: Double = 0.0,
    val greenLap: Int? = null,
    val flagLap: Int? = null,
)

/** A session of the race (M26), a race part: its sessions, flags already on the tablet's clock, and stints as edited. */
public data class RaceSessionInput(
    val part: String,
    val sessions: Set<String>,
    val green: Long? = null,
    val flag: Long? = null,
    val edited: List<StintMark>? = null,
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
        /** Driver changes within sessions (M27), by session, on `wall`: each starts a default stint, and is who drove from then. */
        changes: Map<String, List<StintMark>> = emptyMap(),
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
        val marks = (edited?.takeIf { it.isNotEmpty() } ?: stintsOf(numbered, drivers, changes.filterKeys { it in raceSessions })).sortedBy { it.start }
        // A lap is the stint's that began before it ended: a stint split where lap N starts holds lap N, not N − 1.
        val laps = numbered.map { lap -> lap.copy(stint = marks.indexOfLast { it.start < lap.end }.coerceAtLeast(0) + 1) }
        val stints = marks.mapIndexed { i, m ->
            val mine = laps.filter { it.stint == i + 1 }
            RaceStint(i + 1, m.driver, m.start, mine.firstOrNull()?.number, mine.lastOrNull()?.number, mine.size,
                seconds(mine.sumOf { it.time } * 1000), mine.filter(::onTrack).minOfOrNull { it.time },
                Consistency.of(mine.filter(::onTrack).map { it.time }))
        }
        return CarRace(
            car, laps, stops, stints,
            seconds = seconds(laps.last().end - laps.first().start),
            best = laps.filter(::onTrack).minByOrNull { it.time },
            greenLap = green?.let { t -> laps.firstOrNull { it.end > t }?.number },
            flagLap = flag?.let { t -> laps.firstOrNull { it.end > t }?.number },
            stintsEdited = edited != null && edited.isNotEmpty(),
            bestSectors = bestSectors(laps),
            theoretical = Consistency.theoreticalBest(bestSectors(laps)),
        )
    }

    /**
     * **A race in sessions** (M26: practice Friday, the race Saturday and
     * Sunday, the car off track overnight): each session timed as [car] times
     * a race, then joined. Laps and stints numbered through; no lap across the
     * break between two (a restart within one is still bridged); a stint never
     * crosses one; the time is each session's added up, so the night never
     * counts. One session is exactly [car].
     */
    public fun sessions(
        car: String,
        runs: List<RunTiming>,
        sessions: List<RaceSessionInput>,
        drivers: Map<String, String?> = emptyMap(),
        /** Driver changes within sessions (M27), by session, on `wall`. */
        changes: Map<String, List<StintMark>> = emptyMap(),
    ): CarRace? {
        val each = sessions.map { s -> s to car(car, runs, s.sessions, drivers, s.edited, s.green, s.flag, changes) }
        val raced = each.mapNotNull { it.second }
        if (raced.isEmpty()) return null
        val laps = mutableListOf<RaceLap>()
        val stops = mutableListOf<RaceStop>()
        val stints = mutableListOf<RaceStint>()
        val spans = mutableListOf<RaceSessionSpan>()
        for ((input, race) in each) {
            if (race == null) { spans += RaceSessionSpan(input.part); continue }
            val lapsBefore = laps.size
            val stintsBefore = stints.size
            fun lapNumber(n: Int?) = n?.let { it + lapsBefore }
            laps += race.laps.map { it.copy(number = it.number + lapsBefore, stint = it.stint + stintsBefore) }
            stops += race.stops.map { it.copy(lap = it.lap + lapsBefore) }
            stints += race.stints.map { it.copy(number = it.number + stintsBefore, firstLap = lapNumber(it.firstLap), lastLap = lapNumber(it.lastLap)) }
            spans += RaceSessionSpan(input.part, lapsBefore + 1, race.laps.size, race.seconds, lapNumber(race.greenLap), lapNumber(race.flagLap))
        }
        val best = bestSectors(laps)
        return CarRace(
            car, laps, stops, stints,
            seconds = seconds(raced.sumOf { it.seconds } * 1000),
            best = laps.filter(::onTrack).minByOrNull { it.time },
            // The race's own: its first session's green flag, its last session's flag.
            greenLap = spans.firstOrNull { it.firstLap != null }?.greenLap,
            flagLap = spans.lastOrNull { it.firstLap != null }?.flagLap,
            stintsEdited = raced.any { it.stintsEdited },
            bestSectors = best,
            theoretical = Consistency.theoreticalBest(best),
            sessions = spans,
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

    /** Whether sector [i] of [lap] can be a best (§22.6): not an in-lap's last, nor an out-lap's first. */
    public fun countsForBest(lap: RaceLap, i: Int): Boolean = !(lap.pitIn && i == lap.sectors.size - 1) && !(lap.pitOut && i == 0)

    /** The best of each sector over [laps], by §22.6's rule; as many as the most any lap has. */
    public fun bestSectors(laps: List<RaceLap>): List<Double?> {
        val n = laps.maxOfOrNull { it.sectors.size } ?: 0
        return (0 until n).map { i -> laps.filter { it.sectors.size > i && countsForBest(it, i) }.minOfOrNull { it.sectors[i] } }
    }

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

    /**
     * **Stints from who drove** (M32, decision 48): one from the first lap; one at each driver change inside the race
     * (M27), a change to the same driver included (a double stint, entered at the stop); and one where a session starts
     * with a driver other than the one already driving. A session nobody set carries on whoever was driving (a restart
     * of the app, say, before anyone set it). Stops split nothing. Each stint is driven by whoever drove its first lap:
     * its session's driver, or the change before the lap ended, or, in a session nobody set, the driver before it.
     */
    private fun stintsOf(laps: List<RaceLap>, drivers: Map<String, String?>, changes: Map<String, List<StintMark>>): List<StintMark> {
        // Who drove each lap, in order: a session nobody set takes the driver before it.
        val driverOf = HashMap<Int, String?>()
        var carried: String? = null
        for (lap in laps) {
            val change = changes[lap.session]?.lastOrNull { it.start < lap.end }
            val who = if (change != null) change.driver else drivers[lap.session] ?: carried
            driverOf[lap.number] = who
            carried = who
        }
        fun driverAfter(t: Long) = laps.firstOrNull { it.end > t }?.let { driverOf[it.number] }
        val first = laps.first().start
        // A session's start, when it was set to someone other than who was driving at the end of the one before.
        val sessionStarts = laps.zipWithNext().filter { (before, lap) ->
            lap.session != before.session && drivers[lap.session] != null && drivers[lap.session] != driverOf[before.number]
        }.map { (_, lap) -> StintMark(lap.start, driverAfter(lap.start)) }
        val inRace = changes.values.flatten().filter { c -> c.start > first && c.start < laps.last().end }
        return (listOf(StintMark(first, driverAfter(first))) + sessionStarts + inRace.map { StintMark(it.start, driverAfter(it.start)) })
            .distinctBy { it.start }
    }

    private fun seconds(ms: Number): Double = (ms.toDouble()).roundToLong() / 1000.0

    private const val RESTART = "restart"
}
