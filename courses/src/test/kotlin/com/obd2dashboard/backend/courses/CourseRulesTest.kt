package com.obd2dashboard.backend.courses

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/** Near NHMS: about 8 m per 0.0001° of longitude here, 11 m per 0.0001° of latitude. */
private const val LON = -71.4611
private const val LAT = 43.3626

private fun feature(role: String, points: List<Pair<Double, Double>>, props: Map<String, Any> = emptyMap()) = buildJsonObject {
    put("type", "Feature")
    putJsonObject("properties") {
        put("role", role)
        props.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
            }
        }
    }
    putJsonObject("geometry") {
        put("type", "LineString")
        putJsonArray("coordinates") { points.forEach { (lon, lat) -> add(buildJsonArray { add(lon); add(lat) }) } }
    }
}

private fun course(vararg features: JsonObject) = buildJsonObject {
    put("type", "FeatureCollection")
    putJsonArray("features") { features.forEach { add(it) } }
}

/** A 20 m line across the track at [dLat] north of the start. */
private fun across(dLat: Double) = listOf(LON - 0.00012 to LAT + dLat, LON + 0.00012 to LAT + dLat)
private val loop = listOf(LON to LAT, LON to LAT + 0.002, LON + 0.002 to LAT + 0.002, LON + 0.002 to LAT, LON to LAT)

private fun layout(id: String, default: Boolean = false, name: String = id) =
    feature("layout", loop, mapOf("id" to id, "name" to name, "default" to default))

private fun refused(vararg features: JsonObject): List<String> =
    CourseRules.check(course(*features)).shouldBeInstanceOf<CourseCheck.Refused>().problems

class CourseRulesTest {
    @Test
    fun `the least a course can be is one layout and its start-finish`() {
        val shape = CourseRules.check(course(layout("loop"), feature("start_finish", across(0.0005))))
            .shouldBeInstanceOf<CourseCheck.Ok>().shape
        shape.layouts.single().id shouldBe "loop"
        shape.defaultLayout.id shouldBe "loop" // the only one is the default, said or not
        shape.defaultLayout.sectors shouldBe emptyList()
        shape.pitLine shouldBe null
    }

    @Test
    fun `layouts share a start-finish or have their own, and sectors come in order`() {
        val shape = CourseRules.check(
            course(
                layout("road", default = true, name = "Road Course"),
                layout("road-option"),
                feature("start_finish", across(0.0005)), // for every layout
                feature("sector", across(0.0015), mapOf("layout" to "road", "index" to 2)),
                feature("sector", across(0.0010), mapOf("layout" to "road", "index" to 1)),
                feature("pit_lane", listOf(LON - 0.0003 to LAT, LON - 0.0003 to LAT + 0.001)),
                feature("pit_in", listOf(LON - 0.0004 to LAT + 0.0002, LON - 0.0002 to LAT + 0.0002)),
                feature("pit_out", listOf(LON - 0.0004 to LAT + 0.0009, LON - 0.0002 to LAT + 0.0009)),
                feature("pit_line", listOf(LON - 0.0004 to LAT + 0.0005, LON - 0.0002 to LAT + 0.0005)),
            ),
        ).shouldBeInstanceOf<CourseCheck.Ok>().shape
        shape.defaultLayout.name shouldBe "Road Course"
        shape.defaultLayout.sectors.map { it.a.lat } shouldBe listOf(LAT + 0.0010, LAT + 0.0015) // by index, not by order drawn
        shape.layouts.first { it.id == "road-option" }.sectors shouldBe emptyList()
        shape.layouts.map { it.startFinish }.toSet() shouldHaveSize 1
        shape.pitLane!! shouldHaveSize 2
        shape.pitIn shouldNotBe null
        shape.pitLine shouldNotBe null
    }

    @Test
    fun `a layout's own start-finish is its only one`() {
        val shape = CourseRules.check(
            course(
                layout("east", default = true), layout("west"),
                feature("start_finish", across(0.0005), mapOf("layout" to "east")),
                feature("start_finish", across(0.0008), mapOf("layout" to "west")),
            ),
        ).shouldBeInstanceOf<CourseCheck.Ok>().shape
        shape.layouts.first { it.id == "west" }.startFinish.a.lat shouldBe LAT + 0.0008
        // Both its own and the shared one: which would it be timed on?
        refused(layout("east"), feature("start_finish", across(0.0005)), feature("start_finish", across(0.0008), mapOf("layout" to "east"))) shouldContain
            "the layout \"east\" has more than one start/finish"
    }

    @Test
    fun `every reason a course is refused is said, not just the first`() {
        val problems = refused(
            layout("road", default = true), layout("road", default = true), // the same id, and two defaults
            feature("sector", across(0.001), mapOf("layout" to "road", "index" to 1)),
            feature("sector", across(0.0015), mapOf("layout" to "road", "index" to 3)),
            feature("sector", across(0.0012), mapOf("layout" to "gone", "index" to 1)),
            feature("chicane", across(0.001)),
        )
        problems shouldContain "feature 2 (layout) repeats the id \"road\""
        problems shouldContain "the layout \"road\" has no start/finish"
        problems shouldContain "the layout \"road\" numbers its sectors [1, 3]; they go 1, 2, 3… without gaps"
        problems shouldContain "sectors name the layout \"gone\", which isn't drawn"
        problems shouldContain "feature 6 has the unknown role \"chicane\""
    }

