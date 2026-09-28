package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.BrowserEvent
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.LiveUpdate
import io.kotest.matchers.shouldBe
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/** Where the car stands, on its page (M17.5). */
class TimingPageTest {
    private val s = Standing(
        "s1", CourseAt("nhms", 7, "road"), BestLap(94.532, listOf(31.298, 32.99, 30.244), "SAM", "s0", 7), listOf(31.298, 32.874, 30.101),
        DriverNow("Sam Voigt", "SAM", 1_000_000), RaceNow(58, 2_000_000),
    )

    @Test
    fun `moments on the server's clock, the theoretical best, nothing the page doesn't show`() {
        TimingPage.json(s, 500).toString() shouldBe
            """{"driver":{"name":"Sam Voigt","code":"SAM","since":1000500},"race":{"lap":58,"leftPits":2000500},""" +
            """"best":{"time":94.532,"driver":"SAM"},"bestSectors":[31.298,32.874,30.101],"theoretical":94.273}"""
        // No offset yet: no moments. In the pits: no leftPits. A sector missing: no theoretical best.
        TimingPage.json(s.copy(race = RaceNow(3, null), bestSectors = listOf(31.0, null)), null).toString() shouldBe
            """{"driver":{"name":"Sam Voigt","code":"SAM"},"race":{"lap":3},"best":{"time":94.532,"driver":"SAM"},"bestSectors":[31.0,null]}"""
    }

    @Test
    fun `published when it changes, and held for a browser that comes later`() = runTest {
        val hub = InMemoryLiveHub(Clock.systemUTC())
        val page = TimingPage(hub)
        val seen = mutableListOf<BrowserEvent>()
        val job = launch { hub.subscribe("outback").take(3).toList(seen) }
        yield()
        page.standing("outback", null) // nothing, and nothing shown: nothing sent
        page.standing("outback", s)
        page.standing("outback", s) // the same: nothing sent
        page.standing("outback", s.copy(race = RaceNow(59, 2_000_000)))
        job.join()
        seen.drop(1).map { ((it as BrowserEvent.Update).update as LiveUpdate.Timing).timing!!.toString().contains("\"lap\":") } shouldBe listOf(true, true)
        ((seen[2] as BrowserEvent.Update).update as LiveUpdate.Timing).timing.toString().contains("\"lap\":59") shouldBe true
        (hub.subscribe("outback").first() as BrowserEvent.Snapshot).snapshot.timing.toString().contains("\"lap\":59") shouldBe true
        encode(BrowserEvent.Update(LiveUpdate.Timing(null)), Clock.systemUTC())!!.let { (name, json) -> name shouldBe "timing"; json["timing"].toString() shouldBe "null" }
    }
}
