package com.obd2dashboard.backend.timing

import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * How consistent a run of laps was (M16.2): over laps on track only, the
 * count, the best, the median, the spread (the standard deviation), and how
 * many were within 1% of the best; seconds, to the millisecond.
 */
@Serializable
public data class Consistency(val laps: Int, val best: Double, val median: Double, val spread: Double, val withinOnePercent: Int) {
    public companion object {
        /** Of lap [times] in seconds; null for none. */
        public fun of(times: List<Double>): Consistency? {
            if (times.isEmpty()) return null
            val sorted = times.sorted()
            val n = sorted.size
            val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
            val mean = sorted.average()
            val spread = sqrt(sorted.sumOf { (it - mean) * (it - mean) } / n)
            val best = sorted.first()
            return Consistency(n, ms(best), ms(median), ms(spread), sorted.count { it <= best * 1.01 })
        }

        /** The sum of the best of each sector, if every sector has one (M16.2). */
        public fun theoreticalBest(bestSectors: List<Double?>): Double? =
            if (bestSectors.isEmpty() || bestSectors.any { it == null }) null else ms(bestSectors.sumOf { it!! })

        private fun ms(seconds: Double): Double = (seconds * 1000).roundToLong() / 1000.0
    }
}
