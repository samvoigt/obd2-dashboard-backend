package com.obd2dashboard.backend.live

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveHubTest {
    private val clock = MutableClock()
    private val hub = InMemoryLiveHub(clock, subscriberBuffer = 8)

    private class FakeTablet : TabletHandle {
        var superseded = 0
        val closes = mutableListOf<Short>()
        override fun superseded() { superseded++ }
        override fun close(code: Short, reason: String) { closes += code }
    }

    private fun events(list: List<BrowserEvent>) = list.map {
        when (it) {
            is BrowserEvent.Snapshot -> "snapshot(${it.snapshot.latest.size})"
            is BrowserEvent.Update -> when (val u = it.update) {
                is LiveUpdate.SessionStarted -> "session"
                is LiveUpdate.Records -> "records(${u.records.size})"
                is LiveUpdate.Status -> "status(${u.status.freshness(clock.now).wire})"
            }
        }
    }

    @Test
    fun `a new socket supersedes the old, which then changes nothing`() = runTest {
        val first = FakeTablet()
        val a = hub.attach("yaris", first)
        a.apply(parse(sessionFrame()))
        val b = hub.attach("yaris", FakeTablet())

        first.superseded shouldBe 1
        a.apply(parse(batch(sample("engine.rpm", 1.0, 1)))).shouldBeInstanceOf<CarLive.Applied.Refused>()
        a.detach() // a superseded socket closing must not mark the car offline
        hub.status("yaris").connected shouldBe true
        b.apply(parse(batch(sample("engine.rpm", 2.0, 2)))).shouldBeInstanceOf<CarLive.Applied.Ok>()
    }

    @Test
    fun `a browser joining mid-session gets the snapshot, then updates in order`() = runTest(UnconfinedTestDispatcher()) {
        val tablet = hub.attach("yaris", FakeTablet())
        tablet.apply(parse(sessionFrame()))
        tablet.apply(parse(batch(sample("engine.rpm", 1.0, 1), sample("vehicle.speed", 3.0, 2))))

        val seen = mutableListOf<BrowserEvent>()
        val job = launch { hub.subscribe("yaris").collect { seen += it } }
        tablet.apply(parse(batch(sample("engine.rpm", 2.0, 3))))
        tablet.detach()
        job.cancel()

        events(seen) shouldBe listOf("snapshot(2)", "records(1)", "status(live)", "status(offline)")
        seen.first().shouldBeInstanceOf<BrowserEvent.Snapshot>().snapshot.history shouldHaveSize 2
    }

    @Test
    fun `a stalled browser is resnapshotted while another keeps everything, and the tablet never waits`() = runTest(UnconfinedTestDispatcher()) {
        val tablet = hub.attach("yaris", FakeTablet())
        tablet.apply(parse(sessionFrame()))

        val keeping = mutableListOf<BrowserEvent>()
        val keeper = launch { hub.subscribe("yaris").collect { keeping += it } }
        // The stalled browser subscribes but never collects past its first event.
        val stalled = hub.subscribe("yaris")
        val stalledEvents = mutableListOf<BrowserEvent>()
        val gate = CompletableDeferred<Unit>()
        val staller = launch { stalled.collect { stalledEvents += it; gate.await() } }

        repeat(50) { tablet.apply(parse(batch(sample("engine.rpm", it.toDouble(), it.toLong())))) } // never suspends
        keeping.count { it is BrowserEvent.Update && it.update is LiveUpdate.Records } shouldBe 50

        gate.complete(Unit)
        tablet.detach()
        staller.cancel(); keeper.cancel()
        // It fell behind, so it was given fresh snapshots instead of every update: fewer
        // events than were sent, and a snapshot after its first event.
        (stalledEvents.size < 50) shouldBe true
        stalledEvents.drop(1).any { it is BrowserEvent.Snapshot } shouldBe true
        // What matters: its events, applied in order as a browser does, end at the latest value.
        lastRpm(stalledEvents) shouldBe "49.0"
        lastRpm(keeping) shouldBe "49.0"
    }

    /** Replays events as a browser does: a snapshot resets, records update. */
    private fun lastRpm(events: List<BrowserEvent>): String? {
        var value: String? = null
        for (e in events) when (e) {
            is BrowserEvent.Snapshot -> value = e.snapshot.latest.firstOrNull { it.string("signal") == "engine.rpm" }?.get("value")?.toString()
            is BrowserEvent.Update -> (e.update as? LiveUpdate.Records)?.records
                ?.lastOrNull { it.string("signal") == "engine.rpm" }?.let { value = it["value"].toString() }
        }
        return value
    }

    @Test
    fun `status follows the tablet through connect, session, silence and disconnect`() = runTest {
        hub.status("yaris").freshness(clock.now) shouldBe Freshness.Offline
        val tablet = hub.attach("yaris", FakeTablet())
        hub.status("yaris").freshness(clock.now) shouldBe Freshness.NoSession
        tablet.apply(parse(sessionFrame()))
        tablet.apply(parse(batch(sample("engine.rpm", 1.0, 1))))
        hub.status("yaris").freshness(clock.now) shouldBe Freshness.Live
        clock.advanceMillis(40_000)
        hub.status("yaris").freshness(clock.now) shouldBe Freshness.Stale(40)
        tablet.detach()
        hub.status("yaris").freshness(clock.now) shouldBe Freshness.Offline
    }

    @Test
    fun `two cars never see each other's data`() = runTest(UnconfinedTestDispatcher()) {
        val yaris = hub.attach("yaris", FakeTablet())
        val outback = hub.attach("outback", FakeTablet())
        yaris.apply(parse(sessionFrame()))
        outback.apply(parse(sessionFrame(id = OTHER_SESSION)))
        yaris.apply(parse(batch(sample("engine.rpm", 1.0, 1))))

        val first = hub.subscribe("outback").first().shouldBeInstanceOf<BrowserEvent.Snapshot>()
        first.snapshot.latest shouldBe emptyList()
        first.snapshot.header!!.string("id") shouldBe OTHER_SESSION
    }

    @Test
    fun `closeAll closes every tablet with the code`() = runTest {
        val a = FakeTablet(); val b = FakeTablet()
        hub.attach("yaris", a); hub.attach("outback", b)
        hub.closeAll(1012, "restarting")
        a.closes shouldBe listOf<Short>(1012)
        b.closes shouldBe listOf<Short>(1012)
    }

    @Test
    fun `a browser that stops collecting is removed`() = runTest(UnconfinedTestDispatcher()) {
        val tablet = hub.attach("yaris", FakeTablet())
        tablet.apply(parse(sessionFrame()))
        hub.subscribe("yaris").first() // collects one event, then stops
        hub.subscriberCount("yaris") shouldBe 0

        val job = launch { hub.subscribe("yaris").collect { } }
        hub.subscriberCount("yaris") shouldBe 1
        job.cancel()
        hub.subscriberCount("yaris") shouldBe 0
        repeat(100) { tablet.apply(parse(batch(sample("engine.rpm", it.toDouble(), it.toLong())))) }
    }
}
