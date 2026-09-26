package com.obd2dashboard.backend.live

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessagesTest {
    private val clock = MutableClock()
    private val store = InMemoryMessageStore()
    private val messages = Messages(store, clock)

    @Test
    fun `a sent message is queued, active, and says its age and time left`() = runTest {
        val sent = messages.send("yaris", "  PIT NOW  ", "pit").message
        sent.id shouldMatch Regex("m_[0-9a-f]{16}")
        sent.text shouldBe "PIT NOW"
        sent.state shouldBe MessageState.Queued
        sent.expiresAt shouldBe clock.now.plus(Duration.ofMinutes(30))
        messages.active("yaris").single().id shouldBe sent.id

        clock.advanceMillis(800)
        messages.wire(sent).toString() shouldBe
            """{"id":"${sent.id}","text":"PIT NOW","preset":"pit","ageMs":800,"ttlMs":1799200}"""
    }

    @Test
    fun `a newer message replaces the active one`() = runTest {
        val first = messages.send("yaris", "PIT NOW", "pit").message
        clock.advanceMillis(1_000)
        val second = messages.send("yaris", "FUEL", "fuel")
        second.replaced!!.id shouldBe first.id
        store.get(first.id)!!.let {
            it.state shouldBe MessageState.Replaced
            it.replacedBy shouldBe second.message.id
            it.endedAt shouldBe clock.now
        }
        messages.active("yaris").map { it.id } shouldBe listOf(second.message.id)
    }

    @Test
    fun `cars do not share messages`() = runTest {
        messages.send("yaris", "PIT NOW", "pit")
        val other = messages.send("outback", "PUSH", "push")
        other.replaced.shouldBeNull()
        messages.active("yaris") shouldHaveSize 1
        messages.active("outback") shouldHaveSize 1
    }

    @Test
    fun `states only move forward, and a repeat changes nothing`() = runTest {
        val m = messages.send("yaris", "PIT NOW", "pit").message
        messages.received(m.id).shouldNotBeNull().state shouldBe MessageState.Received
        messages.received(m.id).shouldBeNull() // a repeat
        messages.displayed(m.id).shouldNotBeNull().state shouldBe MessageState.Displayed
        messages.received(m.id).shouldBeNull() // late, after displayed
        store.get(m.id)!!.state shouldBe MessageState.Displayed
        messages.clear(m.id).shouldNotBeNull().state shouldBe MessageState.Cleared
        messages.displayed(m.id).shouldBeNull() // after it ended
        messages.clear(m.id).shouldBeNull()
        messages.active("yaris") shouldBe emptyList()
    }

    @Test
    fun `displayed may come without received`() = runTest {
        val m = messages.send("yaris", "PIT NOW", "pit").message
        messages.displayed(m.id).shouldNotBeNull().let {
            it.state shouldBe MessageState.Displayed
            it.receivedAt.shouldBeNull()
        }
    }

    @Test
    fun `unknown ids are ignored`() = runTest {
        messages.received("m_nope").shouldBeNull()
        messages.displayed("m_nope").shouldBeNull()
        messages.clear("m_nope").shouldBeNull()
    }

    @Test
    fun `an expired message is never active, never revived, and is marked when due`() = runTest {
        val m = messages.send("yaris", "BOX THIS LAP", "box", Duration.ofMinutes(1)).message
        clock.advance(Duration.ofSeconds(59))
        messages.active("yaris") shouldHaveSize 1
        clock.advance(Duration.ofSeconds(1)) // exactly at expiresAt: gone
        messages.active("yaris") shouldBe emptyList()
        messages.syncFrame("yaris") shouldBe """{"t":"messages","active":[]}"""
        messages.received(m.id).shouldBeNull() // a late report cannot revive it
        messages.expireDue("yaris").single().state shouldBe MessageState.Expired
        messages.expireDue("yaris") shouldBe emptyList() // once
    }

    @Test
    fun `a message that ran out is expired, not replaced, when a new one is sent`() = runTest {
        val old = messages.send("yaris", "PUSH", "push", Duration.ofMinutes(1)).message
        clock.advance(Duration.ofMinutes(2))
        messages.send("yaris", "FUEL", "fuel").replaced.shouldBeNull()
        store.get(old.id)!!.state shouldBe MessageState.Expired
    }

    @Test
    fun `clear works even on a message past its time, so the crew's intent is recorded`() = runTest {
        val m = messages.send("yaris", "PIT NOW", "pit", Duration.ofMinutes(1)).message
        clock.advance(Duration.ofMinutes(2))
        messages.clear(m.id).shouldNotBeNull().state shouldBe MessageState.Cleared
    }

    @Test
    fun `the frames are the contract's shapes, as the app parses them`() = runTest {
        val m = messages.send("yaris", "PIT NOW", "pit").message
        clock.advanceMillis(500)
        messages.messageFrame(m) shouldBe """{"t":"message","id":"${m.id}","text":"PIT NOW","preset":"pit","ageMs":500,"ttlMs":1799500}"""
        messages.clearFrame(m.id) shouldBe """{"t":"clear","id":"${m.id}"}"""
        messages.syncFrame("yaris") shouldBe """{"t":"messages","active":[{"id":"${m.id}","text":"PIT NOW","preset":"pit","ageMs":500,"ttlMs":1799500}]}"""
        val plain = messages.send("yaris", "custom words", null).message
        messages.messageFrame(plain) shouldContain "\"text\":\"custom words\""
        messages.messageFrame(plain).contains("preset") shouldBe false
    }

    @Test
    fun `text, preset and lifetime are checked`() {
        Messages.check("", null, Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Bad>()
        Messages.check("   ", null, Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Bad>()
        Messages.check("x".repeat(40), null, Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Ok>()
        Messages.check("x".repeat(41), null, Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Bad>()
        Messages.check("🏁".repeat(40), null, Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Ok>() // characters, not UTF-16 units
        Messages.check("PIT", "teleport", Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Bad>()
        for (p in Messages.PRESETS) Messages.check("x", p, Messages.DEFAULT_TTL).shouldBeInstanceOf<Messages.Checked.Ok>()
        Messages.check("x", null, Duration.ofSeconds(59)).shouldBeInstanceOf<Messages.Checked.Bad>()
        Messages.check("x", null, Duration.ofMinutes(1)).shouldBeInstanceOf<Messages.Checked.Ok>()
        Messages.check("x", null, Duration.ofMinutes(30)).shouldBeInstanceOf<Messages.Checked.Ok>()
        Messages.check("x", null, Duration.ofMinutes(30).plusSeconds(1)).shouldBeInstanceOf<Messages.Checked.Bad>()
    }

    @Test
    fun `send refuses what check refuses`() = runTest {
        shouldThrow<IllegalArgumentException> { messages.send("yaris", "", null) }
        messages.active("yaris") shouldBe emptyList()
    }

    @Test
    fun `recent lists the newest first, ended ones included`() = runTest {
        val a = messages.send("yaris", "A", null).message
        clock.advanceMillis(10)
        val b = messages.send("yaris", "B", null).message
        messages.recent("yaris").map { it.id } shouldBe listOf(b.id, a.id)
    }
}
