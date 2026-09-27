package com.obd2dashboard.backend.live

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import org.junit.Test

class CarLiveTest {
    private val clock = MutableClock()
    private val car = CarLive(clock, historyWindow = Duration.ofMinutes(5), historyCap = 1_000)

    private fun apply(text: String) = car.apply(parse(text))

    private fun latestValue(signal: String): String? =
        car.snapshot().latest.firstOrNull { it.string("signal") == signal }?.get("value")?.toString()

    @Test
    fun `a session, a snapshot and batches build the car's state`() {
        car.connected()
        apply(sessionFrame())
        apply(snapshotFrame(sample("engine.rpm", 900.0, 1), sample("vehicle.speed", 0.0, 2)))
        apply(batch(sample("engine.rpm", 1800.0, 3)))

        latestValue("engine.rpm") shouldBe "1800.0"
        latestValue("vehicle.speed") shouldBe "0.0"
        car.snapshot().history shouldHaveSize 3
        car.status().freshness(clock.now) shouldBe Freshness.Live
    }

    @Test
    fun `the status names the live session only while in it`() {
        car.status().sessionId.shouldBeNull()
        car.connected()
        apply(sessionFrame())
        car.status().sessionId shouldBe SESSION
        apply("""{"t":"end","session":"$SESSION"}""")
        car.status().sessionId.shouldBeNull()
    }

    @Test
    fun `nothing derived from the session holds the VIN`() {
        car.connected()
        val updates = apply(sessionFrame()).shouldBeInstanceOf<CarLive.Applied.Ok>().updates
        updates.toString() shouldNotContain VIN
        car.snapshot().toString() shouldNotContain VIN
    }

    @Test
    fun `a snapshot replaces the latest values, stopped and fault`() {
        apply(sessionFrame())
        apply(batch(sample("engine.rpm", 1.0, 1), sample("old.signal", 2.0, 2),
            """{"type":"stopped","signal":"engine.oil_temperature","reason":"r","seq":3}""",
            """{"type":"fault","codes":["P0301"],"seq":4}"""))
        apply(snapshotFrame(sample("engine.rpm", 5.0, 10)))
        val s = car.snapshot()
        s.latest.map { it.string("signal") } shouldBe listOf("engine.rpm")
        s.stopped shouldBe emptyList()
        s.fault.shouldBeNull()
    }

    @Test
    fun `stopped, fault and a new signals list are kept from batches`() {
        apply(sessionFrame())
        apply(batch(
            """{"type":"stopped","signal":"engine.oil_temperature","reason":"r","seq":1}""",
            """{"type":"fault","codes":["P0301"],"seq":2}""",
            """{"type":"signals","signals":[{"name":"fuel.tank_level","unit":"%","kind":"number"}],"seq":3}""",
        ))
        val s = car.snapshot()
        s.stopped.single().string("signal") shouldBe "engine.oil_temperature"
        s.fault!!.string("type") shouldBe "fault"
        s.signals.toString() shouldBe """[{"name":"fuel.tank_level","unit":"%","kind":"number"}]"""
    }

    @Test
    fun `the same session again keeps its state, and a new one starts afresh`() {
        apply(sessionFrame())
        apply(batch(sample("engine.rpm", 1.0, 1)))
        apply(sessionFrame()) // a reconnect re-sends it
        car.snapshot().history shouldHaveSize 1
        apply(sessionFrame(id = OTHER_SESSION))
        car.snapshot().history shouldHaveSize 0
        car.snapshot().latest shouldBe emptyList()
        car.sessionId shouldBe OTHER_SESSION
    }

    @Test
    fun `frames for a session not announced are refused`() {
        apply(sessionFrame())
        apply(batch(sample("x", 1.0, 1), session = OTHER_SESSION)).shouldBeInstanceOf<CarLive.Applied.Refused>()
        apply(snapshotFrame(session = OTHER_SESSION)).shouldBeInstanceOf<CarLive.Applied.Refused>()
        apply("""{"t":"end","session":"$OTHER_SESSION"}""").shouldBeInstanceOf<CarLive.Applied.Refused>()
        car.snapshot().history shouldBe emptyList()
    }

    /** A reading written at [wall] on the tablet's clock. */
    private fun at(wall: Long, seq: Long) = """{"type":"sample","signal":"engine.rpm","value":1,"seq":$seq,"at":$seq,"wall":$wall}"""

