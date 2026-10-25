package com.obd2dashboard.backend.archive

import java.io.OutputStream
import java.io.Writer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A session prepared for its page (M7.2): every signal as columns, so the page
 * never parses the raw log. Derived, never the record: it can always be rebuilt
 * from the log.
 *
 * **Gaps are drawn as gaps**: at each break the numbers carry an explicit
 * `null`, which is the one thing that breaks a chart's line (uPlot bridges the
 * holes its joining makes). A break falls where two samples of a signal are
 * more than [BREAK_FACTOR] times its median interval apart (never under
 * [MIN_BREAK_MS]), or where a `gap` record lies between them **by `seq`**: the
 * readings it counts were missed from its `seq` on (§3.5), which `wall` can't
 * place when it shares a millisecond with a sample.
 *
 * **A fix's readings go with its position** (M30.1): `gps.speed`,
 * `gps.heading` and the rest arrive in the same burst as their fix's
 * `gps.position`, sharing its `at`, so each is placed when the receiver took
 * the fix, as the position is (M28). And everything from the receiver is
 * placed [gpsLatencyMs] earlier still (M30.2): what someone measured it runs
 * behind, zero unless they did.
 *
 * Holds only plain arrays, and writes its JSON straight to a stream.
 */
public class SeriesBuilder(private val gpsLatencyMs: Long = 0) {
    private class Numbers {
        val t = LongList()
        val v = DoubleList()
        val seq = LongList()

        /** Indexes in time order, or null if they already are (a corrected clock can step back). */
        fun order(): IntArray? {
            if ((1 until t.size).all { t[it - 1] <= t[it] }) return null
            return (0 until t.size).sortedBy { t[it] }.toIntArray()
        }
    }

