package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import org.junit.Test

/** A correction replaces, not adds (M32): under 60 s, or no lap between, after the entry before. */
class DriverRulesTest {
    private val start = 1_000_000L
    /** Laps of 90 s from the start. */
    private val laps = (1..20).map { start + it * 90_000L }

    @Test
    fun `a change 30 s after another corrects it, keeping the first moment and taking the second driver`() {
        val n = DriverRules.normalise(start, "sam", listOf(DriverChange(start + 400_000, "drew"), DriverChange(start + 430_000, "paul")), laps)
        n.driver shouldBe "sam"
        n.changes shouldBe listOf(DriverChange(start + 400_000, "paul"))
        n.replaced shouldBe listOf(DriverChange(start + 400_000, "drew"))
    }

    @Test
    fun `five minutes apart with no lap between is a correction, with a lap between it isn't`() {
        // In the pits: no lap ends between 400 s and 700 s if the car sat still; here, laps end at 450 s, 540 s…
        val stopped = listOf(start + 90_000L, start + 900_000L) // one lap, then a long stop, then the next
        DriverRules.normalise(start, "sam", listOf(DriverChange(start + 200_000, "drew"), DriverChange(start + 500_000, "paul")), stopped)
            .changes shouldBe listOf(DriverChange(start + 200_000, "paul"))
        DriverRules.normalise(start, "sam", listOf(DriverChange(start + 200_000, "drew"), DriverChange(start + 500_000, "paul")), laps)
            .changes shouldBe listOf(DriverChange(start + 200_000, "drew"), DriverChange(start + 500_000, "paul"))
    }

    @Test
    fun `correcting the start sets the session's driver`() {
        val n = DriverRules.normalise(start, null, listOf(DriverChange(start + 30_000, "drew")), laps)
        n.driver shouldBe "drew"
        n.changes shouldBe emptyList()
        n.replaced shouldBe listOf(DriverChange(start, null))
        // Before the first lap ended, too, though over a minute in.
        DriverRules.normalise(start, "sam", listOf(DriverChange(start + 80_000, "drew")), laps).let { it.driver shouldBe "drew"; it.changes shouldBe emptyList() }
    }

    @Test
    fun `three in a row collapse to the last, at the first one's moment`() {
        val n = DriverRules.normalise(start, "sam", listOf(DriverChange(start + 400_000, "drew"), DriverChange(start + 420_000, "paul"), DriverChange(start + 440_000, "hugh")), laps)
        n.changes shouldBe listOf(DriverChange(start + 400_000, "hugh"))
        n.replaced shouldBe listOf(DriverChange(start + 400_000, "drew"), DriverChange(start + 400_000, "paul"))
    }

    @Test
    fun `already normal is unchanged and nothing is reported`() {
        val changes = listOf(DriverChange(start + 400_000, "drew"), DriverChange(start + 1_000_000, "paul"))
        DriverRules.normalise(start, "sam", changes, laps) shouldBe DriverRules.Normalised("sam", changes, emptyList())
        // Out of order in, in order out.
        DriverRules.normalise(start, "sam", changes.reversed(), laps).changes shouldBe changes
    }

    @Test
    fun `no start known corrects nothing at the start, and a session never timed uses only the minute`() {
        DriverRules.normalise(null, "sam", listOf(DriverChange(start + 10_000, "drew")), laps).let { it.driver shouldBe "sam"; it.changes.size shouldBe 1 }
        // No laps at all: two changes ten minutes apart both stand; thirty seconds apart, the second corrects.
        DriverRules.normalise(start, "sam", listOf(DriverChange(start + 600_000, "drew"), DriverChange(start + 1_200_000, "paul")), emptyList()).changes.size shouldBe 2
        DriverRules.normalise(start, "sam", listOf(DriverChange(start + 600_000, "drew"), DriverChange(start + 630_000, "paul")), emptyList())
            .changes shouldBe listOf(DriverChange(start + 600_000, "paul"))
    }
}
