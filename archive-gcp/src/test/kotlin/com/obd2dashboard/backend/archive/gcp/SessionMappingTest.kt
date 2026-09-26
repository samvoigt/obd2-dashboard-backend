package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.Segment
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
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
