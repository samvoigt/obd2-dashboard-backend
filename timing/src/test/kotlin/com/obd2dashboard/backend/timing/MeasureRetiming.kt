package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.Course
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measures re-timing a long live run from its start (M19.1), as `timing` and
 * live results do: `MEASURE=1 MEASURE_FILE=<log> MEASURE_COURSE=<geojson>`,
 * from `scripts/synthetic-session.py`. Not part of the suite.
 */
class MeasureRetiming {
    @Test
    fun `a long live run re-timed from its start`() {
        assumeTrue(System.getenv("MEASURE") != null)
        val file = java.io.File(System.getenv("MEASURE_FILE"))
        val course = Course("box", "Box", 1, Json.parseToJsonElement(java.io.File(System.getenv("MEASURE_COURSE")).readText()).jsonObject, Instant.EPOCH)
        val live = LiveSession("s", "car")
        val fed = System.nanoTime()
        file.forEachLine { live.trace.record(Json.parseToJsonElement(it).jsonObject) }
        val feeding = (System.nanoTime() - fed) / 1_000_000
        repeat(3) { Provisional.runs(course, listOf(live)) } // warm
        val runs = 10
        val started = System.nanoTime()
        var laps = 0
        repeat(runs) { laps = Provisional.runs(course, listOf(live)).single().laps.size }
        val each = (System.nanoTime() - started) / 1_000_000 / runs
        println("MEASURE re-timing: ${live.trace.allFixes.size} fixes, ${live.trace.tabletLaps.size} tablet laps, $laps standing; fed in $feeding ms; re-timed from the start in $each ms each")
    }
}
