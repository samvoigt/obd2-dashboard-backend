package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.CourseShape
import com.obd2dashboard.backend.courses.LonLat
import kotlin.math.hypot
import kotlinx.serialization.Serializable

/** A pit lane line crossed in the lane's direction (M15.1): into the lane, or out of it. */
@Serializable
public data class PitCrossing(
    /** The session the fix that crossed it is in. */
    val session: String,
    /** `in` or `out`. */
    val kind: String,
    /** The moment, on `at`'s clock, milliseconds, interpolated along the move. */
    val at: Double,
)

/**
 * **Stops, from the fixes** (M15.1): the pit lane's entry and exit lines, and
 * each time a car crosses one in the lane's direction. A stop runs from an
 * entry to the next exit (M15.2).
 *
 * The lines are the course's `pit_in` and `pit_out` where drawn. **Else they're
 * made square to the lane where it first comes clear of every layout by
 * [CLEAR_METRES]**, not at its very ends: a pit lane begins and ends on the
 * track (NHMS's does, to the metre), and a line there would be crossed by cars
 * that never pit.
 *
 * The lap rule is the tablet's and stays so (M13.1); this is a second reader of
 * the same fixes, in the same frame.
 */
public class PitLane(shape: CourseShape) {
    private val frame = Frame(shape.defaultLayout.path.first())
    private val lane = shape.pitLane?.takeIf { it.size >= 2 }?.map(frame::xy)
    private val tracks = shape.layouts.map { l -> l.path.map(frame::xy) }

    private class Gate(val kind: String, val a: Xy, val b: Xy, val forward: Double)

    private val gates: List<Gate> = if (lane == null) emptyList() else listOfNotNull(
        gate("in", shape.pitIn?.let { frame.xy(it.a) to frame.xy(it.b) } ?: made(fromStart = true)),
        gate("out", shape.pitOut?.let { frame.xy(it.a) to frame.xy(it.b) } ?: made(fromStart = false)),
    )

    /** Whether stops can be timed here: a pit lane, and both lines crossing it. */
    public val canTime: Boolean = gates.size == 2

    private var last: Xy? = null
    private var lastAt = 0.0

    /** A fix; the lines its move crossed, in order along the move. */
    public fun offer(session: String, fix: Fix): List<PitCrossing> {
        val p = frame.xy(LonLat(fix.lon, fix.lat))
        val previous = last
        val previousAt = lastAt
        last = p
        lastAt = fix.at
        if (previous == null) return emptyList()
        val move = p - previous
        return gates.mapNotNull { g ->
            crossing(previous, p, g.a, g.b)?.takeIf { kotlin.math.sign(move crossZ (g.b - g.a)) == g.forward }?.let { t ->
                PitCrossing(session, g.kind, previousAt + (fix.at - previousAt) * t)
            }
        }.sortedBy { it.at }
    }

    private fun gate(kind: String, line: Pair<Xy, Xy>?): Gate? {
        val (a, b) = line ?: return null
        val forward = LapRule.forwardSign(lane ?: return null, a, b) ?: return null
        return Gate(kind, a, b, forward)
    }

    /** A line square to the lane where it's first (or last) clear of every layout by [CLEAR_METRES]. */
    private fun made(fromStart: Boolean): Pair<Xy, Xy>? {
        val lane = lane ?: return null
        val length = lane.zipWithNext().sumOf { (a, b) -> (b - a).length }
        val steps = (0..length.toInt()).map { it.toDouble() }.let { if (fromStart) it else it.reversed() }
        val along = steps.firstOrNull { d -> distanceToTracks(LapRule.at(lane, d)) >= CLEAR_METRES } ?: return null
        val centre = LapRule.at(lane, along)
        val ahead = LapRule.at(lane, along + 1.0) - LapRule.at(lane, along - 1.0)
        if (ahead.length == 0.0) return null
        val across = Xy(-ahead.y, ahead.x) * (LapRule.PIT_GATE_HALF_WIDTH / ahead.length)
        return (centre + across) to (centre - across)
    }

    private fun distanceToTracks(p: Xy): Double = tracks.minOf { path ->
        path.zipWithNext().minOfOrNull { (a, b) -> segmentDistance(p, a, b) } ?: Double.MAX_VALUE
    }

    public companion object {
        /** How clear of the track a made line must be: 8 m of line, then 8 m for the track's width and GPS error. */
        public const val CLEAR_METRES: Double = 16.0

        /** Every lane line [fixes] of one session cross, in order. */
        public fun crossings(shape: CourseShape, session: String, fixes: Iterable<Fix>): List<PitCrossing> {
            val lane = PitLane(shape)
            return fixes.flatMap { lane.offer(session, it) }
        }
    }
}

private fun segmentDistance(p: Xy, a: Xy, b: Xy): Double {
    val ab = b - a
    val l2 = ab dot ab
    val t = if (l2 == 0.0) 0.0 else (((p - a) dot ab) / l2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + ab.x * t), p.y - (a.y + ab.y * t))
}
