package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.gcp.FirestoreCourseStore.Companion.courseFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreCourseStore.Companion.toFields
import com.obd2dashboard.backend.courses.Course
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** A course version and its Firestore fields (M12.3). */
class CourseMappingTest {
    private val nhms = Json.parseToJsonElement(File("../courses/seed/nhms.geojson").readText()).jsonObject
    private val course = Course("nhms", "New Hampshire Motor Speedway", 3, nhms, Instant.parse("2026-09-27T12:00:00.123456789Z"))

    @Test
    fun `a version round-trips through its fields, the GeoJSON exactly`() {
        courseFrom("nhms", course.toFields()) shouldBe course
    }

    @Test
    fun `the GeoJSON is stored as text, since Firestore holds no arrays inside arrays`() {
        course.toFields()["geojson"].shouldBeInstanceOf<String>()
        course.toFields().values.none { it is List<*> } shouldBe true
    }
}
