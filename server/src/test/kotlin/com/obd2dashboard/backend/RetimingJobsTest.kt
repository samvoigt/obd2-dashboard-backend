package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** When re-timing runs (M13.4). */
class RetimingJobsTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store)
    private val courses = InMemoryCourseStore()

    private suspend fun session(id: String, car: String, lines: List<String>) {
        index.create(SessionRecord(id, car, null, null, lines.size - 1L, emptyList(), true, null, 0, Instant.EPOCH, Instant.EPOCH))
        store.put(ArchiveService.sessionKey(id), lines.joinToString("\n", postfix = "\n").toByteArray())
    }

    private fun timingFiles() = store.objects.keys.filter { "/timing-" in it }.sorted()

    @Test
    fun `a course's save re-times every run there, one at a time, a failure counted and the rest carried on`(): Unit = runBlocking {
        registry.addCar(Slug.parse("outback"), "Outback")
        registry.addCar(Slug.parse("yaris"), "Yaris")
        session("a", "outback", boxLog(0, 40))
        session("b", "outback", boxLog(41, 300))
        session("y", "yaris", boxLog(0, 300))
        session("gone", "yaris", boxLog(1000, 1300, device = "tab-9"))
        archive.summary("gone") // summarised, then its log lost: re-timing it fails
        store.objects.remove(ArchiveService.sessionKey("gone"))
        val jobs = RetimingJobs(registry, archive, courses, this)
        val course = courses.save("box", 0, "Box", boxGeoJson(), Instant.EPOCH)!!

        jobs.courseSaved(course).join()

        jobs.progress("box") shouldBe RetimingProgress(version = 1, runs = 3, sessions = 4, done = 3, failed = 1, finished = true)
        timingFiles() shouldBe listOf("sessions/a/timing-v1-box-1.json.gz", "sessions/y/timing-v1-box-1.json.gz")
        jobs.progress("elsewhere") shouldBe null
    }

    @Test
    fun `a newer save of the course replaces an earlier one's job`(): Unit = runBlocking {
        registry.addCar(Slug.parse("outback"), "Outback")
        session("a", "outback", boxLog(0, 300))
        val jobs = RetimingJobs(registry, archive, courses, this)
        val v1 = courses.save("box", 0, "Box", boxGeoJson(), Instant.EPOCH)!!
        val first = jobs.courseSaved(v1) // not started yet: runBlocking's one thread is still here
        val v2 = courses.save("box", 1, "Box", boxGeoJson(sfX = 600.0), Instant.EPOCH)!!
        val second = jobs.courseSaved(v2)
        second.join()
        first.isCancelled shouldBe true
        jobs.progress("box")!!.version shouldBe 2
        timingFiles() shouldBe listOf("sessions/a/timing-v1-box-2.json.gz")
    }

    @Test
    fun `a prepared session's run is re-timed on each course it touches`(): Unit = runBlocking {
        registry.addCar(Slug.parse("outback"), "Outback")
        courses.save("box", 0, "Box", boxGeoJson(), Instant.EPOCH)
        courses.save("far", 0, "Far", Json.parseToJsonElement(boxGeoJson().toString().replace("-71.4", "-72.4")).jsonObject, Instant.EPOCH)
        session("a", "outback", boxLog(0, 40))
        session("b", "outback", boxLog(41, 300))
        val jobs = RetimingJobs(registry, archive, courses, this)
        jobs.sessionPrepared("outback", "b")
        timingFiles() shouldBe listOf("sessions/a/timing-v1-box-1.json.gz")
        // No courses, nothing to do.
        RetimingJobs(registry, archive, InMemoryCourseStore(), this).sessionPrepared("outback", "b")
    }

    @Test
    fun `a session uploaded and completed is re-timed after it is prepared`() = testApplication {
        val token = runBlocking {
            courses.save("box", 0, "Box", boxGeoJson(), Instant.EPOCH)
            registry.addCar(Slug.parse("outback"), "Outback").token
        }
        application { module(registry, archive, InMemoryLiveHub(), messages = testMessages(), courses = courses, crewKey = testCrewKey()) }
        val id = "5ace0000-1111-4111-8111-000000000013"
        val lines = boxLog(0, 300).mapIndexed { i, l -> if (i == 0) l.replace("\"id\":\"s\"", "\"id\":\"$id\"") else l }
        val body = lines.joinToString("") { "$it\n" }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        client.put("/v1/sessions/$id") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(lines[0]) }
            .status shouldBe HttpStatusCode.Created
        client.post("/v1/sessions/$id/chunks") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, "application/x-ndjson")
            header("X-First-Index", "1")
            header("X-Record-Count", (lines.size - 1).toString())
            setBody(lines.drop(1).joinToString("") { "$it\n" })
        }.status shouldBe HttpStatusCode.OK
        client.post("/v1/sessions/$id/complete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"lastIndex": ${lines.size - 1}, "recordCount": ${lines.size}, "sha256": "$sha"}""")
        }.status shouldBe HttpStatusCode.OK
        val until = System.currentTimeMillis() + 10_000
        while (timingFiles().isEmpty()) {
            check(System.currentTimeMillis() < until) { "not re-timed within 10 s" }
            Thread.sleep(20)
        }
        timingFiles() shouldBe listOf("sessions/$id/timing-v1-box-1.json.gz")
    }
}
