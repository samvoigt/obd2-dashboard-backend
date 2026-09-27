package com.obd2dashboard.backend

import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import com.obd2dashboard.backend.courses.CourseStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A course in a list: what it is and its layouts, without its geometry. */
@Serializable
data class CourseSummary(
    val id: String,
    val name: String,
    val version: Int,
    /** Epoch milliseconds. */
    val saved: Long,
    val layouts: List<LayoutSummary>,
)

@Serializable
data class LayoutSummary(val id: String, val name: String, val default: Boolean, val sectors: Int)

/** One version of a course, whole. */
@Serializable
data class CourseView(val id: String, val name: String, val version: Int, val saved: Long, val geojson: JsonObject)

/** A version in a course's history. */
@Serializable
data class CourseVersion(val version: Int, val name: String, val saved: Long)

/** A save (M12.3): the version it replaces (0 for a new course), so two editors can't both win. */
@Serializable
data class SaveCourse(val expected: Int, val name: String, val geojson: JsonObject)

/** A drawing to check (M12.4): the same rules a save applies, asked as the editor draws. */
@Serializable
data class CheckCourse(val name: String, val geojson: JsonObject, val id: String? = null)

/** What [CheckCourse] found: nothing is saved. */
@Serializable
data class CourseProblems(val problems: List<String>)

/** Why a course was refused: every problem, for the editor to show beside what's wrong. */
@Serializable
data class CourseRefused(val error: String, val message: String, val problems: List<String>)

fun Course.summary(): CourseSummary {
    val shape = (CourseRules.check(geojson) as? CourseCheck.Ok)?.shape
    return CourseSummary(
        id, name, version, saved.toEpochMilli(),
        shape?.layouts.orEmpty().map { LayoutSummary(it.id, it.name, it.default, it.sectors.size) },
    )
}

fun Course.view(): CourseView = CourseView(id, name, version, saved.toEpochMilli(), geojson)

/**
 * The admin page's courses (M12.3): the only way a course is made or changed.
 * Signed in, from our page, and every change logged with who made it, as cars
 * are (decision 25).
 */
fun Route.adminCourseRoutes(
    courses: CourseStore,
    /** Whether any session's laps were timed at course [id]: a course in use is never deleted. */
    inUse: suspend (id: String) -> Boolean,
    clock: Clock,
    auth: AdminAuth,
    config: AdminConfig,
    /** Told of every save or removal, so tablets can be sent the new set (M12.6). */
    onChange: suspend () -> Unit = {},
) {
    suspend fun ApplicationCall.pathId(): String? =
        parameters["id"]?.takeIf { CourseRules.idProblem(it) == null }
            ?: run { respond(HttpStatusCode.NotFound, ApiError("not_found", "No such course.")); null }

    get("/api/admin/courses") {
        call.admin(auth, config, change = false) ?: return@get
        call.respond(courses.current().map { it.summary() })
    }

    // The rules as the editor draws, with no second copy of them in the page (M12.4). Changes nothing.
    post("/api/admin/courses/check") {
        call.admin(auth, config, change = false) ?: return@post
        val request = call.receive<CheckCourse>()
        call.respond(CourseProblems(problemsOf(request.id, request.name, request.geojson)))
    }

    get("/api/admin/courses/{id}") {
        call.admin(auth, config, change = false) ?: return@get
        val id = call.pathId() ?: return@get
        val version = call.request.queryParameters["version"]?.toIntOrNull()
        val course = courses.get(id, version) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such course."))
        call.respond(course.view())
    }

    get("/api/admin/courses/{id}/versions") {
        call.admin(auth, config, change = false) ?: return@get
        val id = call.pathId() ?: return@get
        call.respond(courses.versions(id).map { CourseVersion(it.version, it.name, it.saved.toEpochMilli()) }.reversed())
    }

    put("/api/admin/courses/{id}") {
        val email = call.admin(auth, config, change = true) ?: return@put
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<SaveCourse>()
        val problems = problemsOf(id, request.name, request.geojson)
        if (problems.isNotEmpty()) {
            return@put call.respond(HttpStatusCode.BadRequest, CourseRefused("invalid", "The course can't be saved as it is.", problems))
        }
        val saved = courses.save(id, request.expected, request.name.trim(), request.geojson, clock.instant())
            ?: return@put call.respond(
                HttpStatusCode.Conflict,
                ApiError("conflict", "Someone saved this course since you opened it. Reload it, and draw your change again."),
            )
        adminLog.info("course saved: {} v{} by {}", id, saved.version, email)
        onChange()
        call.respond(if (saved.version == 1) HttpStatusCode.Created else HttpStatusCode.OK, saved.view())
    }

    delete("/api/admin/courses/{id}") {
        val email = call.admin(auth, config, change = true) ?: return@delete
        val id = call.pathId() ?: return@delete
        if (courses.get(id) == null) return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such course."))
        if (inUse(id)) {
            return@delete call.respond(
                HttpStatusCode.Conflict,
                ApiError("in_use", "Laps were timed at this course, so it stays. Draw a new version instead."),
            )
        }
        courses.delete(id)
        adminLog.info("course removed: {} by {}", id, email)
        onChange()
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Every problem with a course: its id (if it has one yet), its name, its drawing. */
private fun problemsOf(id: String?, name: String, geojson: JsonObject): List<String> =
    listOfNotNull(id?.let(CourseRules::idProblem), CourseRules.nameProblem(name)) +
        ((CourseRules.check(geojson) as? CourseCheck.Refused)?.problems.orEmpty())

/**
 * Courses as anyone may see them (M12.5): public like the rest of the site
 * (decision 11), since a course holds nothing private. Reads only.
 */
fun Route.publicCourseRoutes(courses: CourseStore) {
    get("/api/courses") {
        call.respond(courses.current().map { it.summary() })
    }

    get("/api/courses/{id}") {
        val id = call.parameters["id"]?.takeIf { CourseRules.idProblem(it) == null }
        val version = call.request.queryParameters["version"]?.toIntOrNull()
        val course = id?.let { courses.get(it, version) }
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such course."))
        call.respond(course.view())
    }

    get("/api/courses/{id}/versions") {
        val id = call.parameters["id"]?.takeIf { CourseRules.idProblem(it) == null }
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such course."))
        call.respond(courses.versions(id).map { CourseVersion(it.version, it.name, it.saved.toEpochMilli()) }.reversed())
    }
}