    @Test
    fun `lines are two points, 1 to 200 metres, and coordinates are real`() {
        val sf = feature("start_finish", across(0.0005))
        refused(layout("east"), sf, feature("pit_in", listOf(LON to LAT, LON to LAT + 0.0001, LON to LAT + 0.0002))) shouldContain
            "feature 3 (pit_in) is a line: exactly 2 points"
        refused(layout("east"), sf, feature("pit_in", listOf(LON to LAT, LON + 0.0035 to LAT))).single() shouldBe
            "feature 3 (pit_in) is 283 m long; a line is 1–200 m"
        refused(layout("east"), sf, feature("pit_in", listOf(LON to LAT, LON + 0.000005 to LAT))).single() shouldBe
            "feature 3 (pit_in) is 0 m long; a line is 1–200 m"
        refused(layout("east"), feature("start_finish", listOf(LON to LAT, 190.0 to LAT))) shouldContain
            "feature 2 (start_finish) is not a LineString of [lon, lat] points in range"
    }

    @Test
    fun `a course is a FeatureCollection with layouts, one default, and one of each pit line`() {
        CourseRules.check(buildJsonObject { put("type", "Feature") }).shouldBeInstanceOf<CourseCheck.Refused>().problems shouldContain
            "a course is a GeoJSON FeatureCollection"
        refused(feature("start_finish", across(0.0005))) shouldContain "a course needs at least one layout"
        refused(layout("east"), layout("west"), feature("start_finish", across(0.0005))) shouldContain "exactly one layout is the default"
        val pit = feature("pit_line", listOf(LON - 0.0004 to LAT, LON - 0.0002 to LAT))
        refused(layout("east"), feature("start_finish", across(0.0005)), pit, pit) shouldContain "feature 4: a course has at most one pit_line"
    }

    @Test
    fun `a course is at most 256 KB`() {
        val huge = feature("layout", List(12_000) { LON + it * 1e-7 to LAT }, mapOf("id" to "east", "name" to "East"))
        refused(huge, feature("start_finish", across(0.0005))) shouldContain "a course is at most 256 KB"
    }

    @Test
    fun `ids and names`() {
        for (good in listOf("nhms", "road-option-2", "ab")) CourseRules.idProblem(good) shouldBe null
        for (bad in listOf("a", "Road", "2road", "road_course", "x".repeat(33))) CourseRules.idProblem(bad) shouldNotBe null
        CourseRules.nameProblem("New Hampshire Motor Speedway") shouldBe null
        CourseRules.nameProblem("  ") shouldNotBe null
        CourseRules.nameProblem("x".repeat(61)) shouldNotBe null
    }

    @Test
    fun `distances are metres on the ground`() {
        CourseRules.metres(LonLat(LON, LAT), LonLat(LON, LAT + 1.0)) shouldBe (111_195.0 plusOrMinus 5.0)
        CourseRules.metres(LonLat(LON, LAT), LonLat(LON + 0.00024, LAT)) shouldBe (19.4 plusOrMinus 0.2)
    }
}

class CoursesEtagTest {
    private val t = Instant.parse("2026-09-27T12:00:00Z")
    private fun c(id: String, version: Int) = Course(id, id, version, course(), t)

    @Test
    fun `the set's ETag moves exactly when a course is saved or removed`() {
        val set = listOf(c("nhms", 3), c("loop", 1))
        coursesEtag(set) shouldBe coursesEtag(set.reversed()) // the order doesn't matter
        coursesEtag(set) shouldNotBe coursesEtag(listOf(c("nhms", 4), c("loop", 1)))
        coursesEtag(set) shouldNotBe coursesEtag(listOf(c("nhms", 3)))
        coursesEtag(set).startsWith("\"courses-") shouldBe true
    }
}

class InMemoryCourseStoreTest {
    private val store = InMemoryCourseStore()
    private val t = Instant.parse("2026-09-27T12:00:00Z")

    @Test
    fun `every save is a new version, and only over the one expected`() = runTest {
        store.save("loop", 0, "Loop", course(), t)!!.version shouldBe 1
        store.save("loop", 0, "Loop", course(), t) shouldBe null // someone else saved it first
        store.save("loop", 1, "Home loop", course(), t)!!.version shouldBe 2
        store.get("loop")!!.name shouldBe "Home loop"
        store.get("loop", 1)!!.name shouldBe "Loop" // old versions are kept
        store.versions("loop").map { it.version } shouldBe listOf(1, 2)
        store.save("nhms", 0, "NHMS", course(), t)
        store.current().map { it.id to it.version } shouldBe listOf("loop" to 2, "nhms" to 1)
        store.delete("loop") shouldBe true
        store.get("loop", 1) shouldBe null
        store.delete("loop") shouldBe false
    }
}
