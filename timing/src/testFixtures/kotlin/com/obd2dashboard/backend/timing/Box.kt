package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.Course
import java.time.Instant
import kotlin.math.cos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The same 1000 × 400 m box as `LapRuleTest`'s, as a course's GeoJSON, and logs of a car going round it at 40 m/s (M13). */
private const val LON0 = -71.4611
private const val LAT0 = 43.3626
private val perLon = 111_195.0 * cos(Math.toRadians(LAT0))
private fun lon(x: Double) = LON0 + x / perLon
private fun lat(y: Double) = LAT0 + y / 111_195.0
private fun pts(vararg xy: Pair<Double, Double>) = xy.joinToString(",", "[", "]") { (x, y) -> "[${lon(x)},${lat(y)}]" }
private fun feature(props: String, vararg xy: Pair<Double, Double>) =
    """{"type":"Feature","properties":{$props},"geometry":{"type":"LineString","coordinates":${pts(*xy)}}}"""

/** The box with its start/finish at [sfX] on the bottom straight; [short], a second layout, the default. */
public fun boxGeoJson(sfX: Double = 500.0, short: Boolean = false): JsonObject = Json.parseToJsonElement(
    """{"type":"FeatureCollection","features":[""" + listOfNotNull(
        feature(""""role":"layout","id":"box","name":"Box","default":${!short}""", 0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 400.0, 0.0 to 400.0, 0.0 to 0.0),
        if (short) feature(""""role":"layout","id":"short","name":"Short","default":true""", 0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 200.0, 0.0 to 200.0, 0.0 to 0.0) else null,
        feature(""""role":"start_finish"""", sfX to -15.0, sfX to 15.0),
        feature(""""role":"sector","layout":"box","index":1""", 985.0 to 200.0, 1015.0 to 200.0),
        feature(""""role":"sector","layout":"box","index":2""", 500.0 to 415.0, 500.0 to 385.0),
        feature(""""role":"sector","layout":"box","index":3""", 15.0 to 200.0, -15.0 to 200.0),
    ).joinToString(",") + "]}",
).jsonObject

public fun boxCourse(version: Int, sfX: Double = 500.0) = Course("box", "Box", version, boxGeoJson(sfX), Instant.EPOCH)

/** Where the car is [s] metres round the box from its bottom-left corner. */
private fun place(s: Double): Pair<Double, Double> {
    val d = ((s % 2800.0) + 2800.0) % 2800.0
    return when {
        d < 1000 -> d to 0.0
        d < 1400 -> 1000.0 to (d - 1000)
        d < 2400 -> (1000 - (d - 1400)) to 400.0
        else -> 0.0 to (400 - (d - 2400))
    }
}

/** When `at` 0 was, on the wall clock: 2026-09-27, 12:00 UTC. */
public const val BOX_WALL0: Long = 1_790_510_400_000

/**
 * A session's log: a fix a second (at 40 m/s from the corner) from [from] to
 * [to] seconds of the run, then the tablet's [laps]. [atShift] moves `at` (an
 * app restart starts it again), never `wall`.
 */
public fun boxLog(from: Int, to: Int, laps: List<String> = emptyList(), device: String? = "tab-1", atShift: Long = 0): List<String> =
    listOf(
        """{"type":"session","v":3,"id":"s",""" + (device?.let { "\"device\":\"$it\"," } ?: "") +
            """"started":"2026-09-27T12:00:00Z","signals":[],"seq":0,"at":${from * 1000 + atShift},"wall":${BOX_WALL0 + from * 1000}}""",
    ) +
        (from..to).map { t ->
            val (x, y) = place(t * 40.0)
            """{"type":"sample","signal":"gps.position","lat":${lat(y)},"lon":${lon(x)},"fixAt":${t * 1000 + atShift},"seq":${t + 1},"at":${t * 1000 + 150 + atShift},"wall":${BOX_WALL0 + t * 1000 + 150}}"""
        } + laps

/** The tablet's `lap` record for lap [n] of the box at 40 m/s (start/finish at 500 m): true crossings, plus [endOff] ms. */
public fun boxLap(n: Int, version: Int?, endOff: Long = 0, startOff: Long = 0): String {
    val start = 12_500L + 70_000L * (n - 1) + startOff
    val end = 12_500L + 70_000L * n + endOff
    val v = version?.let { ""","course":"box","courseVersion":$it,"layout":"box"""" } ?: ""","track":"box","layout":"Box""""
    return """{"type":"lap"$v,"lap":$n,"time":${(end - start) / 1000.0},"sectors":[17.5,17.5,17.5,17.5],"startAt":$start,"endAt":$end,"seq":${1000 + n},"at":${end + 150}}"""
}

public fun boxTrace(lines: List<String>) = SessionTrace().apply { lines.forEach { line(it.toByteArray()) } }
