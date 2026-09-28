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
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RetimingTest {
    @Test
    fun `the tablet's laps on the current version stand as sent, and agree`() {
        val laps = (1..4).map { boxLap(it, version = 1) }
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300, laps))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.TABLET }
        t.laps.map { it.tabletLap } shouldBe listOf(1, 2, 3, 4)
        t.laps.map { it.flag } shouldBe List(4) { null }
        t.retimed.size shouldBe 4
    }

    @Test
    fun `laps timed on an older version are re-timed on the new one`() {
        // Version 2 moves the start/finish 100 m on: 2.5 s later.
        val laps = (1..4).map { boxLap(it, version = 1) }
        val t = Retiming.retime(boxCourse(2, sfX = 600.0), listOf("a" to boxTrace(boxLog(0, 300, laps))))!!
        t.courseVersion shouldBe 2
        t.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
        for ((i, lap) in t.laps.withIndex()) {
            lap.startAt shouldBe (15_000.0 + i * 70_000 plusOrMinus 1e-6)
            lap.time shouldBe (70.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a session with no laps of its own, or older ones by layout name, is timed`() {
        Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300))))!!.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
        val old = (1..4).map { boxLap(it, version = null) }
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300, old))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
        // Named "Box", not the default layout: timed on it.
        Retiming.retime(Course("box", "Box", 1, boxGeoJson(short = true), Instant.EPOCH), listOf("a" to boxTrace(boxLog(0, 300, old))))!!.layout shouldBe "box"
    }

    @Test
    fun `laps timed on another course don't stand here`() {
        val elsewhere = (1..4).map { boxLap(it, version = 1).replace("\"course\":\"box\"", "\"course\":\"elsewhere\"") }
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300, elsewhere))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.RETIMED }
    }

    @Test
    fun `a lap across two sessions of one run is one lap, in the session it ended in`() {
        // The OBD link drops at 40 s, mid-lap 1 (12.5 to 82.5 s).
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 40)), "b" to boxTrace(boxLog(41, 300))))!!
        t.laps.size shouldBe 4
        t.laps.first().startAt shouldBe (12_500.0 plusOrMinus 1e-6)
        t.laps.first().session shouldBe "b"
        t.sessions shouldBe listOf("a", "b")
        // Two runs (an app restart between) start afresh: lap 1 is lost to both.
        val second = Retiming.retime(boxCourse(1), listOf("b" to boxTrace(boxLog(41, 300))))!!
        second.laps.size shouldBe 3
        second.laps.first().startAt shouldBe (82_500.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a tablet lap 3 ms off re-timing is flagged, one 1 ms off is not, and each still stands`() {
        val laps = listOf(boxLap(1, 1), boxLap(2, 1, endOff = 3), boxLap(3, 1, endOff = 1, startOff = 3), boxLap(4, 1, startOff = 1))
        // Lap 2 ends 3 ms late, lap 3 starts 3 ms late: both flagged. Lap 3 ends and lap 4 starts 1 ms late: fine.
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300, laps))))!!
        t.laps.map { it.source } shouldBe List(4) { LapSource.TABLET }
        t.laps.map { it.flag != null } shouldBe listOf(false, true, true, false)
        t.laps[1].time shouldBe (70.003 plusOrMinus 1e-9)
        t.laps[1].flag!!.endAt!! shouldBe (152_500.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a lap 1 ms off agrees`() {
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300, listOf(boxLap(2, 1, endOff = 1, startOff = -1))))))!!
        t.laps.single { it.source == LapSource.TABLET }.flag shouldBe null
        // The tablet's lap displaces the re-timed one it covers; the rest are re-timed.
        t.laps.map { it.source } shouldBe listOf(LapSource.RETIMED, LapSource.TABLET, LapSource.RETIMED, LapSource.RETIMED)
    }

    @Test
    fun `a current lap with no crossings is placed from its record, unchecked`() {
        val lap = """{"type":"lap","course":"box","courseVersion":1,"layout":"box","lap":2,"time":71.0,"seq":9,"at":152650}"""
        val t = Retiming.retime(boxCourse(1), listOf("a" to boxTrace(boxLog(0, 300, listOf(lap)))))!!
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
        session("a", boxLog(0, 40))
        session("b", boxLog(41, 300))
        courses.save("box", 0, "Box", boxGeoJson(), Instant.EPOCH)
        val first = retimer.timing(listOf("a", "b"), "box")!!
        store.objects.keys.filter { "timing-" in it } shouldBe listOf("sessions/a/timing-v1-box-1.json.gz")

        // Read back: the logs aren't read again.
        val logs = listOf("a", "b").associateWith { store.objects.remove(ArchiveService.sessionKey(it))!! }
        retimer.timing(listOf("a", "b"), "box") shouldBe first
        logs.forEach { (id, bytes) -> store.objects[ArchiveService.sessionKey(id)] = bytes }

        // The course moves on: rebuilt on version 2, version 1's file gone.
        courses.save("box", 1, "Box", boxGeoJson(sfX = 600.0), Instant.EPOCH)
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
            session("a", boxLog(0, 300))
            retimer.timing(listOf("a"), "nowhere") shouldBe null
        }
    }
}
