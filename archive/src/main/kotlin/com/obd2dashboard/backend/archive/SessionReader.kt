package com.obd2dashboard.backend.archive

import java.io.InputStream
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A signal as a session announces it (§3.2, §3.3). */
public data class SignalInfo(val name: String, val unit: String, val kind: String)

/** A timed lap (§16), with the pit flags §18 added. */
public data class LapInfo(
    val track: String?,
    val layout: String?,
    val lap: Int,
    /** Seconds between two crossings of the line. */
    val time: Double,
    val pitIn: Boolean,
    val pitOut: Boolean,
    /** When it was published: `wall`, epoch milliseconds. */
    val wall: Long?,
) {
    /** A lap on track, not into or out of the pits: the only kind that can be the best (§18). */
    val onTrack: Boolean get() = !pitIn && !pitOut
}

/**
 * What the sessions list shows about a session, built once from its lines
 * (M7.1). **Never the VIN**: nothing here can hold it.
 */
public data class SessionSummary(
    /** Of the summary's own rules: an older one is rebuilt. */
    val version: Int,
    /** Epoch milliseconds: the first `wall`, else the header's `started`. */
    val started: Long,
    /** Epoch milliseconds: the latest `wall`. */
    val ended: Long,
    val lines: Long,
    val signals: List<SignalInfo>,
    /** The session record's `source` (§20, §21): `tablet`, `fake`, or null for a car's. Since version 2 (M11). */
    val source: String? = null,
    val track: String?,
    val layout: String?,
    val laps: Int,
    /** The fastest lap on track, never a pit lap (§18); null without one. */
    val bestLap: LapInfo?,
    /** Every trouble code seen, in the order first seen. */
    val faults: List<String>,
    /** `gap` records: where the tablet's own log lost readings (§3.5). */
    val gaps: Int,
    val missed: Long,
    /** Lines that aren't JSON objects. The archive takes none, so this should stay 0. */
    val unreadable: Int,
) {
    public companion object {
        /** 2 (M11): `source`, so a tablet's or a test session says so. */
        public const val VERSION: Int = 2
    }
}

/**
 * Reads a session one line at a time and builds its [SessionSummary] (M7.1).
 * Pure, so any source can feed it: the completed log, or segments in order.
 *
 * **Never fails a session** on a line it doesn't understand: unknown types and
 * fields are skipped (§3.1), and an unparsable line is only counted.
 */
public class SessionReader(
    /** Also given every parsed record, so one pass can build more than the summary (M7.2). */
    private val also: (JsonObject) -> Unit = {},
) {
    private var lines = 0L
    private var unreadable = 0
    private var firstWall: Long? = null
    private var lastWall: Long? = null
    private var headerStarted: Long? = null
    private var source: String? = null
    private val signals = LinkedHashMap<String, SignalInfo>()
    private val laps = mutableListOf<LapInfo>()
    private val faults = LinkedHashSet<String>()
    private var gaps = 0
    private var missed = 0L

    public fun line(bytes: ByteArray) {
        lines++
        val record = Records.parseObject(bytes) ?: run { unreadable++; return }
        also(record)
        record.long("wall")?.let { wall ->
            if (firstWall == null) firstWall = wall
            lastWall = maxOf(lastWall ?: wall, wall)
        }
        when (record.string("type")) {
            "session" -> {
                headerStarted = record.string("started")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                source = record.string("source")
                announce(record)
            }
            "signals" -> announce(record)
            "fault" -> (record["codes"] as? JsonArray)?.forEach { code ->
                (code as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { faults += it }
            }
            "gap" -> {
                gaps++
                missed += record.long("missed") ?: 0
            }
            "lap" -> {
                val lap = record.int("lap")
                val time = (record["time"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (lap != null && time != null) {
                    laps += LapInfo(
                        track = record.string("track"),
                        layout = record.string("layout"),
                        lap = lap,
                        time = time,
                        pitIn = record.flag("pitIn"),
                        pitOut = record.flag("pitOut"),
                        wall = record.long("wall"),
                    )
                }
            }
        }
    }

    /** Every line of [input], split at `\n`. */
    public fun read(input: InputStream) {
        LineSplitter { line(it) }.feed(input)
    }

    public fun summary(): SessionSummary {
        val started = firstWall ?: headerStarted ?: 0
        val track = laps.groupingBy { it.track }.eachCount().maxByOrNull { it.value }?.key
        return SessionSummary(
            version = SessionSummary.VERSION,
            started = started,
            ended = lastWall ?: started,
            lines = lines,
            signals = signals.values.toList(),
            source = source,
            track = track,
            layout = laps.firstOrNull { it.track == track }?.layout,
            laps = laps.size,
            bestLap = laps.filter { it.onTrack }.minByOrNull { it.time },
            faults = faults.toList(),
            gaps = gaps,
            missed = missed,
            unreadable = unreadable,
        )
    }

    /** A `signals` list replaces the set going forward (§3.3); the summary keeps every signal the session had. */
    private fun announce(record: JsonObject) {
        (record["signals"] as? JsonArray)?.forEach { entry ->
            val signal = entry as? JsonObject ?: return@forEach
            val name = signal.string("name") ?: return@forEach
            signals[name] = SignalInfo(name, signal.string("unit").orEmpty(), signal.string("kind").orEmpty())
        }
    }
}

/** Splits a byte stream into lines at `\n`, without holding more than one line. */
public class LineSplitter(private val each: (ByteArray) -> Unit) {
    public fun feed(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        val line = java.io.ByteArrayOutputStream(1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            var start = 0
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    line.write(buffer, start, i - start)
                    each(line.toByteArray())
                    line.reset()
                    start = i + 1
                }
            }
            line.write(buffer, start, n - start)
        }
        if (line.size() > 0) each(line.toByteArray())
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
