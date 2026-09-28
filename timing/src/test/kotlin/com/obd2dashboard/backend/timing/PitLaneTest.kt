package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.CourseShape
import com.obd2dashboard.backend.courses.Layout
import com.obd2dashboard.backend.courses.Line
import com.obd2dashboard.backend.courses.LonLat
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.hypot
import org.junit.Test

private const val LON0 = -71.4611
private const val LAT0 = 43.3626
private val perLon = Frame.METRES_PER_DEGREE * cos(Math.toRadians(LAT0))
private fun ll(x: Double, y: Double) = LonLat(LON0 + x / perLon, LAT0 + y / Frame.METRES_PER_DEGREE)

/**
 * The box (1000 × 400 m) with a pit lane inside its bottom straight: off the
 * track at x = 150 up a ramp to y = 30, along to x = 750, down to the track at
 * x = 850. 708.8 m of lane; the track's bottom straight is y = 0.
 */
private val laneXy = listOf(150.0 to 0.0, 250.0 to 30.0, 750.0 to 30.0, 850.0 to 0.0)
private val ramp = hypot(100.0, 30.0)
private val laneLength = 2 * ramp + 500

private fun shape(pitIn: Line? = null, pitOut: Line? = null, lane: Boolean = true) = CourseShape(
    layouts = listOf(Layout("box", "Box", true, listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 400.0, 0.0 to 400.0, 0.0 to 0.0).map { (x, y) -> ll(x, y) },
        Line(ll(500.0, -15.0), ll(500.0, 15.0)), emptyList())),
    pitLane = if (lane) laneXy.map { (x, y) -> ll(x, y) } else null, pitIn = pitIn, pitOut = pitOut, pitLine = null,
)

/** Where the car is [s] metres along the lane. */
private fun onLane(s: Double): Pair<Double, Double> {
    val d = s.coerceIn(0.0, laneLength)
    return when {
        d < ramp -> (150 + 100 * d / ramp) to (30 * d / ramp)
        d < ramp + 500 -> (250 + (d - ramp)) to 30.0
        else -> (750 + 100 * (d - ramp - 500) / ramp) to (30 - 30 * (d - ramp - 500) / ramp)
    }
}

/** At 10 m/s from the lane's start, standing 60 s at its middle, a fix a second. */
private fun pitStop(): List<Fix> = (0..140).map { t ->
    val mid = laneLength / 2
    val s = when {
        t * 10.0 < mid -> t * 10.0
        t * 10.0 < mid + 600 -> mid
        else -> t * 10.0 - 600
    }
    val (x, y) = onLane(s)
    val p = ll(x, y)
    Fix(p.lon, p.lat, t * 1000.0)
}

class PitLaneTest {
    @Test
    fun `a stop is timed from where the lane leaves the track to where it rejoins, to its true length`() {
        val crossings = PitLane.crossings(shape(), "s", pitStop())
        crossings.map { it.kind } shouldBe listOf("in", "out")
        // Clear of the track by 16 m at 56 m along (y = 16.09 on the ramp), and last at 653 m.
        crossings[0].at shouldBe (5_600.0 plusOrMinus 1e-6)
        crossings[1].at shouldBe (125_300.0 plusOrMinus 1e-6)
        crossings.map { it.session } shouldBe listOf("s", "s")
    }

    @Test
    fun `drawn pit lines win over made ones`() {
        val drawn = shape(pitIn = Line(ll(400.0, 15.0), ll(400.0, 45.0)), pitOut = Line(ll(600.0, 15.0), ll(600.0, 45.0)))
        val crossings = PitLane.crossings(drawn, "s", pitStop())
        crossings[0].at shouldBe ((ramp + 150) * 100 plusOrMinus 1e-6)
        crossings[1].at shouldBe ((ramp + 350) * 100 + 60_000 plusOrMinus 1e-6)
    }

    @Test
    fun `only in the lane's direction, never by a car on track, and nothing without a lane`() {
        PitLane.crossings(shape(), "s", pitStop().reversed().mapIndexed { i, f -> f.copy(at = i * 1000.0) }) shouldBe emptyList()
        // Laps of the box, at 40 m/s, past both ends of the lane.
        val laps = (0..300).map { t ->
            val d = (t * 40.0) % 2800
            val (x, y) = when {
                d < 1000 -> d to 0.0
                d < 1400 -> 1000.0 to (d - 1000)
                d < 2400 -> (1000 - (d - 1400)) to 400.0
                else -> 0.0 to (400 - (d - 2400))
            }
            ll(x, y).let { Fix(it.lon, it.lat, t * 1000.0) }
        }
        PitLane.crossings(shape(), "s", laps) shouldBe emptyList()
        PitLane(shape(lane = false)).canTime shouldBe false
        PitLane.crossings(shape(lane = false), "s", pitStop()) shouldBe emptyList()
        PitLane(shape()).canTime shouldBe true
        // A lane never 16 m clear of the track: no line can be made; one drawn line alone can't time a stop.
        val hugging = CourseShape(shape().layouts, listOf(ll(200.0, 10.0), ll(800.0, 10.0)), Line(ll(400.0, 0.0), ll(400.0, 20.0)), null, null)
        PitLane(hugging).canTime shouldBe false
    }

    @Test
    fun `a stop across two sessions of one run keeps each crossing's session`() {
        val fixes = pitStop()
        val lane = PitLane(shape())
        val crossings = fixes.take(60).flatMap { lane.offer("a", it) } + fixes.drop(60).flatMap { lane.offer("b", it) }
        crossings.map { it.session to it.kind } shouldBe listOf("a" to "in", "b" to "out")
    }

    @Test
    fun `NHMS times stops as it is, with no pit_in or pit_out drawn`() {
        val nhms = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File("../courses/seed/nhms.geojson").readText()) as kotlinx.serialization.json.JsonObject
        val shape = (com.obd2dashboard.backend.courses.CourseRules.check(nhms) as com.obd2dashboard.backend.courses.CourseCheck.Ok).shape
        shape.pitIn shouldBe null
        PitLane(shape).canTime shouldBe true
    }

    @Test
    fun `a re-timing keeps the run's pit crossings`() {
        val lanes = laneXy.joinToString(",", "[", "]") { (x, y) -> ll(x, y).let { "[${it.lon},${it.lat}]" } }
        val base = boxGeoJson()
        val features = base.getValue("features") as kotlinx.serialization.json.JsonArray
        val withLane = kotlinx.serialization.json.JsonObject(base + ("features" to kotlinx.serialization.json.JsonArray(features +
            kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"Feature","properties":{"role":"pit_lane"},"geometry":{"type":"LineString","coordinates":$lanes}}"""))))
        val course = com.obd2dashboard.backend.courses.Course("box", "Box", 1, withLane, java.time.Instant.EPOCH)
        val lines = pitStop().mapIndexed { i, f -> """{"type":"sample","signal":"gps.position","lat":${f.lat},"lon":${f.lon},"fixAt":${f.at.toLong()},"seq":$i,"at":${f.at.toLong() + 150}}""" }
        val t = Retiming.retime(course, listOf("a" to boxTrace(lines.take(60)), "b" to boxTrace(lines.drop(60))))!!
        t.pitCrossings.map { it.session to it.kind } shouldBe listOf("a" to "in", "b" to "out")
        t.pitCrossings.last().at - t.pitCrossings.first().at shouldBe (119_700.0 plusOrMinus 1e-3)
        t.rule shouldBe 2
    }
}
