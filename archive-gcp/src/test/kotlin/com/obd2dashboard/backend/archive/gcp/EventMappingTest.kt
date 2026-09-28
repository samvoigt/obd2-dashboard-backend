package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.gcp.FirestoreDriverStore.Companion.driverFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreDriverStore.Companion.toFields
import com.obd2dashboard.backend.archive.gcp.FirestoreEventStore.Companion.eventFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreEventStore.Companion.toFields
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

/** Drivers and events and their Firestore fields (M14.2). */
class EventMappingTest {
    private val t = Instant.parse("2026-10-04T13:00:00.123456789Z")
    private val event = Event(
        "nhms-october", "NHMS October", "2026-10-04", "nhms", "road", listOf("outback-2018", "yaris"),
        listOf(
            Part("p1", PartKind.PRACTICE, "Practice 1", t, t.plusSeconds(3600), added = listOf("s-late"), removed = listOf("s-bad")),
            Part("p3", PartKind.RACE, "The race", t.plusSeconds(7200), t.plusSeconds(7200 + 6 * 3600),
                green = t.plusSeconds(7300), flag = t.plusSeconds(7300 + 6 * 3600),
                stints = mapOf("outback-2018" to listOf(com.obd2dashboard.backend.events.Stint(1_790_000_000_000, "d-sam"), com.obd2dashboard.backend.events.Stint(1_790_003_600_000, null)))),
        ),
        revision = 4, updated = t,
    )

    @Test
    fun `an event round-trips through its fields, parts and hand-made changes and all`() {
        eventFrom("nhms-october", event.toFields()) shouldBe event
        eventFrom("nhms-october", event.copy(parts = emptyList()).toFields()) shouldBe event.copy(parts = emptyList())
    }

    @Test
    fun `a part's kind is stored in words, and no list holds a list`() {
        @Suppress("UNCHECKED_CAST")
        val parts = event.toFields()["parts"] as List<Map<String, Any>>
        parts.map { it["kind"] } shouldBe listOf("practice", "race")
        parts.all { part -> part.values.none { v -> v is List<*> && v.any { it is List<*> } } } shouldBe true
        (parts[1]["stints"] as Map<*, *>).values.all { list -> (list as List<*>).none { it is List<*> } } shouldBe true
        parts[0].containsKey("stints") shouldBe false
    }

    @Test
    fun `an id Firestore can't hold is never asked for`() {
        isDocumentId("d-7f3a") shouldBe true
        isDocumentId("nhms-october") shouldBe true
        for (bad in listOf("", ".", "..", "a/b")) isDocumentId(bad) shouldBe false
    }

    @Test
    fun `a driver round-trips through its fields`() {
        val sam = Driver("d-7f3a", "Sam", "SAM")
        driverFrom("d-7f3a", sam.toFields()) shouldBe sam
    }
}
