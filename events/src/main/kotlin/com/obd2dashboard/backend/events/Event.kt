package com.obd2dashboard.backend.events

import java.time.Instant

/**
 * A driver (M14): one list for every event and car. [id] is the server's, so
 * the [code] (`SAM`, for tables) can change.
 */
public data class Driver(val id: String, val name: String, val code: String)

/** What kind of part of an event: sessions standing alone, or the race, tied together (M15). */
public enum class PartKind { PRACTICE, RACE }

/**
 * A part of an event: a practice or the race, over a window of the server's
 * time, `[start, end)`. Sessions join it by when the server heard them, and
 * by hand ([added], [removed]).
 */
public data class Part(
    /** `p1`, `p2`… in the order made, never reused. */
    val id: String,
    val kind: PartKind,
    val name: String,
    val start: Instant,
    val end: Instant,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    /** The race only (M15.3): when the green flag and the chequered flag fell, as entered; annotations, cutting nothing. */
    val green: Instant? = null,
    val flag: Instant? = null,
    /** The race only (M15.3): each car's stints as edited, by car; none means the default (split at every stop). */
    val stints: Map<String, List<Stint>> = emptyMap(),
)

/** A stint as edited (M15.3): from [start], on **the tablet's clock** (milliseconds), driven by [driver]. */
public data class Stint(val start: Long, val driver: String?)

/**
 * An event (M14): where (a course and layout), which of our cars, and its
 * parts. Every save is the next [revision], so two editors can't both win.
 */
public data class Event(
    /** Chosen like a course's id: `nhms-october`. */
    val id: String,
    val name: String,
    /** The day it's on, `2026-10-04`, as the admin gave it. */
    val date: String,
    val course: String,
    val layout: String,
    val cars: List<String>,
    val parts: List<Part>,
    val revision: Int = 0,
    val updated: Instant = Instant.EPOCH,
) {
    public val race: Part? get() = parts.firstOrNull { it.kind == PartKind.RACE }

    /** [parts] as drawn, with what only the race routes set kept from the stored parts of the same ids (M15.3). */
    public fun keepingRaceEdits(stored: Event?): Event = copy(parts = parts.map { p ->
        stored?.parts?.firstOrNull { it.id == p.id }?.let { old -> p.copy(green = old.green, flag = old.flag, stints = old.stints) } ?: p
    })

    /** The next part's id: one past the highest ever used here. */
    public fun nextPartId(): String = "p${(parts.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1}"
}

/** What joining a part needs of a session: its car, when the server heard it, and what it is (§20, §21). */
public data class SessionHeard(
    val id: String,
    val car: String,
    /** The server's time: first heard (`created`) and last heard (`updated`). */
    val from: Instant,
    val to: Instant,
    /** `tablet`, `fake` or null (a car's): fake data never joins. */
    val source: String?,
)
