package com.obd2dashboard.backend.archive

import java.io.OutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **A thinned series** (M19.4): a session's records in front of a
 * [SeriesBuilder], so a long one's "whole session" costs kilobytes, not
 * megabytes, and can be built as it grows. Each number keeps its **minimum and
 * maximum per [bucketMs]** (the records themselves, in time order, so peaks
 * survive); a position every [positionMs]; states, flag sets, laps, faults,
 * gaps and stopped signals whole (the builder already keeps states' changes
 * only). **Every record's `seq` still counts**, so the series' `lastSeq`
 * reaches the newest line and the page merges the live lane after it (§7).
 */
public class ThinSeries(
    private val builder: SeriesBuilder = SeriesBuilder(),
    private val bucketMs: Long = 5_000,
    private val positionMs: Long = 1_000,
) {
    private class Bucket(val start: Long, var min: JsonObject, var minV: Double, var max: JsonObject, var maxV: Double)

    private val open = HashMap<String, Bucket>()
    private val lastPosition = HashMap<String, Long>()
    private var newest = Long.MIN_VALUE

    public fun record(record: JsonObject) {
        // The seq alone: counted for `lastSeq`, placed nowhere (a record without `wall`).
        (record["seq"] as? JsonPrimitive)?.longOrNull?.let { builder.record(JsonObject(mapOf("seq" to JsonPrimitive(it)))) }
        val wall = (record["wall"] as? JsonPrimitive)?.longOrNull ?: return
        newest = maxOf(newest, wall)
        if (record.str("type") != "sample") return builder.record(record)
        val signal = record.str("signal") ?: return
        val v = numberOf(record)
        when {
            v != null -> {
                val start = wall - Math.floorMod(wall, bucketMs)
                val b = open[signal]
                if (b != null && b.start == start) {
                    if (v < b.minV) { b.min = record; b.minV = v }
                    if (v > b.maxV) { b.max = record; b.maxV = v }
                } else {
                    b?.let(::emit)
                    open[signal] = Bucket(start, record, v, record, v)
                }
            }
            record["lat"] != null -> {
                val last = lastPosition[signal]
                if (last == null || wall - last >= positionMs) {
                    lastPosition[signal] = wall
                    builder.record(record)
                }
            }
            else -> builder.record(record)
        }
    }

    /**
     * The series so far, as `SeriesBuilder.write`: buckets that ended before
     * the newest record's bucket are placed; the latest is left open (it may
     * still grow), unless [final].
     */
    public fun write(out: OutputStream, t0: Long, signals: List<SignalInfo>, final: Boolean = false) {
        val current = newest - Math.floorMod(newest, bucketMs)
        open.entries.removeIf { (_, b) -> (final || b.start < current).also { if (it) emit(b) } }
        builder.write(out, t0, signals)
    }

    /** The bucket's minimum and maximum; the builder puts each signal's points in time order itself. */
    private fun emit(b: Bucket) {
        builder.record(b.min)
        if (b.max !== b.min) builder.record(b.max)
    }

    private fun numberOf(record: JsonObject): Double? {
        (record["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { return it }
        return (record["flag"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { if (it) 1.0 else 0.0 }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
