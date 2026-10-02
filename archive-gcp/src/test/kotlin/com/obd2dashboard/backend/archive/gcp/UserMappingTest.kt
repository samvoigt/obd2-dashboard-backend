package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.archive.gcp.FirestoreAccessStore.Companion.accessFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreAccessStore.Companion.toFields
import com.obd2dashboard.backend.archive.gcp.FirestoreUserStore.Companion.toFields
import com.obd2dashboard.backend.archive.gcp.FirestoreUserStore.Companion.userFrom
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

/** Users and access records and their Firestore fields (M23). */
class UserMappingTest {
    @Test
    fun `a user round-trips, their email the document's id`() {
        val user = User("ann@example.com", "sam@example.com", Instant.parse("2026-10-01T12:00:00.123456789Z"))
        userFrom("ann@example.com", user.toFields()) shouldBe user
    }

    @Test
    fun `an access record round-trips, its editors a plain sorted list of lower-case emails`() {
        val access = Access("ann@example.com", setOf("Ed@Example.com", "bo@example.com"))
        val fields = access.toFields()
        fields["editors"] shouldBe listOf("bo@example.com", "ed@example.com")
        (fields["editors"] as List<*>).none { it is List<*> } shouldBe true
        accessFrom(fields) shouldBe Access("ann@example.com", setOf("bo@example.com", "ed@example.com"))
    }

    @Test
    fun `thing keys are document ids Firestore accepts as they are`() {
        listOf("car:outback", "event:nhms-october", "course:nhms", "driver:d-0a1b2c3d").all { isDocumentId(it) } shouldBe true
        isDocumentId("ann@example.com") shouldBe true
    }
}
