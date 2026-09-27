package com.obd2dashboard.backend.courses

import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * A course (M12): a place to time laps, drawn on the website and sent down to
 * the tablet, which times on it (the contract proposal's §3).
 *
 * **The GeoJSON is kept as sent**, a `JsonObject`, so nothing the editor or a
 * later field adds is lost in a round trip; [CourseRules] reads what it needs
 * from it. **Every save is a new [version]**, numbered from 1, and old ones are
 * kept: a lap names the version it was timed on.
 */
public data class Course(
    /** Permanent, like a car's slug: `nhms`. */
    val id: String,
    val name: String,
    val version: Int,
    val geojson: JsonObject,
    val saved: Instant,
)

/** A point as GeoJSON has it: longitude first. */
public data class LonLat(val lon: Double, val lat: Double)

/** A timing line: two points, crossed only in its layout's direction. */
public data class Line(val a: LonLat, val b: LonLat)

/** One way round a course. */
public data class Layout(
    val id: String,
    val name: String,
    val default: Boolean,
    /** The line cars take, in the direction they go. */
    val path: List<LonLat>,
    /** The start/finish that applies to this layout: its own, or the course's for every layout. */
    val startFinish: Line,
    /** Sector lines in order: sector 1 runs from the start/finish to the first. */
    val sectors: List<Line>,
)

/** What [CourseRules] read from a valid course's GeoJSON. */
public data class CourseShape(
    val layouts: List<Layout>,
    val pitLane: List<LonLat>?,
    val pitIn: Line?,
    val pitOut: Line?,
    /** Across the pit lane: where an in-lap ends and the out-lap begins (the proposal's §3.2). */
    val pitLine: Line?,
) {
    val defaultLayout: Layout get() = layouts.first { it.default }
}

/** The whole set's `ETag` for `GET /v1/courses`: from every course's id and version, so it moves exactly when one does. */
public fun coursesEtag(courses: Collection<Course>): String {
    val key = courses.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.version}" }
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    return "\"courses-" + digest.take(8).joinToString("") { "%02x".format(it) } + "\""
}
