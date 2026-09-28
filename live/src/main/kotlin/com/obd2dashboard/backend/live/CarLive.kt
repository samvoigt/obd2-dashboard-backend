package com.obd2dashboard.backend.live

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A record and when this server received it: history is by the server's clock, which never jumps per tablet. */
public data class Stamped(val at: Instant, val record: JsonObject)

/** What a browser is told, in order: a snapshot first, then these. */
public sealed interface LiveUpdate {
    /** A new session: its record (no VIN) and signals list. The browser starts afresh. */
    public data class SessionStarted(val header: JsonObject, val signals: JsonArray?) : LiveUpdate

    /** Records as they arrived: samples, `signals`, `stopped`, `fault`, `gap`. */
    public data class Records(val at: Instant, val records: List<JsonObject>) : LiveUpdate

    public data class Status(val status: CarStatus) : LiveUpdate

    /** A crew message changed state (M5). **Crew browsers only**: the public stream drops it. */
    public data class MessageChanged(val message: Message) : LiveUpdate

    /** Where the car stands (M17.5), as the page shows it; null when there's nothing to say. Held for snapshots. */
    public data class Timing(val timing: JsonObject?) : LiveUpdate
}

/** Everything a browser needs to draw a car from nothing. */
public data class LiveSnapshot(
    val status: CarStatus,
    val header: JsonObject?,
    val signals: JsonArray?,
    val latest: List<JsonObject>,
    val stopped: List<JsonObject>,
    val fault: JsonObject?,
    val history: List<Stamped>,
    /** Where the car stands (M17.5), as last published (kept across sessions: it's replaced when it changes); null before. */
    val timing: JsonObject? = null,
)

/**
 * One car's live state (contract §5.2), held for browsers.
 *
 * **Provisional by nature** (§5.3, §7): it is what the live lane last said, not
 * the archive's record. It holds no VIN, since frames arrive stripped.
 */