    private val numbers = LinkedHashMap<String, Numbers>()
    private val states = LinkedHashMap<String, MutableList<Triple<Long, Long?, String?>>>()
    private val sets = LinkedHashMap<String, MutableList<Pair<Long, List<String>>>>()
    private val positions = Triple(LongList(), DoubleList(), DoubleList())
    /** Positions no car could have reached are left out (M28). */
    private val fixes = FixFilter()
    private val stopped = mutableListOf<Triple<Long, String, String>>()
    private val faults = mutableListOf<Pair<Long, List<String>>>()
    private val gaps = mutableListOf<Pair<Long, Long>>()
    private val gapSeqs = LongList()
    private val laps = mutableListOf<Pair<Long, JsonObject>>()
    private var lastSeq: Long? = null
    /**
     * The latest fixes' `at`, each with how long before it the receiver took the fix. Several, not one: a thinned
     * series (M19.4) hands a bucket's readings on when the bucket closes, after newer fixes.
     */
    private val fixDelays = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > FIXES_HELD
    }

    /**
     * Notes a `gps.position` sample's fix (M30.1) without placing it: a thinned series leaves most positions out, but
     * the readings of their fixes are still placed by them. Anything else is ignored.
     */
    public fun fix(record: JsonObject) {
        if (record.string("type") != "sample" || record.string("signal") != "gps.position") return
        val at = record.long("at") ?: return
        val fixAt = record.long("fixAt")?.takeIf { it <= at } ?: return
        fixDelays[at] = at - fixAt
    }

    /** One parsed record; one without `wall` can't be placed in time and is skipped. */
    public fun record(record: JsonObject) {
        // How far the file reaches, for merging with the live lane by seq (§7, M7.6).
        record.long("seq")?.let { seq -> lastSeq = maxOf(lastSeq ?: seq, seq) }
        val wall = record.long("wall") ?: return
        when (record.string("type")) {
            "sample" -> sample(record, wall)
            "stopped" -> record.string("signal")?.let { stopped += Triple(wall, it, record.string("reason").orEmpty()) }
            "fault" -> faults += wall to strings(record["codes"])
            "gap" -> {
                gaps += wall to (record.long("missed") ?: 0)
                record.long("seq")?.let { gapSeqs.add(it) }
            }
            "lap" -> laps += wall to record
        }
    }

    private fun sample(record: JsonObject, wall: Long) {
        val signal = record.string("signal") ?: return
        val value = record["value"] as? JsonPrimitive
        val flag = record["flag"] as? JsonPrimitive
        fix(record)
        val placed = if (signal.startsWith(GPS)) wall - (record.long("at")?.let { fixDelays[it] } ?: 0) - gpsLatencyMs else wall
        when {
            value != null && !value.isString -> value.doubleOrNull?.let { number(signal, placed, record.long("seq"), it) }
            flag != null && !flag.isString -> flag.booleanOrNull?.let { number(signal, placed, record.long("seq"), if (it) 1.0 else 0.0) }
            record["lat"] != null -> {
                val lat = (record["lat"] as? JsonPrimitive)?.doubleOrNull ?: return
                val lon = (record["lon"] as? JsonPrimitive)?.doubleOrNull ?: return
                for (f in fixes.offer(FixFilter.placed(record, wall, lat, lon))) {
                    positions.first.add(f.t - gpsLatencyMs); positions.second.add(f.lat); positions.third.add(f.lon)
                }
            }
            record["flags"] is JsonArray -> {
                val now = strings(record["flags"])
                val changes = sets.getOrPut(signal) { mutableListOf() }
                if (changes.lastOrNull()?.second != now) changes += wall to now
            }
            record["code"] != null || record["text"] != null -> {
                val code = record.long("code")
                val text = record.string("text")
                val changes = states.getOrPut(signal) { mutableListOf() }
                val last = changes.lastOrNull()
                if (last == null || last.second != code || last.third != text) changes += Triple(wall, code, text)
            }
        }
    }

    private fun number(signal: String, wall: Long, seq: Long?, value: Double) {
        val n = numbers.getOrPut(signal) { Numbers() }
        n.t.add(wall)
        n.v.add(value)
        n.seq.add(seq ?: -1)
    }

    /** The prepared session as gzip-ready JSON, times as milliseconds after [t0]. */
    public fun write(out: OutputStream, t0: Long, signals: List<SignalInfo>) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        val gapSeqs = LongArray(gapSeqs.size) { gapSeqs[it] }.apply { sort() }
        w.write("""{"version":$VERSION,"t0":$t0,"lastSeq":${lastSeq ?: "null"},"signals":[""")
        signals.forEachIndexed { i, s ->
            if (i > 0) w.write(",")
            w.write("""{"name":${str(s.name)},"unit":${str(s.unit)},"kind":${str(s.kind)}}""")
        }
        w.write("""],"numbers":{""")
        numbers.entries.forEachIndexed { i, (name, n) ->
            if (i > 0) w.write(",")
            w.write("${str(name)}:")
            writeNumbers(w, n, t0, gapSeqs)
        }
        w.write("""},"states":{""")
        states.entries.forEachIndexed { i, (name, changes) ->
            if (i > 0) w.write(",")
            w.write("${str(name)}:[")
            changes.forEachIndexed { j, (t, code, text) ->
                if (j > 0) w.write(",")
                w.write("[${t - t0},${code ?: "null"},${text?.let { str(it) } ?: "null"}]")
            }
            w.write("]")
        }
        w.write("""},"sets":{""")
        sets.entries.forEachIndexed { i, (name, changes) ->
            if (i > 0) w.write(",")
            w.write("${str(name)}:[")
            changes.forEachIndexed { j, (t, flags) ->
                if (j > 0) w.write(",")
                w.write("[${t - t0},[${flags.joinToString(",") { str(it) }}]]")
            }
            w.write("]")
        }
        // In time order: a fix's moment on `wall` steps back with a corrected clock (M11), though its `fixAt` never does.
        val order = positions.first.let { t -> if ((1 until t.size).all { t[it - 1] <= t[it] }) null else (0 until t.size).sortedBy { t[it] } }
        fun at(k: Int) = order?.get(k) ?: k
        w.write("""},"positions":{"t":[""")
        val n = positions.first.size
        for (k in 0 until n) { if (k > 0) w.write(","); w.write((positions.first[at(k)] - t0).toString()) }
        w.write("""],"lat":[""")
        for (k in 0 until n) { if (k > 0) w.write(","); w.write(number(positions.second[at(k)])) }
        w.write("""],"lon":[""")
        for (k in 0 until n) { if (k > 0) w.write(","); w.write(number(positions.third[at(k)])) }
        w.write("""],"dropped":${fixes.dropped + fixes.held}},"events":{"stopped":[""")
        w.write(stopped.joinToString(",") { (t, signal, reason) -> "[${t - t0},${str(signal)},${str(reason)}]" })
        w.write("""],"fault":[""")
        w.write(faults.joinToString(",") { (t, codes) -> "[${t - t0},[${codes.joinToString(",") { str(it) }}]]" })
        w.write("""],"gap":[""")
        w.write(gaps.joinToString(",") { (t, missed) -> "[${t - t0},$missed]" })
        w.write("""],"lap":[""")
        w.write(laps.joinToString(",") { (t, lap) -> "[${t - t0},$lap]" })
        w.write("]}}")
        w.flush()
    }

    /** A signal's `t` and `v`, sorted by time, with a `null` value at each break. Streams; boxes nothing. */
    private fun writeNumbers(w: Writer, n: Numbers, t0: Long, gapSeqs: LongArray) {
        val order = n.order() // null when already in time order, as it nearly always is
        fun at(k: Int) = order?.get(k) ?: k
        val threshold = breakThreshold(LongArray(n.t.size) { n.t[at(it)] })
        // Two passes, t then v, finding the same breaks: no intermediate text.
        w.write("""{"t":[""")
        for (k in 0 until n.t.size) {
            val time = n.t[at(k)]
            if (k > 0) {
                val previous = n.t[at(k - 1)]
                if (breaks(n, at(k - 1), at(k), threshold, gapSeqs)) w.write("${previous + 1 - t0},")
            }
            w.write((time - t0).toString())
            if (k < n.t.size - 1) w.write(",")
        }
        w.write("""],"v":[""")
        for (k in 0 until n.t.size) {
            if (k > 0 && breaks(n, at(k - 1), at(k), threshold, gapSeqs)) w.write("null,")
            w.write(number(n.v[at(k)]))
            if (k < n.t.size - 1) w.write(",")
        }
        w.write("]}")
    }

    /** Whether the line breaks between samples [a] and [b] (indexes, in time order). */
    private fun breaks(n: Numbers, a: Int, b: Int, threshold: Long?, gapSeqs: LongArray): Boolean {
        if (threshold != null && n.t[b] - n.t[a] > threshold) return true
        val from = n.seq[a]
        val to = n.seq[b]
        if (from < 0 || to <= from) return false // no seq to place a gap by
        // A gap record whose seq lies strictly between the two samples'.
        val at = gapSeqs.binarySearch(from + 1).let { if (it < 0) -it - 1 else it }
        return at < gapSeqs.size && gapSeqs[at] < to
    }

    public companion object {
        /**
         * 2: `lastSeq` added (M7.6). 3: positions at the receiver's time, those no car could reach dropped (M28).
         * 4: a fix's other readings at its time too, and the receiver's measured latency taken out (M30).
         */
        public const val VERSION: Int = 4
        /** A reading from the GPS receiver, by its name's prefix. */
        private const val GPS = "gps."
        /** About five seconds of a 10 Hz receiver: longer than a thinned series' bucket. */
        private const val FIXES_HELD = 64
        public const val BREAK_FACTOR: Int = 5
        public const val MIN_BREAK_MS: Long = 1_000

        /** [BREAK_FACTOR] times the median interval, never under [MIN_BREAK_MS]; null with under two samples. */
        internal fun breakThreshold(times: LongArray): Long? {
            if (times.size < 2) return null
            val intervals = LongArray(times.size - 1) { times[it + 1] - times[it] }.apply { sort() }
            return maxOf(intervals[intervals.size / 2] * BREAK_FACTOR, MIN_BREAK_MS)
        }

        private fun str(s: String): String = JsonPrimitive(s).toString()

        /** JSON has no NaN or infinity: those are written as a break. */
        private fun number(d: Double): String = if (d.isFinite()) d.toString() else "null"

        private fun strings(element: Any?): List<String> =
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    }
}

/** A growable array of longs, without boxing. */
internal class LongList {
    private var values = LongArray(16)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(i: Int): Long = values[i]

    fun clear() { size = 0 }
}

/** A growable array of doubles, without boxing. */
internal class DoubleList {
    private var values = DoubleArray(16)
    var size = 0
        private set

    fun add(value: Double) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(i: Int): Double = values[i]

    fun clear() { size = 0 }
}
