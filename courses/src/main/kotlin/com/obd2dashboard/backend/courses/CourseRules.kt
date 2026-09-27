package com.obd2dashboard.backend.courses

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What makes a course valid (M12.1). The website is the only source of courses,
 * so the server refuses anything a tablet couldn't time on, and says every
 * reason, not just the first.
 */
public object CourseRules {
    public const val MAX_BYTES: Int = 256 * 1024
    public const val MAX_NAME: Int = 60
    public const val MAX_LINE_METRES: Double = 200.0
    public const val MIN_LINE_METRES: Double = 1.0

    /** The proposal's roles (§3), and no others. */
    public val ROLES: Set<String> = setOf("layout", "start_finish", "sector", "pit_lane", "pit_in", "pit_out", "pit_line")

    private val ID = Regex("[a-z][a-z0-9-]{1,31}")

    /** A course's or a layout's id: lower case, letters, digits and hyphens, starting with a letter, 2–32 characters. */
    public fun idProblem(raw: String): String? =
        if (ID.matches(raw)) null else "an id is 2–32 lower-case letters, digits and hyphens, starting with a letter"

    public fun nameProblem(raw: String): String? = when {
        raw.isBlank() -> "a name cannot be blank"
        raw.trim().length > MAX_NAME -> "a name has at most $MAX_NAME characters"
        else -> null
    }

    /** [geojson] read, or every reason it can't be. */
    public fun check(geojson: JsonObject): CourseCheck {
        val problems = mutableListOf<String>()
        if (geojson.toString().toByteArray().size > MAX_BYTES) problems += "a course is at most ${MAX_BYTES / 1024} KB"
        if ((geojson["type"] as? JsonPrimitive)?.contentOrNull != "FeatureCollection") problems += "a course is a GeoJSON FeatureCollection"
        val features = (geojson["features"] as? JsonArray)?.map { it as? JsonObject }
        if (features == null) return CourseCheck.Refused(problems + "a course has a list of features")

        val layouts = mutableListOf<Triple<String, String, Pair<Boolean, List<LonLat>>>>()
        val startFinish = mutableListOf<Pair<String?, Line>>()
        val sectors = mutableListOf<Triple<String, Int, Line>>()
        var pitLane: List<LonLat>? = null
        val pits = mutableMapOf<String, Line>()

        features.forEachIndexed { i, feature ->
            val where = "feature ${i + 1}"
            if (feature == null) { problems += "$where is not an object"; return@forEachIndexed }
            val props = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val role = props.string("role")
            if (role == null || role !in ROLES) {
                problems += "$where has ${if (role == null) "no role" else "the unknown role \"$role\""}"
                return@forEachIndexed
            }
            val points = lineString(feature)
            if (points == null) { problems += "$where ($role) is not a LineString of [lon, lat] points in range"; return@forEachIndexed }
            fun asLine(): Line? {
                if (points.size != 2) { problems += "$where ($role) is a line: exactly 2 points"; return null }
                val length = metres(points[0], points[1])
                if (length < MIN_LINE_METRES || length > MAX_LINE_METRES) {
                    problems += "$where ($role) is ${"%.0f".format(length)} m long; a line is ${MIN_LINE_METRES.toInt()}–${MAX_LINE_METRES.toInt()} m"
                    return null
                }
                return Line(points[0], points[1])
            }
            when (role) {
                "layout" -> {
                    val id = props.string("id")
                    val name = props.string("name")
                    if (id == null || idProblem(id) != null) problems += "$where (layout) needs an id: ${idProblem(id ?: "")}"
                    else if (layouts.any { it.first == id }) problems += "$where (layout) repeats the id \"$id\""
                    if (name == null || nameProblem(name) != null) problems += "$where (layout) needs a name"
                    if (points.size < 2) problems += "$where (layout) needs at least 2 points"
                    if (id != null && name != null) layouts += Triple(id, name, (props.bool("default") == true) to points)
                }
                "start_finish" -> asLine()?.let { startFinish += props.string("layout") to it }
                "sector" -> {
                    val layout = props.string("layout")
                    val index = (props["index"] as? JsonPrimitive)?.intOrNull
                    if (layout == null) problems += "$where (sector) names no layout"
                    if (index == null || index < 1) problems += "$where (sector) needs an index from 1"
                    val line = asLine()
                    if (layout != null && index != null && index >= 1 && line != null) sectors += Triple(layout, index, line)
                }
                "pit_lane" -> {
                    if (pitLane != null) problems += "$where: a course has at most one pit lane"
                    if (points.size < 2) problems += "$where (pit_lane) needs at least 2 points" else pitLane = points
                }
                else -> {
                    if (role in pits) problems += "$where: a course has at most one $role"
                    asLine()?.let { pits[role] = it }
                }
            }
        }

        if (layouts.isEmpty()) problems += "a course needs at least one layout"
        val defaults = layouts.count { it.third.first }
        if (layouts.size > 1 && defaults != 1) problems += "exactly one layout is the default"
        val ids = layouts.map { it.first }.toSet()
        startFinish.mapNotNull { it.first }.filter { it !in ids }.forEach { problems += "a start/finish names the layout \"$it\", which isn't drawn" }
        sectors.map { it.first }.filter { it !in ids }.toSet().forEach { problems += "sectors name the layout \"$it\", which isn't drawn" }

        val built = layouts.mapNotNull { (id, name, rest) ->
            val own = startFinish.filter { it.first == id }
            val shared = startFinish.filter { it.first == null }
            val line = when {
                own.size + shared.size == 1 -> (own + shared).single().second
                own.isEmpty() && shared.isEmpty() -> { problems += "the layout \"$id\" has no start/finish"; null }
                else -> { problems += "the layout \"$id\" has more than one start/finish"; null }
            }
            val mine = sectors.filter { it.first == id }.sortedBy { it.second }
            if (mine.map { it.second } != (1..mine.size).toList()) {
                problems += "the layout \"$id\" numbers its sectors ${mine.map { it.second }}; they go 1, 2, 3… without gaps"
            }
            line?.let { Layout(id, name, rest.first || layouts.size == 1, rest.second, it, mine.map { s -> s.third }) }
        }
        return if (problems.isEmpty()) {
            CourseCheck.Ok(CourseShape(built, pitLane, pits["pit_in"], pits["pit_out"], pits["pit_line"]))
        } else {
            CourseCheck.Refused(problems)
        }
    }

    /** The feature's `LineString` as points, or null if it isn't one, or any point is out of range. */
    private fun lineString(feature: JsonObject): List<LonLat>? {
        val geometry = feature["geometry"] as? JsonObject ?: return null
        if ((geometry["type"] as? JsonPrimitive)?.contentOrNull != "LineString") return null
        val coords = geometry["coordinates"] as? JsonArray ?: return null
        return coords.map { c ->
            val pair = c as? JsonArray ?: return null
            val lon = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
            val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
            if (lon !in -180.0..180.0 || lat !in -90.0..90.0) return null
            LonLat(lon, lat)
        }
    }

    /** Great-circle distance in metres. */
    public fun metres(a: LonLat, b: LonLat): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}

/** The outcome of [CourseRules.check]. */
public sealed interface CourseCheck {
    public data class Ok(val shape: CourseShape) : CourseCheck
    public data class Refused(val problems: List<String>) : CourseCheck
}
