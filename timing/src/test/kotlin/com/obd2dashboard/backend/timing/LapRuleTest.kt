package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.CourseShape
import com.obd2dashboard.backend.courses.Layout
import com.obd2dashboard.backend.courses.Line
import com.obd2dashboard.backend.courses.LonLat
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/** Metres east and north of a point near NHMS, as the rule's own frame reads them back exactly. */
private const val LON0 = -71.4611
private const val LAT0 = 43.3626
private val perLon = Frame.METRES_PER_DEGREE * cos(Math.toRadians(LAT0))
private fun ll(x: Double, y: Double) = LonLat(LON0 + x / perLon, LAT0 + y / Frame.METRES_PER_DEGREE)
private fun line(x1: Double, y1: Double, x2: Double, y2: Double) = Line(ll(x1, y1), ll(x2, y2))

/**
 * A 1000 × 400 m rectangle, anticlockwise from its bottom-left corner: 2,800 m
 * round. The start/finish across the bottom straight at x = 500; sector lines
 * across the right side (y = 200), the top (x = 500) and the left (y = 200), so
 * every line is mid-straight and interpolation along a move is exact there.
 */
private val corners = listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 400.0, 0.0 to 400.0, 0.0 to 0.0)
private val startFinish = line(500.0, -15.0, 500.0, 15.0)
private val sectorLines = listOf(line(985.0, 200.0, 1015.0, 200.0), line(500.0, 415.0, 500.0, 385.0), line(15.0, 200.0, -15.0, 200.0))

private fun course(sectors: List<Line> = sectorLines, pitLine: Line? = null, pitLane: List<LonLat>? = null) = CourseShape(
    layouts = listOf(Layout("box", "Box", true, corners.map { (x, y) -> ll(x, y) }, startFinish, sectors)),
    pitLane = pitLane, pitIn = null, pitOut = null, pitLine = pitLine,
)

/** Where the car is [s] metres round the rectangle from its bottom-left corner. */
private fun around(s: Double): Pair<Double, Double> {
    val d = ((s % 2800.0) + 2800.0) % 2800.0
    return when {
        d < 1000 -> d to 0.0
        d < 1400 -> 1000.0 to (d - 1000)
        d < 2400 -> (1000 - (d - 1400)) to 400.0
        else -> 0.0 to (400 - (d - 2400))
    }
}

/** Fixes every [everyMs] for [seconds], the car at distance [s] (metres) at each time (ms). */
private fun drive(everyMs: Double, seconds: Double, s: (Double) -> Double, place: (Double) -> Pair<Double, Double> = ::around): List<Fix> =
    generateSequence(0.0) { it + everyMs }.takeWhile { it <= seconds * 1000 }.map { t ->
        val (x, y) = place(s(t))
        val p = ll(x, y)
        Fix(p.lon, p.lat, t)
    }.toList()

class LapRuleTest {
    @Test
    fun `at one fix a second, laps come to their true time`() {
        // 40 m/s from the corner: the start/finish (500 m) at 12.5 s, then a lap every 70 s.
        val laps = LapRule.laps(course(), "box", drive(1000.0, 300.0, { t -> t / 1000 * 40 }))
        laps.map { it.number } shouldBe listOf(1, 2, 3, 4)
        for ((i, lap) in laps.withIndex()) {
            lap.startAt shouldBe (12_500.0 + i * 70_000 plusOrMinus 1e-6)
            lap.time shouldBe (70.0 plusOrMinus 1e-9)
            lap.pitIn shouldBe false
        }
    }

    @Test
    fun `each lap's sectors come to their true times, and add up to the lap`() {
        // From the start/finish: 700 m to line 1, 700 to line 2, 700 to line 3, 700 home; at 40 m/s, 17.5 s each.
        val lap = LapRule.laps(course(), "box", drive(1000.0, 90.0, { t -> t / 1000 * 40 })).single()
        lap.sectors.size shouldBe 4
        for (sector in lap.sectors) sector shouldBe (17.5 plusOrMinus 1e-9)
        lap.sectors.sum() shouldBe (lap.time plusOrMinus 1e-9)
    }

