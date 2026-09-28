package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.archive.Bounds
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import kotlin.math.cos

/** A complete session and its summary: what picking needs (M13.4). */
public data class SessionAt(val id: String, val car: String, val summary: SessionSummary) {
    internal val part: RunPart get() = RunPart(id, car, summary.device, summary.started, summary.ended, summary.firstAt, summary.lastAt)
}

/** How far round a course's lines a fix still counts as there: a GPS error and a runoff. */
public const val COURSE_MARGIN_METRES: Double = 50.0

/** Where a course is: every layout's path and the pit lane, with [COURSE_MARGIN_METRES] round them; null if it isn't valid. */
public fun courseBounds(course: Course): Bounds? {
    val shape = (CourseRules.check(course.geojson) as? CourseCheck.Ok)?.shape ?: return null
    val points = shape.layouts.flatMap { it.path } + shape.pitLane.orEmpty()
    val lat = COURSE_MARGIN_METRES / Frame.METRES_PER_DEGREE
    val lon = lat / cos(Math.toRadians(points.map { it.lat }.average()))
    return Bounds(points.minOf { it.lon } - lon, points.minOf { it.lat } - lat, points.maxOf { it.lon } + lon, points.maxOf { it.lat } + lat)
}

/** Whether [session] was at [course]: its laps name it, or its fixes were where it is. */
public fun touches(session: SessionSummary, course: String, bounds: Bounds?): Boolean =
    session.track == course || (bounds != null && session.bounds?.intersects(bounds) == true)

/** The runs (§22.8) that touch [course], each as its sessions' ids in order: what a course's save re-times. */
public fun runsAt(sessions: List<SessionAt>, course: Course): List<List<String>> {
    val bounds = courseBounds(course)
    val byId = sessions.associateBy { it.id }
    return runs(sessions.map { it.part })
        .filter { run -> run.any { touches(byId.getValue(it.id).summary, course.id, bounds) } }
        .map { run -> run.map { it.id } }
}

/** The run [id] is in, and the courses of [courses] it touches: what a session's completion re-times. */
public fun runOf(sessions: List<SessionAt>, id: String, courses: List<Course>): Pair<List<String>, List<String>>? {
    val run = runs(sessions.map { it.part }).firstOrNull { r -> r.any { it.id == id } } ?: return null
    val byId = sessions.associateBy { it.id }
    val at = courses.filter { course ->
        val bounds = courseBounds(course)
        run.any { touches(byId.getValue(it.id).summary, course.id, bounds) }
    }
    return run.map { it.id } to at.map { it.id }
}
