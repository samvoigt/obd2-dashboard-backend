package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.MergedSession
import com.obd2dashboard.backend.archive.gcp.FirestoreMergeStore.Companion.mergeFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreMergeStore.Companion.toFields
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

/** A merged session and its Firestore fields (M33.2). */
class MergeMappingTest {
    private val t = Instant.parse("2026-10-17T13:23:25.123456789Z")
    private val merge = MergedSession("5ace0000-1111-4111-8111-0000000000m1", "outback-2018", listOf("s1", "s2", "s3"), "Saturday", t, t.plusSeconds(60), 3)

    @Test
    fun `a merge round-trips through its fields, named or not, split or not`() {
        mergeFrom(merge.id, merge.toFields()) shouldBe merge
        mergeFrom(merge.id, merge.copy(name = null, split = true).toFields()) shouldBe merge.copy(name = null, split = true)
        merge.copy(name = null).toFields().containsKey("name") shouldBe false
    }
}
