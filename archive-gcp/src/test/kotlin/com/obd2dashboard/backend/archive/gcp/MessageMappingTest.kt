package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.gcp.FirestoreMessageStore.Companion.messageFrom
import com.obd2dashboard.backend.archive.gcp.FirestoreMessageStore.Companion.toFields
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test

class MessageMappingTest {
    private val sent = Instant.parse("2026-09-26T12:00:00.123456Z")
    private val m = Message("m_0011223344556677", "yaris", "PIT NOW", "pit", sent, sent.plusSeconds(1800), MessageState.Queued)

    @Test
    fun `a new message round-trips, absent fields staying absent`() {
        val fields = m.toFields()
        for (absent in listOf("receivedAt", "displayedAt", "endedAt", "replacedBy")) fields shouldNotContainKey absent
        fields["state"] shouldBe "queued"
        messageFrom(m.id, fields) shouldBe m
    }

    @Test
    fun `an ended message round-trips with every field`() {
        val ended = m.copy(
            state = MessageState.Replaced, receivedAt = sent.plusSeconds(1), displayedAt = sent.plusSeconds(2),
            endedAt = sent.plusSeconds(60), replacedBy = "m_ffeeddccbbaa9988",
        )
        messageFrom(m.id, ended.toFields()) shouldBe ended
    }

    @Test
    fun `a message without a preset has no preset field`() {
        m.copy(preset = null).toFields() shouldNotContainKey "preset"
    }
}
