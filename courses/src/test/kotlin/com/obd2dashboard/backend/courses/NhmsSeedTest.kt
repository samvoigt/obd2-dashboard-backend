package com.obd2dashboard.backend.courses

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** The NHMS seed (M12.2), as `courses/seed/make_nhms.py` writes it from the app's file. */
class NhmsSeedTest {
    private val shape = CourseRules.check(Json.parseToJsonElement(File("seed/nhms.geojson").readText()).jsonObject)
        .shouldBeInstanceOf<CourseCheck.Ok>().shape

    /** Whether segment [a]–[b] crosses the polyline [path] (flat, which is plenty over a few hundred metres). */
    private fun crosses(a: LonLat, b: LonLat, path: List<LonLat>): Boolean =
        path.zipWithNext().any { (c, d) -> side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0 }

    private fun side(p: LonLat, q: LonLat, r: LonLat): Double =
        Math.signum((q.lon - p.lon) * (r.lat - p.lat) - (q.lat - p.lat) * (r.lon - p.lon))

    @Test
    fun `NHMS passes the rules, with its three layouts by id and the road course the default`() {
        shape.layouts.map { it.id } shouldBe listOf("road", "road-option", "road-option-2")
        shape.defaultLayout.name shouldBe "Road Course"
        shape.layouts.map { it.sectors.size } shouldBe listOf(0, 0, 0) // none drawn yet
    }

    @Test
    fun `its start-finish crosses every layout, and its pit line the pit lane and no layout`() {
        for (layout in shape.layouts) crosses(layout.startFinish.a, layout.startFinish.b, layout.path) shouldBe true
        val pit = shape.pitLine!!
        crosses(pit.a, pit.b, shape.pitLane!!) shouldBe true
        for (layout in shape.layouts) crosses(pit.a, pit.b, layout.path) shouldBe false // short of the track, as the tablet's
        CourseRules.metres(pit.a, pit.b) shouldBe (16.0 plusOrMinus 0.2)
    }
}