    @Test
    fun `at ten a second, and at a pace that varies round the lap`() {
        // Speed swings between 30 and 50 m/s: s(t) = 40 t + 50 sin(t / 10).
        val s = { t: Double -> 40 * t / 1000 + 50 * sin(t / 1000 / 10) }
        val laps = LapRule.laps(course(), "box", drive(100.0, 400.0, s))
        // The true moments s(t) = 500 + 2800 k, found by bisection.
        fun whenAt(target: Double): Double {
            var lo = 0.0
            var hi = 400_000.0
            repeat(80) { val mid = (lo + hi) / 2; if (s(mid) < target) lo = mid else hi = mid }
            return lo
        }
        laps.size shouldBe 5
        for ((i, lap) in laps.withIndex()) {
            lap.startAt shouldBe (whenAt(500.0 + i * 2800) plusOrMinus 0.2) // within 0.2 ms: at 10 Hz a move is ~4 m of a steady-ish pace
            lap.endAt shouldBe (whenAt(500.0 + (i + 1) * 2800) plusOrMinus 0.2)
        }
    }

    @Test
    fun `a car sat on the line, its fixes wandering across it, makes no laps`() {
        val wander = drive(1000.0, 600.0, { t -> 500 + 4 * sin(t / 700) }) // ±4 m about the line, for ten minutes
        LapRule.laps(course(), "box", wander) shouldBe emptyList()
    }

    @Test
    fun `a lap counts once the car has left the line, however soon`() {
        // A 400 m loop: arming is a quarter of it, 100 m, not 150.
        val small = CourseShape(
            layouts = listOf(Layout("small", "Small", true, listOf(ll(0.0, 0.0), ll(100.0, 0.0), ll(100.0, 100.0), ll(0.0, 100.0), ll(0.0, 0.0)), line(50.0, -10.0, 50.0, 10.0), emptyList())),
            pitLane = null, pitIn = null, pitOut = null, pitLine = null,
        )
        LapRule(small, "small").armAt shouldBe (100.0 plusOrMinus 1e-6)
        LapRule(course(), "box").armAt shouldBe 150.0
        val loop = { d: Double -> val m = ((d % 400) + 400) % 400; when { m < 100 -> m to 0.0; m < 200 -> 100.0 to m - 100; m < 300 -> 300 - m to 100.0; else -> 0.0 to 400 - m } }
        LapRule.laps(small, "small", drive(250.0, 25.0, { t -> t / 1000 * 40 }, loop)).map { it.time } shouldBe listOf(10.0, 10.0)
    }

    @Test
    fun `driven the wrong way, nothing is timed, no sector either`() {
        LapRule.laps(course(), "box", drive(1000.0, 300.0, { t -> -t / 1000 * 40 })) shouldBe emptyList()
    }

