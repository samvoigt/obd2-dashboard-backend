package com.obd2dashboard.backend.archive

/**
 * **A correction replaces, not adds** (M32, decision 48; Sam: "a stint shorter
 * than 60s or 1 lap is likely a correction"). A session's drivers are its
 * driver from its start and its changes; a change less than [WINDOW_MS] after
 * the entry before it, or with no lap ending between them, corrects that
 * entry instead of following it. The correction keeps the earlier moment (when
 * the swap happened) and takes the later driver; correcting the start sets the
 * session's driver. Pure.
 */
public object DriverRules {
    public const val WINDOW_MS: Long = 60_000

    /** A session's drivers after [normalise], and the entries corrected away: the start's as a change at the start. */
    public data class Normalised(val driver: String?, val changes: List<DriverChange>, val replaced: List<DriverChange>)

    /**
     * [driver] from [start] (the tablet's `wall`, milliseconds; null when not known yet, and then nothing corrects the
     * start), then [changes]. [lapEnds] are the session's laps' ends on `wall`; with none (a session never timed),
     * only the [WINDOW_MS] test applies, so its changes aren't all taken for corrections.
     */
    public fun normalise(start: Long?, driver: String?, changes: List<DriverChange>, lapEnds: List<Long>): Normalised {
        var first = driver
        val kept = mutableListOf<DriverChange>()
        val replaced = mutableListOf<DriverChange>()
        for (change in changes.sortedBy { it.from }) {
            val before = kept.lastOrNull()?.from ?: start
            val correction = before != null && (
                change.from - before < WINDOW_MS ||
                    (lapEnds.isNotEmpty() && lapEnds.none { it > before && it <= change.from })
                )
            when {
                !correction -> kept += change
                kept.isEmpty() -> { replaced += DriverChange(start!!, first); first = change.driver }
                else -> { replaced += kept.last(); kept[kept.lastIndex] = kept.last().copy(driver = change.driver) }
            }
        }
        return Normalised(first, kept, replaced)
    }
}
