package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.archive.Bounds
import com.obd2dashboard.backend.archive.SessionReader
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.courses.Course
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Which sessions re-timing picks (M13.4). */
class PickingTest {
    private fun summary(lines: List<String>): SessionSummary =
        SessionReader().apply { read(lines.joinToString("\n", postfix = "\n").byteInputStream()) }.summary()

    private fun at(id: String, lines: List<String>, car: String = "outback") = SessionAt(id, car, summary(lines))

    /** A log with one fix at [lon], [lat], and nothing else. */
    private fun elsewhere(lon: Double, lat: Double, second: Int, laps: List<String> = emptyList()) = listOf(
        """{"type":"session","v":3,"id":"s","device":"tab-1","started":"2026-09-27T12:00:00Z","signals":[],"seq":0,"at":${second * 1000},"wall":${BOX_WALL0 + second * 1000}}""",
        """{"type":"sample","signal":"gps.position","lat":$lat,"lon":$lon,"seq":1,"at":${second * 1000 + 500},"wall":${BOX_WALL0 + second * 1000 + 500}}""",
    ) + laps

    @Test
    fun `a course's bounds hold its layouts and pit lane, and 50 m round them`() {
        val b = courseBounds(boxCourse(1))!!
        // The box's left edge is at LON0; 50 m is 50 / 111,195 of a degree of latitude.
        b.minLat shouldBe (43.3626 - 50 / 111_195.0 plusOrMinus 1e-9)
        b.maxLat shouldBe (43.3626 + 450 / 111_195.0 plusOrMinus 1e-9)
        // A pit lane 200 m south of the box: inside the bounds.
        val pitLane = """{"type":"Feature","properties":{"role":"pit_lane"},"geometry":{"type":"LineString","coordinates":[[-71.4575,${43.3626 - 200 / 111_195.0}],[-71.4550,${43.3626 - 200 / 111_195.0}]]}}"""
        val box = boxGeoJson()
        val withPits = JsonObject(box + ("features" to JsonArray(box.getValue("features").jsonArray + Json.parseToJsonElement(pitLane))))
        courseBounds(Course("box", "Box", 1, withPits, Instant.EPOCH))!!.minLat shouldBe (43.3626 - 250 / 111_195.0 plusOrMinus 1e-9)
        courseBounds(Course("bad", "Bad", 1, Json.parseToJsonElement("""{"type":"FeatureCollection","features":[]}""").jsonObject, Instant.EPOCH)) shouldBe null
    }

    @Test
    fun `a session touches a course if its laps name it, or its fixes were there`() {
        val bounds = courseBounds(boxCourse(1))
        touches(summary(boxLog(0, 10)), "box", bounds) shouldBe true
        touches(summary(elsewhere(-71.0, 43.0, 0)), "box", bounds) shouldBe false
        touches(summary(elsewhere(-71.0, 43.0, 0, listOf(boxLap(1, 1)))), "box", bounds) shouldBe true
        // Just outside the box, within 50 m: still there.
        touches(summary(elsewhere(-71.4611 - 40 / 80_800.0, 43.3626, 0)), "box", bounds) shouldBe true
        touches(summary(elsewhere(-71.0, 43.0, 0)), "box", Bounds(-71.1, 42.9, -70.9, 43.1)) shouldBe true
    }

    @Test
    fun `a course's save picks every run with any session there, whole`() {
        val sessions = listOf(
            at("drive", elsewhere(-71.0, 43.0, 0)), // the drive to the track, same run
            at("track", boxLog(60, 300)),
            at("home", elsewhere(-71.0, 43.0, 100_000, emptyList()).map { it.replace("\"device\":\"tab-1\"", "\"device\":\"tab-2\"") }),
            at("yaris", boxLog(0, 300), car = "yaris"),
        )
        runsAt(sessions, boxCourse(1)).toSet() shouldBe setOf(listOf("drive", "track"), listOf("yaris"))
    }

    @Test
    fun `a session's run, and the courses it touches`() {
        val sessions = listOf(at("drive", elsewhere(-71.0, 43.0, 0)), at("track", boxLog(60, 300)))
        val other = Course("other", "Other", 1, boxGeoJson(), Instant.EPOCH).copy(id = "other")
        val far = Course("far", "Far", 1, Json.parseToJsonElement(boxGeoJson().toString().replace("-71.4", "-72.4")).jsonObject, Instant.EPOCH)
        runOf(sessions, "drive", listOf(boxCourse(1), far, other)) shouldBe (listOf("drive", "track") to listOf("box", "other"))
        runOf(sessions, "nope", listOf(boxCourse(1))) shouldBe null
    }
}
