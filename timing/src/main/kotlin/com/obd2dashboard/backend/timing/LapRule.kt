package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.CourseShape
import com.obd2dashboard.backend.courses.LonLat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign

/** A fix: where the car was, and when, in milliseconds on the tablet's `at` clock (its `fixAt`, else `at`). */
public data class Fix(val lon: Double, val lat: Double, val at: Double)

/**
 * A lap the rule timed: when it began and ended (milliseconds, `at`'s clock),
 * its time and sectors in seconds as a `lap` record has them (§16, §22.6).
 */
public data class TimedLap(
    val number: Int,
    val startAt: Double,
    val endAt: Double,
    val pitIn: Boolean,
    val pitOut: Boolean,
    /** As far as the lap went: complete when every sector line was crossed, else up to the first missed; none without sectors. */
    val sectors: List<Double>,
) {
    val time: Double get() = (endAt - startAt) / 1000.0
}

/**
 * **The tablet's lap rule** (its `core/laps` `LapTimer`, contract §16, §22.5,
 * §22.6), so the server re-times the tablet's own fixes exactly as the tablet
 * would (M13.1, decision 31):
 *
 * - each **move** from one fix to the next is tested against every line; a
 *   line counts **only in its direction** (the layout's where it crosses the
 *   layout, the pit lane's for the pit line), its moment **interpolated along
 *   the move**; several in one move count in order along it;
 * - **the first start/finish (or pit line) crossing starts timing**; each later
 *   one ends a lap, but only once the car has been [armAt] from the
 *   start/finish since the last, so a car sat on the line makes no laps;
 * - **the pit line** ends an in-lap and starts an out-lap: the course's own,
 *   else one made across the pit lane level with the start/finish;
 * - **sectors only if every sector line cuts the layout exactly once**; only
 *   the **next** line counts, a missed one stops that lap's sectors; the last
 *   ends the lap, recorded only if every line before it was crossed;
 * - **a start/finish that misses the layout times nothing**, never an error.
 */
public class LapRule(shape: CourseShape, layoutId: String) {
    private val layout = shape.layouts.firstOrNull { it.id == layoutId }
        ?: throw IllegalArgumentException("no layout \"$layoutId\"")
    private val frame = Frame(shape.defaultLayout.path.first())
    private val lane = layout.path.map(frame::xy)
    private val laneLength = lane.zipWithNext().sumOf { (a, b) -> (b - a).length }

    private class Line(val a: Xy, val b: Xy, val forward: Double, val pit: Boolean = false, val sector: Int? = null) {
        val gate: Xy get() = b - a
    }

    private val ends: List<Line> = buildList {
        val sf = layout.startFinish
        val a = frame.xy(sf.a)
        val b = frame.xy(sf.b)
        forwardSign(lane, a, b)?.let { add(Line(a, b, it)) }
        val pitLane = shape.pitLane?.takeIf { it.size >= 2 }?.map(frame::xy)
        val pit = shape.pitLine?.let { frame.xy(it.a) to frame.xy(it.b) } ?: pitLane?.let { pitGate(it, a, b) }
        if (pit != null && pitLane != null) forwardSign(pitLane, pit.first, pit.second)?.let { add(Line(pit.first, pit.second, it, pit = true)) }
    }

    private val sectorLines: List<Line> = layout.sectors.mapIndexed { i, gate ->
        val a = frame.xy(gate.a)
        val b = frame.xy(gate.b)
        forwardSign(lane, a, b)?.takeIf { crossings(lane, a, b) == 1 }?.let { Line(a, b, it, sector = i) }
    }.let { lines -> if (lines.all { it != null }) lines.filterNotNull() else emptyList() }

    /** Whether this layout can be timed at all: its start/finish crosses it. */
    public val canTime: Boolean = ends.any { !it.pit }

    /** Whether laps are timed in sectors: there are sector lines, each cutting the layout once. */
    public val hasSectors: Boolean get() = sectorLines.isNotEmpty()

    /** How far from the start/finish's middle a car must go before its next crossing counts. */
    public val armAt: Double = minOf(ARM_METRES, laneLength / 4)

    private val gateMiddle = frame.xy(layout.startFinish.a).let { a -> a + (frame.xy(layout.startFinish.b) - a) * 0.5 }
    private var armed = true
    private var last: Xy? = null
    private var lastAt = 0.0
    private var startedInPits = false
    private var lapStartedAt: Double? = null
    private var lapSectors: List<Double> = emptyList()
    private var sectorStartedAt = 0.0
    private var laps = 0

    /** A fix; the lap it completed, if any. */
    public fun offer(fix: Fix): TimedLap? {
        val p = frame.xy(LonLat(fix.lon, fix.lat))
        val previous = last
        val previousAt = lastAt
        last = p
        lastAt = fix.at
        if ((p - gateMiddle).length >= armAt) armed = true
        if (previous == null || !canTime) return null
        val move = p - previous
        val hits = (ends + sectorLines)
            .mapNotNull { gate -> crossing(previous, p, gate.a, gate.b)?.takeIf { (move crossZ gate.gate).sign == gate.forward }?.let { gate to it } }
            .sortedBy { it.second }
        var completed: TimedLap? = null
        for ((gate, t) in hits) {
            val at = previousAt + (fix.at - previousAt) * t
            val sector = gate.sector
            if (sector != null) passSector(sector, at) else if (armed) completed = endLap(gate, at) ?: completed
        }
        return completed
    }

