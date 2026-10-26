package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.DriverChange
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** A session following straight on carries its driver over (M32): same tablet, back to back (10 min). */
class DriverCarryTest {
    private val index = InMemorySessionIndex()
    private val archive = ArchiveService(index, InMemorySegmentStore())
    private val t0 = Instant.parse("2026-10-18T13:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)
    private val prev = "5ace0000-1111-4111-8111-0000000000a1"
    private val next = "5ace0000-1111-4111-8111-0000000000a2"

    private fun session(id: String, created: Instant, updated: Instant, device: String? = "tab", driver: String? = null,
                        changes: List<DriverChange> = emptyList(), source: String? = null, car: String = "yaris") = runBlocking {
        index.create(SessionRecord(id, car, SessionHeader(id, 3, created.toString(), device, null, null, null, source), null, 3, emptyList(), false, null, 0,
            created, updated, driver = driver, driverChanges = changes))
    }

    @Test
    fun `same tablet, back to back, carries the driver the session before ended with`() {
        session(prev, at(0), at(60), driver = "d-sam")
        session(next, at(65), at(65))
        runBlocking { carryDriver(archive, "yaris", next) } shouldBe "d-sam"
        runBlocking { index.get(next)!!.driver } shouldBe "d-sam"
    }

    @Test
    fun `the driver it ended with is its last change's, and nobody if that was nobody`() {
        session(prev, at(0), at(60), driver = "d-sam", changes = listOf(DriverChange(1, "d-drew")))
        session(next, at(62), at(62))
        runBlocking { carryDriver(archive, "yaris", next) } shouldBe "d-drew"
        val other = "5ace0000-1111-4111-8111-0000000000a3"
        session(other, at(64), at(64), driver = "d-sam", changes = listOf(DriverChange(1, null)), car = "gti")
        val after = "5ace0000-1111-4111-8111-0000000000a4"
        session(after, at(66), at(66), car = "gti")
        runBlocking { carryDriver(archive, "gti", after) } shouldBe null
    }

    @Test
    fun `exactly 10 min apart carries, 11 doesn't`() {
        session(prev, at(0), at(60), driver = "d-sam")
        session(next, at(70), at(70))
        runBlocking { carryDriver(archive, "yaris", next) } shouldBe "d-sam"
        val later = "5ace0000-1111-4111-8111-0000000000a5"
        session(later, at(81), at(81)) // 11 min after next was last heard
        runBlocking { carryDriver(archive, "yaris", later) } shouldBe null
    }

    @Test
    fun `another tablet, a driver already set, or test data on either side carries nothing`() {
        session(prev, at(0), at(60), driver = "d-sam")
        session(next, at(65), at(65), device = "other")
        runBlocking { carryDriver(archive, "yaris", next) } shouldBe null
        // Set already (the crew was quick): untouched.
        val set = "5ace0000-1111-4111-8111-0000000000b1"
        session(set, at(0), at(60), driver = "d-sam", car = "gti")
        val mine = "5ace0000-1111-4111-8111-0000000000b2"
        session(mine, at(61), at(61), driver = "d-alex", car = "gti")
        runBlocking { carryDriver(archive, "gti", mine) } shouldBe null
        runBlocking { index.get(mine)!!.driver } shouldBe "d-alex"
        // Test data.
        val fakeBefore = "5ace0000-1111-4111-8111-0000000000c1"
        session(fakeBefore, at(0), at(60), driver = "d-sam", source = "fake", car = "mx5")
        val real = "5ace0000-1111-4111-8111-0000000000c2"
        session(real, at(61), at(61), car = "mx5")
        runBlocking { carryDriver(archive, "mx5", real) } shouldBe null
    }

    @Test
    fun `a restart of the app (at begun again) is carried too`() {
        // The record doesn't see `at`; same tablet and back to back is all it takes, restarts included (Sam).
        session(prev, at(0), at(60), driver = "d-sam")
        session(next, at(61), at(61))
        runBlocking { carryDriver(archive, "yaris", next) } shouldBe "d-sam"
    }

    @Test
    fun `the archive's PUT of a session's line 0 carries the driver over`() = testApplication {
        val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
        val token = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
        application {
            module(registry, archive, InMemoryLiveHub(), messages = testMessages(), crewKey = testCrewKey(), courses = testCourses(), events = testEvents())
        }
        // The session before, Sam driving, last heard a moment ago (the server's clock): the next one opens now.
        val now = Instant.now()
        session(prev, now.minusSeconds(3600), now.minusSeconds(30), driver = "d-sam")
        val line0 = """{"type":"session","v":3,"id":"$next","device":"tab","started":"2026-10-18T14:00:00Z","signals":[],"seq":0,"at":0,"wall":1}"""
        client.put("/v1/sessions/$next") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(line0) }.status shouldBe HttpStatusCode.Created
        runBlocking { index.get(next)!!.driver } shouldBe "d-sam"
    }
}
