package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.DriverChange
import com.obd2dashboard.backend.archive.InMemoryMergeStore
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.MergedSession
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/** A merged session as the session routes answer it (M33.5): one row, one page, its members named. */
class MergedSessionsTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val merges = InMemoryMergeStore()
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore(), merges)
    private val json = Json { ignoreUnknownKeys = true }
    private val t0 = Instant.parse("2026-10-17T13:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)
    private fun id(n: Int) = "5ace0000-1111-4111-8111-0000000035%02d".format(n)
    private val merge = "5ace0000-1111-4111-8111-0000000035ff"

    private fun session(n: Int, from: Long, to: Long, device: String = "tab", laps: Int = 0, best: Double? = null, driver: String? = null,
                        changes: List<DriverChange> = emptyList(), source: String? = null) = runBlocking {
        sessions.create(SessionRecord(id(n), "outback", SessionHeader(id(n), 3, at(from).toString(), device, null, null, null, source), null, 3, emptyList(), true, null, 0, at(from), at(to),
            summary = SessionSummary(SessionSummary.VERSION, at(from).toEpochMilli(), at(to).toEpochMilli(), 4, emptyList(), source = source, track = "nhms", layout = "road", laps = laps,
                bestLap = best?.let { com.obd2dashboard.backend.archive.LapInfo("nhms", "road", 1, it, false, false, null) }, faults = emptyList(), gaps = 1, missed = 0, unreadable = 0, device = device),
            driver = driver, driverChanges = changes))
    }

    init {
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            stores.drivers.put(Driver("d-sam", "Sam", "SAM"))
            stores.drivers.put(Driver("d-hugh", "Hugh", "HUG"))
        }
        // Saturday's shape: 1 Hugh's, then a tablet-only minute nobody set, then 3 Sam's with a change to Hugh.
        session(1, 0, 150, laps = 60, best = 101.5, driver = "d-hugh")
        session(2, 150, 155, source = "tablet")
        session(3, 155, 350, laps = 100, best = 100.9, driver = "d-sam", changes = listOf(DriverChange(at(300).toEpochMilli(), "d-hugh")))
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), InMemoryLiveHub(), messages = testMessages(), crewKey = testCrewKey(), courses = testCourses(), events = stores)
        }
    }

    private fun merged(vararg n: Int, split: Boolean = false, name: String? = null) = runBlocking {
        merges.save(MergedSession(merge, "outback", n.map { id(it) }, name, at(400), at(400), split = split), 0, at(400))!!
    }

    private suspend fun ApplicationTestBuilder.list(): List<Drive> = json.decodeFromString(client.get("/api/cars/outback/sessions").bodyAsText())
    private suspend fun ApplicationTestBuilder.detail(id: String): SessionDetail = json.decodeFromString(client.get("/api/sessions/$id").bodyAsText())

    @Test
    fun `unmerged, the drive suggests its run, merged, one row with the laps summed and the best of all`() = testApplication {
        app()
        list().single().let { it.sessions.size shouldBe 3; it.suggested shouldBe listOf(listOf(id(1), id(2), id(3))) }
        merged(1, 2, 3)
        val drive = list().single()
        drive.suggested shouldBe emptyList()
        val row = drive.sessions.single()
        row.id shouldBe merge
        row.members shouldBe listOf(id(1), id(2), id(3))
        row.started shouldBe at(0).toEpochMilli()
        row.ended shouldBe at(350).toEpochMilli()
        row.laps shouldBe 160
        row.bestLap shouldBe LapView(1, 100.9)
        row.source shouldBe null // a car's and a tablet's together: not "tablet"
    }

    @Test
    fun `the merged page names its members, and who drove runs across them`() = testApplication {
        app()
        merged(1, 2, 3, name = "Saturday")
        val d = detail(merge)
        d.session.id shouldBe merge
        d.session.name shouldBe "Saturday"
        d.gaps shouldBe 3
        d.members.map { it.id } shouldBe listOf(id(1), id(2), id(3))
        d.members.map { it.driver?.code } shouldBe listOf("HUG", null, "SAM")
        // Hugh from the start; 2, set to nobody, carries him on; Sam from 3's start; Hugh again at 3's change.
        d.driver?.code shouldBe "HUG"
        d.driverChanges.map { it.from to it.driver?.code } shouldBe listOf(at(155).toEpochMilli() to "SAM", at(300).toEpochMilli() to "HUG")
        d.mergedInto shouldBe null
    }

    @Test
    fun `a member's page says which merge it's in, an unmerged merge answers as its first member`() = testApplication {
        app()
        merged(1, 2)
        detail(id(2)).let { it.session.id shouldBe id(2); it.mergedInto shouldBe merge }
        detail(id(3)).mergedInto shouldBe null
        runBlocking { merges.save(merges.get(merge)!!.copy(split = true), 1, at(500)) }
        detail(merge).let { it.session.id shouldBe id(1); it.mergedInto shouldBe null }
        detail(id(2)).mergedInto shouldBe null
        list().single().sessions.size shouldBe 3
    }

    private fun item(n: Int, from: Long, to: Long, state: String = "complete", source: String? = null, part: String? = null, members: List<String> = emptyList()) =
        SessionItem(id(n), at(from).toEpochMilli(), at(to).toEpochMilli(), 1, state, source = source, event = part?.let { EventRef("weekend", "The weekend", it) }, members = members)

    @Test
    fun `suggestions - same tablet, consecutive, complete, unmerged, one part or none`() {
        val devices = (1..9).associate { id(it) to "tab" } + (id(4) to "other")
        suggestMerges(listOf(item(1, 0, 10), item(2, 10, 20), item(3, 20, 30)), devices) shouldBe listOf(listOf(id(1), id(2), id(3)))
        // Another tablet breaks the run, and a lone session isn't a suggestion.
        suggestMerges(listOf(item(1, 0, 10), item(4, 10, 20), item(3, 20, 30)), devices) shouldBe emptyList()
        // Uploading or live breaks it; so does a merged row; so does test data.
        suggestMerges(listOf(item(1, 0, 10), item(2, 10, 20, state = "uploading"), item(3, 20, 30), item(5, 30, 40)), devices) shouldBe listOf(listOf(id(3), id(5)))
        suggestMerges(listOf(item(1, 0, 10), item(2, 10, 20, members = listOf("x", "y")), item(3, 20, 30)), devices) shouldBe emptyList()
        suggestMerges(listOf(item(1, 0, 10), item(2, 10, 20, source = "fake"), item(3, 20, 30)), devices) shouldBe emptyList()
        // Another part breaks it.
        suggestMerges(listOf(item(1, 0, 10, part = "Practice"), item(2, 10, 20, part = "Practice"), item(3, 20, 30, part = "Race")), devices) shouldBe listOf(listOf(id(1), id(2)))
        // A tablet not known (older logs) is never guessed at.
        suggestMerges(listOf(item(1, 0, 10), item(2, 10, 20)), emptyMap()) shouldBe emptyList()
    }
}