    private fun passSector(index: Int, at: Double) {
        if (lapStartedAt == null || index != lapSectors.size) return
        lapSectors = lapSectors + (at - sectorStartedAt) / 1000.0
        sectorStartedAt = at
    }

    private fun endLap(gate: Line, at: Double): TimedLap? {
        armed = false
        val started = lapStartedAt
        val fromPits = startedInPits
        val sectors = if (hasSectors && lapSectors.size == sectorLines.size) lapSectors + (at - sectorStartedAt) / 1000.0 else lapSectors
        lapStartedAt = at
        startedInPits = gate.pit
        lapSectors = emptyList()
        sectorStartedAt = at
        if (started == null) return null
        laps++
        return TimedLap(laps, started, at, pitIn = gate.pit, pitOut = fromPits, sectors = sectors)
    }

    public companion object {
        /** Far past any GPS error, and less than the far side of any circuit (the tablet's `ARM_METRES`). */
        public const val ARM_METRES: Double = 150.0

        /** Half the fallback pit line: a pit lane's width and a GPS error, short of a track beside it. */
        public const val PIT_GATE_HALF_WIDTH: Double = 8.0

        /** Every lap [fixes] make on [layoutId] of [shape], in order. */
        public fun laps(shape: CourseShape, layoutId: String, fixes: Iterable<Fix>): List<TimedLap> {
            val rule = LapRule(shape, layoutId)
            return fixes.mapNotNull(rule::offer)
        }

        /** The sign a forward crossing of [a]–[b] has, from the segment of [points] that crosses it; null if none does. */
        private fun forwardSign(points: List<Xy>, a: Xy, b: Xy): Double? {
            val across = (0 until points.size - 1).firstOrNull { crossing(points[it], points[it + 1], a, b) != null } ?: return null
            return ((points[across + 1] - points[across]) crossZ (b - a)).sign
        }

        private fun crossings(points: List<Xy>, a: Xy, b: Xy): Int =
            (0 until points.size - 1).count { crossing(points[it], points[it + 1], a, b) != null }

        /**
         * The fallback pit line (the tablet's `pitGate`): square to the open pit
         * lane where it passes nearest the start/finish's middle, 8 m each side.
         */
        private fun pitGate(lane: List<Xy>, sfA: Xy, sfB: Xy): Pair<Xy, Xy>? {
            val middle = (sfA + sfB) * 0.5
            val along = project(lane, middle)
            val centre = at(lane, along)
            val ahead = at(lane, along + 1.0) - at(lane, along - 1.0)
            if (ahead.length == 0.0) return null
            val across = Xy(-ahead.y, ahead.x) * (PIT_GATE_HALF_WIDTH / ahead.length)
            return (centre + across) to (centre - across)
        }

        /** How far along an open line the point nearest [p] is. */
        private fun project(points: List<Xy>, p: Xy): Double {
            var best = Double.MAX_VALUE
            var along = 0.0
            var run = 0.0
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val ab = points[i + 1] - a
                val l2 = ab dot ab
                val seg = kotlin.math.sqrt(l2)
                if (l2 != 0.0) {
                    val t = (((p - a) dot ab) / l2).coerceIn(0.0, 1.0)
                    val offset = (p - (a + ab * t)).length
                    if (offset < best) { best = offset; along = run + t * seg }
                }
                run += seg
            }
            return along
        }

        /** The point [distance] metres along an open line, clamped to its ends (the tablet's `Polyline.at`, open). */
        private fun at(points: List<Xy>, distance: Double): Xy {
            val length = points.zipWithNext().sumOf { (a, b) -> (b - a).length }
            var d = distance.coerceIn(0.0, length)
            for (i in 0 until points.size - 1) {
                val segment = (points[i + 1] - points[i]).length
                if (d <= segment) return points[i] + (points[i + 1] - points[i]) * (if (segment == 0.0) 0.0 else d / segment)
                d -= segment
            }
            return points.last()
        }
    }
}

/** A point in a [Frame]: metres east and north. */
internal data class Xy(val x: Double, val y: Double) {
    operator fun minus(o: Xy): Xy = Xy(x - o.x, y - o.y)
    operator fun plus(o: Xy): Xy = Xy(x + o.x, y + o.y)
    operator fun times(k: Double): Xy = Xy(x * k, y * k)
    infix fun dot(o: Xy): Double = x * o.x + y * o.y
    infix fun crossZ(o: Xy): Double = x * o.y - y * o.x
    val length: Double get() = hypot(x, y)
}

/** Metres east and north of [origin], equirectangular, as the tablet's `LocalFrame`. */
internal class Frame(private val origin: LonLat) {
    private val metresPerLon = METRES_PER_DEGREE * cos(Math.toRadians(origin.lat))

    fun xy(p: LonLat): Xy = Xy((p.lon - origin.lon) * metresPerLon, (p.lat - origin.lat) * METRES_PER_DEGREE)

    companion object {
        const val METRES_PER_DEGREE: Double = 111_195.0
    }
}

/**
 * Where the segment [p1]→[p2] crosses [a]–[b], as a fraction along [p1]→[p2],
 * or null if it doesn't. Touching an end counts (the tablet's `crossing`).
 */
internal fun crossing(p1: Xy, p2: Xy, a: Xy, b: Xy): Double? {
    val r = p2 - p1
    val s = b - a
    val denominator = r crossZ s
    if (denominator == 0.0) return null
    val t = ((a - p1) crossZ s) / denominator
    val u = ((a - p1) crossZ r) / denominator
    return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
}
