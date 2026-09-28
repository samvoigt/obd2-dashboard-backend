package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Who drove a session (M14.2): set, cleared, and kept by the index's other changes. */
class SessionDriverTest {
    @Test
    fun `a session's driver is set, kept through other changes, and cleared`() = runTest {
        val index = InMemorySessionIndex()
        val archive = ArchiveService(index, InMemorySegmentStore())
        index.create(SessionRecord("s", "outback", null, null, 0, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
        archive.setDriver("s", "d1") shouldBe true
        index.get("s")!!.driver shouldBe "d1"
        index.append("s", 0, Segment(1, 1, "k", null, null), Instant.EPOCH) shouldBe true
        index.complete("s", 1, "sha", Instant.EPOCH) shouldBe true
        index.get("s")!!.driver shouldBe "d1"
        archive.setDriver("s", null) shouldBe true
        index.get("s")!!.driver shouldBe null
        archive.setDriver("nope", "d1") shouldBe false
    }
}