    /** A batch sent now, its newest reading [behind] ms before now on a tablet [slow] ms slow. */
    private fun batchFromTablet(slow: Long, seq: Long, behind: Long = 0) {
        val now = clock.now.toEpochMilli() - slow - behind
        apply(batch(at(now - 150, seq), at(now, seq + 1)))
    }

    @Test
    fun `the tablet's clock is measured from its batches, as the first drive's was 10 h 58 min slow (M11)`() {
        apply(sessionFrame())
        car.status().clockOffset.shouldBeNull() // nothing streamed yet
        val slow = Duration.ofHours(10).plusMinutes(58).toMillis()
        // A snapshot's readings can be minutes old, so it doesn't count: before any batch, no offset
        // (not one ten minutes too big).
        apply(snapshotFrame(at(clock.now.toEpochMilli() - slow - 600_000, 900)))
        car.status().clockOffset.shouldBeNull()
        for (i in 0 until 10) { batchFromTablet(slow, seq = 10L * i, behind = 40L + i % 3 * 20); clock.advanceMillis(200) }
        car.status().clockOffset shouldBe Duration.ofMillis(slow + 40) // the least delayed batch
        car.disconnected()
        car.status().clockOffset shouldBe Duration.ofMillis(slow + 40) // kept, to say what it last was
    }

    @Test
    fun `one batch sent late doesn't move it, and a corrected clock shows at once (M11)`() {
        apply(sessionFrame())
        val slow = Duration.ofHours(11).toMillis()
        for (i in 0 until 5) { batchFromTablet(slow, seq = 10L * i); clock.advanceMillis(200) }
        batchFromTablet(slow, seq = 100, behind = 240_000) // held in a dead zone for 4 minutes
        car.status().clockOffset shouldBe Duration.ofMillis(slow)
        batchFromTablet(0, seq = 200) // the tablet's clock set right
        car.status().clockOffset shouldBe Duration.ZERO
    }

    @Test
    fun `a fast clock is forgotten once its batches leave the window (M11)`() {
        apply(sessionFrame())
        batchFromTablet(-600_000, seq = 0) // ten minutes fast
        repeat(CarLive.CLOCK_BATCHES - 1) { batchFromTablet(0, seq = 10L + it * 2) }
        car.status().clockOffset shouldBe Duration.ofMinutes(-10) // still in the window
        batchFromTablet(0, seq = 1000)
        car.status().clockOffset shouldBe Duration.ZERO
    }

    @Test
    fun `history keeps five minutes, by the server's clock`() {
        apply(sessionFrame())
        apply(batch(sample("engine.rpm", 1.0, 1)))
        clock.advance(Duration.ofMinutes(5))
        apply(batch(sample("engine.rpm", 2.0, 2)))
        car.snapshot().history shouldHaveSize 2 // exactly five minutes old: kept
        clock.advanceMillis(1)
        car.snapshot().history shouldHaveSize 1
    }

    @Test
    fun `history never exceeds its cap`() {
        apply(sessionFrame())
        repeat(1_200) { apply(batch(sample("engine.rpm", it.toDouble(), it.toLong()))) }
        val history = car.snapshot().history
        history shouldHaveSize 1_000
        history.last().record["value"].toString() shouldBe "1199.0"
    }

    @Test
    fun `freshness moves through its states at exactly two seconds`() {
        car.status().freshness(clock.now) shouldBe Freshness.Offline
        car.connected()
        car.status().freshness(clock.now) shouldBe Freshness.NoSession
        apply(sessionFrame())
        car.status().freshness(clock.now) shouldBe Freshness.Stale(null)
        apply(batch(sample("engine.rpm", 1.0, 1)))
        clock.advance(Duration.ofSeconds(2))
        car.status().freshness(clock.now) shouldBe Freshness.Live
        clock.advanceMillis(1)
        car.status().freshness(clock.now) shouldBe Freshness.Stale(2)
        clock.advance(Duration.ofSeconds(38))
        car.status().freshness(clock.now) shouldBe Freshness.Stale(40)
        apply("""{"t":"end","session":"$SESSION"}""")
        car.status().freshness(clock.now) shouldBe Freshness.NoSession
        car.snapshot().latest shouldHaveSize 1 // last values stay on screen after the end
        car.disconnected()
        car.status().freshness(clock.now) shouldBe Freshness.Offline
    }
}
