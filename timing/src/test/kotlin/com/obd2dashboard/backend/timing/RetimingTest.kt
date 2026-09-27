package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import kotlin.math.cos
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Test

/** The same 1000 × 400 m box as [LapRuleTest], as a course's GeoJSON. */
private const val LON0 = -71.4611
private const val LAT0 = 43.3626
private val perLon = Frame.METRES_PER_DEGREE * cos(Math.toRadians(LAT0))
private fun lon(x: Double) = LON0 + x / perLon
private fun lat(y: Double) = LAT0 + y / Frame.METRES_PER_DEGREE
private fun pts(vararg xy: Pair<Double, Double>) = xy.joinToString(",", "[", "]") { (x, y) -> "[${lon(x)},${lat(y)}]" }
private fun feature(props: String, vararg xy: Pair<Double, Double>) =
    """{"type":"Feature","properties":{$props},"geometry":{"type":"LineString","coordinates":${pts(*xy)}}}"""

/** The box with its start/finish at [sfX] on the bottom straight; [short], a second layout, the default. */
private fun box(sfX: Double = 500.0, short: Boolean = false): JsonObject = Json.parseToJsonElement(
    """{"type":"FeatureCollection","features":[""" + listOfNotNull(
        feature(""""role":"layout","id":"box","name":"Box","default":${!short}""", 0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 400.0, 0.0 to 400.0, 0.0 to 0.0),
        if (short) feature(""""role":"layout","id":"short","name":"Short","default":true""", 0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 200.0, 0.0 to 200.0, 0.0 to 0.0) else null,
        feature(""""role":"start_finish"""", sfX to -15.0, sfX to 15.0),
        feature(""""role":"sector","layout":"box","index":1""", 985.0 to 200.0, 1015.0 to 200.0),
        feature(""""role":"sector","layout":"box","index":2""", 500.0 to 415.0, 500.0 to 385.0),
        feature(""""role":"sector","layout":"box","index":3""", 15.0 to 200.0, -15.0 to 200.0),
    ).joinToString(",") + "]}",
).jsonObject

private fun course(version: Int, sfX: Double = 500.0) = Course("box", "Box", version, box(sfX), Instant.EPOCH)

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

/** A session's log: a fix a second (at 40 m/s from the corner) from [from] to [to] seconds, then the tablet's [laps]. */
private fun log(from: Int, to: Int, laps: List<String> = emptyList()): List<String> =
    listOf("""{"type":"session","v":3,"id":"s","device":"tab-1","started":"2026-09-27T12:00:00Z","signals":[],"seq":0,"at":${from * 1000}}""") +
        (from..to).map { t ->
            val (x, y) = place(t * 40.0)
            """{"type":"sample","signal":"gps.position","lat":${lat(y)},"lon":${lon(x)},"fixAt":${t * 1000},"seq":${t + 1},"at":${t * 1000 + 150}}"""
        } + laps

/** The tablet's `lap` record for lap [n] of the box at 40 m/s (start/finish at 500 m): true crossings, plus [endOff] ms. */
private fun tabletLap(n: Int, version: Int?, endOff: Long = 0, startOff: Long = 0): String {
    val start = 12_500L + 70_000L * (n - 1) + startOff
    val end = 12_500L + 70_000L * n + endOff
    val v = version?.let { ""","course":"box","courseVersion":$it,"layout":"box"""" } ?: ""","track":"box","layout":"Box""""
    return """{"type":"lap"$v,"lap":$n,"time":${(end - start) / 1000.0},"sectors":[17.5,17.5,17.5,17.5],"startAt":$start,"endAt":$end,"seq":1000,"at":${end + 150}}"""
}

private fun trace(lines: List<String>) = SessionTrace().apply { lines.forEach { line(it.toByteArray()) } }

class RetimingTest {
    @Test
    fun `the tablet's laps on the current version stand as sent, and agree`() {
        val laps = (1..4).map { tabletLap(it, version = 1) }
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 300, laps))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.TABLET }
        t.laps.map { it.tabletLap } shouldBe listOf(1, 2, 3, 4)
        t.laps.map { it.flag } shouldBe List(4) { null }
        t.retimed.size shouldBe 4
    }

    @Test
    fun `laps timed on an older version are re-timed on the new one`() {
        // Version 2 moves the start/finish 100 m on: 2.5 s later.
        val laps = (1..4).map { tabletLap(it, version = 1) }
        val t = Retiming.retime(course(2, sfX = 600.0), listOf("a" to trace(log(0, 300, laps))))!!
        t.courseVersion shouldBe 2
        t.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
        for ((i, lap) in t.laps.withIndex()) {
            lap.startAt shouldBe (15_000.0 + i * 70_000 plusOrMinus 1e-6)
            lap.time shouldBe (70.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a session with no laps of its own, or older ones by layout name, is timed`() {
        Retiming.retime(course(1), listOf("a" to trace(log(0, 300))))!!.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
        val old = (1..4).map { tabletLap(it, version = null) }
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 300, old))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
        // Named "Box", not the default layout: timed on it.
        Retiming.retime(Course("box", "Box", 1, box(short = true), Instant.EPOCH), listOf("a" to trace(log(0, 300, old))))!!.layout shouldBe "box"
    }

    @Test
    fun `laps timed on another course don't stand here`() {
        val elsewhere = (1..4).map { tabletLap(it, version = 1).replace("\"course\":\"box\"", "\"course\":\"elsewhere\"") }
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 300, elsewhere))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
    }

    @Test
    fun `a lap across two sessions of one run is one lap, in the session it ended in`() {
        // The OBD link drops at 40 s, mid-lap 1 (12.5 to 82.5 s).
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 40)), "b" to trace(log(41, 300))))!!
        t.laps.size shouldBe 4
        t.laps.first().startAt shouldBe (12_500.0 plusOrMinus 1e-6)
        t.laps.first().session shouldBe "b"
        t.sessions shouldBe listOf("a", "b")
        // Two runs (an app restart between) start afresh: lap 1 is lost to both.
        val second = Retiming.retime(course(1), listOf("b" to trace(log(41, 300))))!!
        second.laps.size shouldBe 3
        second.laps.first().startAt shouldBe (82_500.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a tablet lap 3 ms off re-timing is flagged, one 1 ms off is not, and each still stands`() {
        val laps = listOf(tabletLap(1, 1), tabletLap(2, 1, endOff = 3), tabletLap(3, 1, endOff = 1, startOff = 3), tabletLap(4, 1, startOff = 1))
        // Lap 2 ends 3 ms late, lap 3 starts 3 ms late: both flagged. Lap 3 ends and lap 4 starts 1 ms late: fine.
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 300, laps))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.TABLET }
        t.laps.map { it.flag != null } shouldBe listOf(false, true, true, false)
        t.laps[1].time shouldBe (70.003 plusOrMinus 1e-9)
        t.laps[1].flag!!.endAt!! shouldBe (152_500.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a lap 1 ms off agrees`() {
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 300, listOf(tabletLap(2, 1, endOff = 1, startOff = -1))))))!!
        t.laps.single { it.source == LapSource.TABLET }.flag shouldBe null
        // The tablet's lap displaces the re-timed one it covers; the rest are re-timed.
        t.laps.map { it.source } shouldBe listOf(LapSource.RETIMED, LapSource.TABLET, LapSource.RETIMED, LapSource.RETIMED)
    }

    @Test
    fun `a current lap with no crossings is placed from its record, unchecked`() {
        val lap = """{"type":"lap","course":"box","courseVersion":1,"layout":"box","lap":2,"time":71.0,"seq":9,"at":152650}"""
        val t = Retiming.retime(course(1), listOf("a" to trace(log(0, 300, listOf(lap)))))!!
        val placed = t.laps.single { it.source == LapSource.TABLET }
        placed.checked shouldBe false
        placed.flag shouldBe null
        placed.endAt shouldBe 152_650.0
        t.laps.size shouldBe 4
    }
}

class RetimerTest {
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val courses = InMemoryCourseStore()
    private val retimer = Retimer(archive, courses)

    private suspend fun session(id: String, lines: List<String>) {
        index.create(SessionRecord(id, "outback", null, null, lines.size - 1L, emptyList(), true, null, 0, Instant.EPOCH, Instant.EPOCH))
        store.put(ArchiveService.sessionKey(id), lines.joinToString("\n", postfix = "\n").toByteArray())
    }

    @Test
    fun `stored beside the run's first session, read back while nothing changed, rebuilt when the course moves on`() {
        runBlocking { movesOn() }
    }

    private suspend fun movesOn() {
        session("a", log(0, 40))
        session("b", log(41, 300))
        courses.save("box", 0, "Box", box(), Instant.EPOCH)
        val first = retimer.timing(listOf("a", "b"), "box")!!
        store.objects.keys.filter { "timing-" in it } shouldBe listOf("sessions/a/timing-v1-box-1.json.gz")

        // Read back: the logs aren't read again.
        val logs = listOf("a", "b").associateWith { store.objects.remove(ArchiveService.sessionKey(it))!! }
        retimer.timing(listOf("a", "b"), "box") shouldBe first
        logs.forEach { (id, bytes) -> store.objects[ArchiveService.sessionKey(id)] = bytes }

        // The course moves on: rebuilt on version 2, version 1's file gone.
        courses.save("box", 1, "Box", box(sfX = 600.0), Instant.EPOCH)
        val second = retimer.timing(listOf("a", "b"), "box")!!
        second.courseVersion shouldBe 2
        second.laps.first().startAt shouldBe (15_000.0 plusOrMinus 1e-6)
        store.objects.keys.filter { "timing-" in it } shouldBe listOf("sessions/a/timing-v1-box-2.json.gz")

        // The run is found shorter (b removed): rebuilt for a alone.
        retimer.timing(listOf("a"), "box")!!.sessions shouldBe listOf("a")
        retimer.timing(listOf("a"), "box")!!.laps.size shouldNotBe second.laps.size
    }

    @Test
    fun `no such course, no timing`() {
        runBlocking {
            session("a", log(0, 300))
            retimer.timing(listOf("a"), "nowhere") shouldBe null
        }
    }
}
