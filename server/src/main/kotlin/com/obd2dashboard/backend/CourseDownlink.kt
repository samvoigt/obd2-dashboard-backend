package com.obd2dashboard.backend

import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.courses.coursesEtag
import com.obd2dashboard.backend.live.TabletHandle
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Courses down to the tablet (M12.6, the proposal's §3): the `courses` frame,
 * after every `hello` from a tablet listing `courses.1`, and to every such
 * tablet connected when a course is saved or removed, so it fetches
 * `GET /v1/courses` again if its `ETag` differs.
 */
class CourseDownlink(private val courses: CourseStore) {
    private val tablets: MutableSet<TabletHandle> = ConcurrentHashMap.newKeySet()

    suspend fun frame(): String = buildJsonObject {
        put("t", "courses")
        put("etag", coursesEtag(courses.current()))
    }.toString()

    fun listen(tablet: TabletHandle) {
        tablets += tablet
    }

    fun leave(tablet: TabletHandle) {
        tablets -= tablet
    }

    /** A course was saved or removed: every listening tablet hears the new `ETag`. */
    suspend fun changed() {
        val frame = frame()
        tablets.forEach { it.send(frame) }
    }

    companion object {
        const val FEATURE: String = "courses.1"
    }
}
