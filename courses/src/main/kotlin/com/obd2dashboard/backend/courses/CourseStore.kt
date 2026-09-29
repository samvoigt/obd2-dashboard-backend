package com.obd2dashboard.backend.courses

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/**
 * Where courses live (Firestore in production). **Every save is a new version**,
 * and old versions are kept for re-timing (M13): nothing here edits one.
 */
public interface CourseStore {
    /** The latest version of every course. */
    public suspend fun current(): List<Course>

    /** [version] of course [id], or its latest if [version] is null. */
    public suspend fun get(id: String, version: Int? = null): Course?

    /** Every version of course [id], oldest first. */
    public suspend fun versions(id: String): List<Course>

    /**
     * Stores the next version of [id] (1 if it's new), only if its latest is
     * still [expected] (0 for a new course): two editors can't both win.
     * Null if the latest had moved on.
     */
    public suspend fun save(id: String, expected: Int, name: String, geojson: JsonObject, now: Instant): Course?

    /** Removes course [id] and every version of it. False if there was none. */
    public suspend fun delete(id: String): Boolean

    /** For each course, the version the server last finished re-timing it at (M18.2); absent if none since its save. */
    public suspend fun retimed(): Map<String, Int>

    /** Records that the re-timing of [id] at [version] finished; a newer save forgets it. */
    public suspend fun setRetimed(id: String, version: Int)
}

public class InMemoryCourseStore : CourseStore {
    private val courses = ConcurrentHashMap<String, List<Course>>()
    private val retimed = ConcurrentHashMap<String, Int>()

    override suspend fun current(): List<Course> = courses.values.map { it.last() }.sortedBy { it.id }

    override suspend fun get(id: String, version: Int?): Course? =
        courses[id]?.let { all -> if (version == null) all.last() else all.firstOrNull { it.version == version } }

    override suspend fun versions(id: String): List<Course> = courses[id].orEmpty()

    override suspend fun save(id: String, expected: Int, name: String, geojson: JsonObject, now: Instant): Course? {
        var saved: Course? = null
        courses.compute(id) { _, all ->
            val latest = all?.last()?.version ?: 0
            if (latest != expected) return@compute all
            val course = Course(id, name, latest + 1, geojson, now)
            saved = course
            retimed.remove(id) // as Firestore's course document is rewritten
            all.orEmpty() + course
        }
        return saved
    }

    override suspend fun delete(id: String): Boolean = courses.remove(id).also { retimed.remove(id) } != null

    override suspend fun retimed(): Map<String, Int> = retimed.toMap()

    /** For tests: as if the server stopped before recording a finished job. */
    public fun forgetRetimed(id: String) {
        retimed.remove(id)
    }

    override suspend fun setRetimed(id: String, version: Int) {
        if (courses[id]?.last()?.version == version) retimed[id] = version
    }
}
