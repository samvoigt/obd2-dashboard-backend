package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.Bounds
import com.obd2dashboard.backend.archive.LapInfo
import com.obd2dashboard.backend.archive.Segment
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.archive.SignalInfo
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex.Companion.recordFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex.Companion.toFields
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

class SessionMappingTest {
    private val now = Instant.parse("2026-09-26T12:00:00.123456Z")
    private val id = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
    private val header = SessionHeader(id, 3, "2026-09-24T13:08:32.623Z", "dev", "1.0", vin = null, protocol = "CAN")
    private val record = SessionRecord(
        id = id, car = "yaris", header = header, line0Sha256 = "ab", ackedThrough = 12,
        segments = listOf(
            Segment(0, 0, "k0", firstSeq = 0, lastSeq = 0),
            Segment(1, 12, "k1", firstSeq = 1, lastSeq = null),
        ),
        complete = false, sha256 = null, hashResets = 1, created = now, updated = now,
    )

    @Test
    fun `a record round-trips through its fields`() {
        recordFrom(id, record.toFields()) shouldBe record
    }

    @Test
    fun `a summary round-trips, with and without a best lap, and is absent until built`() {
        record.toFields() shouldNotContainKey "summary"
        val summary = SessionSummary(
            version = 1, started = 1790000000000, ended = 1790003600000, lines = 180_000,
            signals = listOf(SignalInfo("engine.rpm", "rpm", "number"), SignalInfo("gps.position", "", "position")),
            track = "nhms", layout = "Road Course", laps = 12,
            bestLap = LapInfo("nhms", "Road Course", 7, 94.532, pitIn = false, pitOut = false, wall = 1790001000000),
            faults = listOf("P0420"), gaps = 2, missed = 15, unreadable = 0,
        )
        val complete = record.copy(complete = true, segments = emptyList(), summary = summary)
        recordFrom(id, complete.toFields()) shouldBe complete
        val bare = complete.copy(summary = summary.copy(track = null, layout = null, laps = 0, bestLap = null, faults = emptyList()))
        recordFrom(id, bare.toFields()) shouldBe bare
        // M11: a tablet's or a test session's source, in the summary.
        val tablet = complete.copy(summary = summary.copy(source = "tablet"))
        recordFrom(id, tablet.toFields()) shouldBe tablet
        // M13: the device, the span of at, and the fixes' bounds.
        val run = complete.copy(summary = summary.copy(device = "tab-1", firstAt = 5_000, lastAt = 9_000, bounds = Bounds(-71.47, 43.36, -71.46, 43.37)))
        recordFrom(id, run.toFields()) shouldBe run
    }

    @Test
    fun `a header's source is stored when there is one, and absent otherwise (M11)`() {
        record.toFields() shouldNotContainKey "source"
        val fake = record.copy(header = header.copy(source = "fake"))
        fake.toFields()["source"] shouldBe "fake"
        recordFrom(id, fake.toFields()).header!!.source shouldBe "fake"
    }

    @Test
    fun `absent header fields stay absent, the VIN included`() {
        val fields = record.toFields()
        fields shouldNotContainKey "vin"
        fields shouldNotContainKey "sha256"
        fields["protocol"] shouldBe "CAN"
    }

    @Test
    fun `a VIN is stored when there is one`() {
        val withVin = record.copy(header = header.copy(vin = "TSTVEHCLE00000001"))
        withVin.toFields()["vin"] shouldBe "TSTVEHCLE00000001"
        recordFrom(id, withVin.toFields()).header!!.vin shouldBe "TSTVEHCLE00000001"
    }

    @Test
    fun `a session the live lane created has no header and no line 0`() {
        val live = record.copy(header = null, line0Sha256 = null, ackedThrough = -1, segments = emptyList())
        val fields = live.toFields()
        fields shouldNotContainKey "started"
        fields shouldNotContainKey "line0Sha256"
        recordFrom(id, fields) shouldBe live
    }
}