    @Test
    fun `fixes dropped across the line still make the lap, to its true time`() {
        val fixes = drive(1000.0, 160.0, { t -> t / 1000 * 40 }).filterNot { it.at in 80_000.0..84_000.0 } // 5 s lost across the line at 82.5 s
        val laps = LapRule.laps(course(), "box", fixes)
        laps.first().endAt shouldBe (82_500.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a move across a sector line and the start-finish counts both, in order`() {
        // Line 3 on the bottom straight at x = 450, just before the start/finish (500): one move from 440 m to 520 m
        // round crosses both, the fixes between them lost.
        val lines = listOf(sectorLines[0], sectorLines[1], line(450.0, -15.0, 450.0, 15.0))
        val fixes = drive(1000.0, 160.0, { t -> t / 1000 * 40 }).filterNot { it.at > 81_000.0 && it.at < 83_000.0 } // 3240 m → 3320 m in one move
        val lap = LapRule.laps(course(sectors = lines), "box", fixes).first()
        lap.sectors.size shouldBe 4
        lap.sectors.last() shouldBe (50.0 / 40 plusOrMinus 1e-9) // line 3 to the start/finish: 50 m at 40 m/s
        lap.sectors.sum() shouldBe (70.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a sector line missed stops that lap's sectors, and the next lap has them all`() {
        // On lap 2 the car takes the top straight 60 m inside (y = 340), missing line 2 (y 385–415).
        val inside = { d: Double -> around(d).let { (x, y) -> if (d % 2800 in 1450.0..2350.0 && d > 2800 && d < 5600) x to 340.0 else x to y } }
        val laps = LapRule.laps(course(), "box", drive(500.0, 250.0, { t -> t / 1000 * 40 }, inside))
        laps[0].sectors.size shouldBe 4
        laps[1].sectors.size shouldBe 1 // line 1, then line 2 missed: its sectors stop there
        laps[2].sectors.size shouldBe 4
    }

    @Test
    fun `a sector line that misses its layout leaves the layout timed without sectors`() {
        val off = sectorLines.take(2) + line(-200.0, 200.0, -170.0, 200.0) // line 3 nowhere near the track
        val lap = LapRule.laps(course(sectors = off), "box", drive(1000.0, 90.0, { t -> t / 1000 * 40 })).single()
        lap.sectors shouldBe emptyList()
        lap.time shouldBe (70.0 plusOrMinus 1e-9)
        // One that cuts the track twice (the bottom and the top, at x = 300) is no sector line either.
        val twice = sectorLines.take(2) + line(300.0, -10.0, 300.0, 410.0)
        LapRule.laps(course(sectors = twice), "box", drive(1000.0, 90.0, { t -> t / 1000 * 40 })).single().sectors shouldBe emptyList()
    }

    @Test
    fun `a start-finish that misses the layout times nothing, and is no error`() {
        val shape = CourseShape(listOf(Layout("box", "Box", true, corners.map { (x, y) -> ll(x, y) }, line(500.0, 100.0, 500.0, 130.0), emptyList())), null, null, null, null)
        LapRule(shape, "box").canTime shouldBe false
        LapRule.laps(shape, "box", drive(1000.0, 300.0, { t -> t / 1000 * 40 })) shouldBe emptyList()
    }

    /** A pit lane beside the bottom straight, 40 m below it, from x = 300 to 700, in the direction cars go. */
    private val lane = listOf(ll(300.0, -40.0), ll(700.0, -40.0))

    /**
     * Round the box, but the third time along the bottom through the pit lane:
     * down at x = 300, along y = −45 (5 m off the lane's line, as a GPS trace
     * is), up at x = 700.
     */
    private val pitting = { d: Double ->
        val m = ((d % 2800) + 2800) % 2800
        if (d in 5600.0 + 255..5600.0 + 745) {
            when {
                m < 300 -> 300.0 to -(m - 255)
                m < 700 -> m to -45.0
                else -> 700.0 to -45 + (m - 700)
            }
        } else around(d)
    }

    @Test
    fun `an in-lap ends at the pit line, and the out-lap begins there`() {
        val pit = line(500.0, -48.0, 500.0, -32.0)
        val laps = LapRule.laps(course(sectors = emptyList(), pitLine = pit, pitLane = lane), "box", drive(500.0, 250.0, { t -> t / 1000 * 40 }, pitting))
        // A lap, then the in-lap ending at the pit line (at 6,100 m round), then the out-lap from it.
        laps.map { it.pitIn to it.pitOut } shouldBe listOf(false to false, true to false, false to true)
        laps[1].endAt shouldBe laps[2].startAt // one line, no time lost between them
    }

    @Test
    fun `without a pit line, one is made across the pit lane level with the start-finish, as the tablet's`() {
        val made = LapRule.laps(course(sectors = emptyList(), pitLane = lane), "box", drive(500.0, 250.0, { t -> t / 1000 * 40 }, pitting))
        val drawn = LapRule.laps(course(sectors = emptyList(), pitLine = line(500.0, -48.0, 500.0, -32.0), pitLane = lane), "box", drive(500.0, 250.0, { t -> t / 1000 * 40 }, pitting))
        made.map { it.pitIn to it.pitOut } shouldBe drawn.map { it.pitIn to it.pitOut }
        made.zip(drawn).forEach { (m, d) -> m.endAt shouldBe (d.endAt plusOrMinus 1e-6) }
    }

    @Test
    fun `the same moments whatever the frame's origin`() {
        // The same rectangle, its points starting from another corner: the rule's frame is centred elsewhere.
        val shifted = corners.drop(2).dropLast(1) + corners.take(3)
        val other = CourseShape(listOf(Layout("box", "Box", true, shifted.map { (x, y) -> ll(x, y) }, startFinish, sectorLines)), null, null, null, null)
        val s = { t: Double -> 40 * t / 1000 + 50 * sin(t / 1000 / 10) }
        val a = LapRule.laps(course(), "box", drive(1000.0, 300.0, s))
        val b = LapRule.laps(other, "box", drive(1000.0, 300.0, s))
        a shouldHaveSize b.size
        a.zip(b).forEach { (x, y) ->
            x.startAt shouldBe (y.startAt plusOrMinus 1e-6)
            x.endAt shouldBe (y.endAt plusOrMinus 1e-6)
            x.sectors.zip(y.sectors).forEach { (p, q) -> p shouldBe (q plusOrMinus 1e-9) }
        }
    }
}