public class CarLive(
    private val clock: Clock,
    private val historyWindow: Duration = Duration.ofMinutes(5),
    private val historyCap: Int = 100_000,
) {
    public var sessionId: String? = null
        private set
    private var header: JsonObject? = null
    private var signals: JsonArray? = null
    private val latest = LinkedHashMap<String, JsonObject>()
    private val stopped = LinkedHashMap<String, JsonObject>()
    private var fault: JsonObject? = null
    private val history = ArrayDeque<Stamped>()
    private var connected = false
    private var inSession = false
    private var lastDataAt: Instant? = null
    private val offsets = ArrayDeque<Long>()
    private var clockOffset: Duration? = null
    private var timing: JsonObject? = null

    public fun status(): CarStatus = CarStatus(connected, inSession, lastDataAt, sessionId.takeIf { inSession }, clockOffset)

    public fun connected(): LiveUpdate.Status {
        connected = true
        return LiveUpdate.Status(status())
    }

    public fun disconnected(): LiveUpdate.Status {
        connected = false
        return LiveUpdate.Status(status())
    }

    /** Applies a frame and returns what browsers must be told; a frame for another session is refused. */
    public fun apply(frame: TabletFrame): Applied {
        val now = clock.instant()
        return when (frame) {
            is TabletFrame.Session -> {
                val updates = mutableListOf<LiveUpdate>()
                if (frame.id != sessionId) {
                    // A new session starts afresh; the same one re-sent after a reconnect keeps its state.
                    sessionId = frame.id
                    latest.clear(); stopped.clear(); fault = null; history.clear()
                    signals = frame.record["signals"] as? JsonArray
                }
                header = frame.record
                inSession = true
                updates += LiveUpdate.SessionStarted(frame.record, signals)
                updates += LiveUpdate.Status(status())
                Applied.Ok(updates)
            }
            is TabletFrame.Snapshot -> {
                if (frame.session != sessionId) return Applied.Refused("a snapshot for a session not announced")
                latest.clear(); stopped.clear(); fault = null
                frame.records.forEach { absorb(it) }
                record(now, frame.records)
                Applied.Ok(listOf(LiveUpdate.Records(now, frame.records), LiveUpdate.Status(status())))
            }
            is TabletFrame.Batch -> {
                if (frame.session != sessionId) return Applied.Refused("a batch for a session not announced")
                frame.records.forEach { absorb(it) }
                record(now, frame.records)
                noteClock(now, frame.records)
                Applied.Ok(listOf(LiveUpdate.Records(now, frame.records), LiveUpdate.Status(status())))
            }
            is TabletFrame.End -> {
                if (frame.session != sessionId) return Applied.Refused("end for a session not announced")
                inSession = false
                Applied.Ok(listOf(LiveUpdate.Status(status())))
            }
            is TabletFrame.Hello, is TabletFrame.Received, is TabletFrame.Displayed, is TabletFrame.Unknown -> Applied.Ok(emptyList())
        }
    }

    public fun snapshot(): LiveSnapshot {
        trim(clock.instant())
        return LiveSnapshot(
            status = status(),
            header = header,
            signals = signals,
            latest = latest.values.toList(),
            stopped = stopped.values.toList(),
            fault = fault,
            history = history.toList(),
            timing = timing,
        )
    }

    /** Holds where the car stands (M17.5), for browsers that come later. */
    public fun timing(json: JsonObject?): LiveUpdate.Timing {
        timing = json
        return LiveUpdate.Timing(json)
    }

    private fun absorb(record: JsonObject) {
        when (record.string("type")) {
            "sample" -> record.string("signal")?.let { latest[it] = record }
            "stopped" -> record.string("signal")?.let { stopped[it] = record }
            "fault" -> fault = record
            "signals" -> signals = record["signals"] as? JsonArray ?: signals
        }
    }

    private fun record(now: Instant, records: List<JsonObject>) {
        lastDataAt = now
        records.forEach { history.addLast(Stamped(now, it)) }
        trim(now)
    }

    /**
     * The tablet's clock against ours (M11): our time a batch arrived minus its
     * newest `wall`. The network only ever adds to that, so the smallest over the
     * last [CLOCK_BATCHES] is the truest: a batch held back and sent late doesn't
     * move it, and a corrected clock shows at once. Snapshots don't count: they
     * hold each signal's latest reading, however old.
     */
    private fun noteClock(now: Instant, records: List<JsonObject>) {
        val newest = records.mapNotNull { it.long("wall") }.maxOrNull() ?: return
        offsets.addLast(now.toEpochMilli() - newest)
        while (offsets.size > CLOCK_BATCHES) offsets.removeFirst()
        clockOffset = Duration.ofMillis(offsets.min())
    }

    private fun trim(now: Instant) {
        val oldest = now.minus(historyWindow)
        while (history.isNotEmpty() && history.first().at.isBefore(oldest)) history.removeFirst()
        while (history.size > historyCap) history.removeFirst()
    }

    public sealed interface Applied {
        public data class Ok(val updates: List<LiveUpdate>) : Applied
        public data class Refused(val reason: String) : Applied
    }

    public companion object {
        /** Ten seconds of batches at five a second (§5.2). */
        public const val CLOCK_BATCHES: Int = 50
    }
}

/**
 * Where a car stands, from the server's side. [freshness] turns it into what a
 * page shows; the page then counts the seconds itself.
 */
public data class CarStatus(
    val connected: Boolean,
    val inSession: Boolean,
    val lastDataAt: Instant?,
    /** The session being streamed, only while in one (the admin page, M6.7). Never sent to public streams. */
    val sessionId: String? = null,
    /**
     * How far the tablet's clock is behind ours (ahead if negative), from its
     * live batches (M11); null before any. Kept after a disconnect. The admin
     * page's alone: never sent to public streams.
     */
    val clockOffset: Duration? = null,
) {
    public fun freshness(now: Instant): Freshness = when {
        !connected -> Freshness.Offline
        !inSession -> Freshness.NoSession
        lastDataAt == null -> Freshness.Stale(null)
        Duration.between(lastDataAt, now) <= LIVE_WITHIN -> Freshness.Live
        else -> Freshness.Stale(Duration.between(lastDataAt, now).seconds)
    }

    public companion object {
        /** Batches come every 200 ms (§5.2); two seconds without one is visibly behind. */
        public val LIVE_WITHIN: Duration = Duration.ofSeconds(2)
    }
}

public sealed interface Freshness {
    public val wire: String

    public data object Live : Freshness { override val wire: String = "live" }
    public data class Stale(val seconds: Long?) : Freshness { override val wire: String = "stale" }
    public data object NoSession : Freshness { override val wire: String = "no_session" }
    public data object Offline : Freshness { override val wire: String = "offline" }
}
