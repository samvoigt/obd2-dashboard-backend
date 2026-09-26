package com.obd2dashboard.backend.registry.firestore

import com.google.cloud.Timestamp
import com.google.cloud.firestore.FieldValue
import com.obd2dashboard.backend.registry.Car
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore.Companion.toFields
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

class FieldMappingTest {
    private val car = Car(
        slug = Slug.parse("yaris"),
        name = "Yaris",
        tokenHash = "ab".repeat(32),
        tokenHint = "WXYZ",
        tokenIssued = Instant.parse("2026-09-26T12:00:00.123456Z"),
        passcodeHash = null,
        created = Instant.parse("2026-09-26T11:00:00Z"),
        updated = Instant.parse("2026-09-26T12:00:00.123456Z"),
    )

    @Test
    fun `the slug is the document ID, not a field`() {
        car.toFields(forUpdate = false) shouldNotContainKey "slug"
    }

    @Test
    fun `fields and timestamps map across exactly`() {
        val fields = car.toFields(forUpdate = false)
        fields["name"] shouldBe "Yaris"
        fields["tokenHash"] shouldBe car.tokenHash
        fields["tokenHint"] shouldBe "WXYZ"
        fields["tokenIssued"] shouldBe Timestamp.parseTimestamp("2026-09-26T12:00:00.123456Z")
        fields["created"] shouldBe Timestamp.parseTimestamp("2026-09-26T11:00:00Z")
    }

    @Test
    fun `a missing passcode is absent on create and deleted on update`() {
        car.toFields(forUpdate = false) shouldNotContainKey "passcodeHash"
        car.toFields(forUpdate = true)["passcodeHash"] shouldBe FieldValue.delete()
    }

    @Test
    fun `a set passcode is written either way`() {
        val withPasscode = car.copy(passcodeHash = "pbkdf2-sha256\$1\$AA\$AA")
        withPasscode.toFields(forUpdate = false) shouldContainKey "passcodeHash"
        withPasscode.toFields(forUpdate = true)["passcodeHash"] shouldBe "pbkdf2-sha256\$1\$AA\$AA"
    }
}
