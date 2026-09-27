package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.archive.LineSplitter
import com.obd2dashboard.backend.archive.Records
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A `lap` record as the tablet sent it (§16, §18, §22.6). */
public data class TabletLap(
    val course: String?,
    val courseVersion: Int?,
    /** The layout's `id` (its name before courses came from the website). */
    val layout: String?,
    val lap: Int,
    /** Seconds. */
    val time: Double,
    val sectors: List<Double>,
    /** Its two crossings on `fixAt`'s clock, milliseconds; absent before §22.6. */
    val startAt: Long?,
    val endAt: Long?,
    val pitIn: Boolean,
    val pitOut: Boolean,
    val seq: Long?,
    /** When the record was written, on `at`'s clock: about when the lap ended. */
    val at: Long? = null,
)

/** A session's fixes and laps, read from its log a line at a time (M13.2). */
public class SessionTrace {
    private val fixes = mutableListOf<Fix>()
    private val laps = mutableListOf<TabletLap>()
    private var last: Double? = null

    /** Fixes in order, each timed on its `fixAt`, else its `at`; any going back in time left out (§22.3). */
    public val allFixes: List<Fix> get() = fixes
    public val tabletLaps: List<TabletLap> get() = laps

    public fun line(bytes: ByteArray) {
        val record = Records.parseObject(bytes) ?: return
        when (record.str("type")) {
            "sample" -> if (record.str("signal") == "gps.position") fix(record)
            "lap" -> lap(record)
        }
    }

    public fun read(input: InputStream): SessionTrace = apply { LineSplitter { line(it) }.feed(input) }

    private fun fix(record: JsonObject) {
        val lon = record.num("lon") ?: return
        val lat = record.num("lat") ?: return
        val at = (record.lng("fixAt") ?: record.lng("at"))?.toDouble() ?: return
        // The tablet never sends a fix older than the last (§22.3); one that did would be timed backwards.
        if (last?.let { at < it } == true) return
        last = at
        fixes += Fix(lon, lat, at)
    }

    private fun lap(record: JsonObject) {
        val lap = (record["lap"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return
        val time = record.num("time") ?: return
        laps += TabletLap(
            course = record.str("course") ?: record.str("track"),
            courseVersion = (record["courseVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            layout = record.str("layout"),
            lap = lap,
            time = time,
            sectors = (record["sectors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }.orEmpty(),
            startAt = record.lng("startAt"),
            endAt = record.lng("endAt"),
            pitIn = record.bool("pitIn"),
            pitOut = record.bool("pitOut"),
            seq = record.lng("seq"),
            at = record.lng("at"),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
}

/** What a run needs to know of a session: from its summary (version 3). */
public data class RunPart(
    val id: String,
    val car: String,
    val device: String?,
    /** Epoch milliseconds. */
    val started: Long,
    val ended: Long,
    /** Its records' smallest and largest `at`. */
    val firstAt: Long?,
    val lastAt: Long?,
)

/**
 * Runs of the app (§22.8): one car's sessions from one device, back to back,
 * each one's first `at` above the last one's last (a smaller `at` means the app
 * restarted), none more than [maxGapMs] after the last. The tablet's timing
 * carries across a run's sessions, so re-timing does too. A session with no
 * device or no `at` stands alone.
 */
public fun runs(parts: List<RunPart>, maxGapMs: Long = 12 * 3_600_000L): List<List<RunPart>> {
    val out = mutableListOf<MutableList<RunPart>>()
    for (p in parts.sortedWith(compareBy({ it.car }, { it.started }))) {
        val run = out.lastOrNull()
        val prev = run?.last()
        val joins = prev != null && p.car == prev.car && p.device != null && p.device == prev.device &&
            p.firstAt != null && prev.lastAt != null && p.firstAt > prev.lastAt && p.started - prev.ended <= maxGapMs
        if (joins) run.add(p) else out += mutableListOf(p)
    }
    return out
}
