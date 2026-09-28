package com.obd2dashboard.backend.timing

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Consistency and the theoretical best (M16.2). */
class ConsistencyTest {
    @Test
    fun `the laps counted, best, median, spread and those within 1 percent, to the millisecond`() {
        Consistency.of(listOf(70.0, 71.0, 70.5, 75.0)) shouldBe Consistency(4, 70.0, 70.75, 1.98, 2) // mean 71.625; 70.7 is 1% over
        Consistency.of(listOf(70.0, 72.0, 71.0)) shouldBe Consistency(3, 70.0, 71.0, 0.816, 1)
        Consistency.of(listOf(69.123)) shouldBe Consistency(1, 69.123, 69.123, 0.0, 1)
        Consistency.of(emptyList()) shouldBe null
        // Within 1%: 70.7 is, 70.701 isn't.
        Consistency.of(listOf(70.0, 70.7, 70.701))!!.withinOnePercent shouldBe 2
    }

    @Test
    fun `the theoretical best only when every sector has a best`() {
        Consistency.theoreticalBest(listOf(17.5, 17.4, 17.6, 17.25)) shouldBe 69.75
        Consistency.theoreticalBest(listOf(17.5, null)) shouldBe null
        Consistency.theoreticalBest(emptyList()) shouldBe null
    }
}
