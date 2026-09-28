package com.obd2dashboard.backend.events

import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What makes a driver or an event valid, and **which sessions each part
 * holds** (M14.1). Every problem is said, not just the first, as the course
 * rules do.
 */
public object EventRules {
    public const val MAX_NAME: Int = 60

    private val ID = Regex("[a-z][a-z0-9-]{1,31}")
    private val CODE = Regex("[A-Z]{2,4}")

    public fun idProblem(raw: String): String? =
        if (ID.matches(raw)) null else "an id is 2–32 lower-case letters, digits and hyphens, starting with a letter"

    public fun nameProblem(raw: String): String? = when {
        raw.isBlank() -> "a name cannot be blank"
        raw.trim().length > MAX_NAME -> "a name has at most $MAX_NAME characters"
        else -> null
    }

    public fun codeProblem(raw: String): String? =
        if (CODE.matches(raw)) null else "a driver's code is 2–4 capital letters"

    /** Every problem with [driver], [others] being every other driver. */
    public fun driverProblems(driver: Driver, others: Collection<Driver>): List<String> = listOfNotNull(
        nameProblem(driver.name),
        codeProblem(driver.code),
        others.firstOrNull { it.id != driver.id && it.code == driver.code }?.let { "${it.name} already has the code ${driver.code}" },
    )

    /** Every problem with [event] as drawn; whether its course, layout and cars exist is the caller's to say. */
    public fun eventProblems(event: Event): List<String> = buildList {
        idProblem(event.id)?.let(::add)
        nameProblem(event.name)?.let(::add)
        try { LocalDate.parse(event.date) } catch (_: DateTimeParseException) { add("a date is written 2026-10-04") }
        if (event.course.isBlank() || event.layout.isBlank()) add("an event is at a course, on one of its layouts")
        if (event.cars.isEmpty()) add("an event has at least one car")
        if (event.cars.toSet().size != event.cars.size) add("a car is entered once")
        if (event.parts.count { it.kind == PartKind.RACE } > 1) add("an event has at most one race")
        if (event.parts.map { it.id }.toSet().size != event.parts.size) add("each part has its own id")
        for (part in event.parts) {
            nameProblem(part.name)?.let { add("${part.name.ifBlank { part.id }}: $it") }
            if (!part.end.isAfter(part.start)) add("${part.name}: it ends after it starts")
            else if (Duration.between(part.start, part.end) > MAX_PART) add("${part.name}: a part is at most ${MAX_PART.toHours()} hours")
            if (part.added.any { it in part.removed }) add("${part.name}: a session is added or removed, not both")
            if (part.kind != PartKind.RACE && (part.green != null || part.flag != null || part.stints.isNotEmpty())) {
                add("${part.name}: only the race has flags and stints")
            }
            if (part.green != null && part.flag != null && !part.flag.isAfter(part.green)) add("${part.name}: the flag falls after the green flag")
            for ((car, stints) in part.stints) {
                if (car !in event.cars) add("${part.name}: stints for $car, which isn't entered")
                if (stints.map { it.start }.toSet().size != stints.size) add("${part.name}: two of $car's stints start at once")
            }
        }
        val sorted = event.parts.sortedBy { it.start }
        sorted.zipWithNext().filter { (a, b) -> b.start.isBefore(a.end) }.forEach { (a, b) -> add("${a.name} and ${b.name} overlap") }
        val added = event.parts.flatMap { p -> p.added.map { it to p } }.groupBy({ it.first }, { it.second })
        added.filter { it.value.size > 1 }.forEach { (id, parts) -> add("the session $id is added to ${parts.joinToString(" and ") { it.name }}; a session is in one part") }
    }

    /** A day and a night: longer than any endurance race we'd run, short of a mistyped year. */
    public val MAX_PART: Duration = Duration.ofHours(30)

    /**
     * **Which sessions each part holds**, by part id, in the order heard: a car
     * entered in the event, the server hearing it during the part's window
     * (`[start, end)`), never fake data; the part it overlaps most if two; plus
     * sessions added by hand (there only), less those removed by hand.
     */
    public fun sessionsIn(event: Event, sessions: Collection<SessionHeard>): Map<String, List<String>> {
        val eligible = sessions.filter { it.car in event.cars && it.source != FAKE }
        val byHand = event.parts.flatMap { p -> p.added.map { it to p.id } }.toMap()
        val chosen = eligible.mapNotNull { s ->
            val part = byHand[s.id]
                ?: event.parts
                    .filter { p -> s.id !in p.removed && s.from.isBefore(p.end) && !s.to.isBefore(p.start) }
                    .maxWithOrNull(compareBy<Part> { overlap(it, s) }.thenByDescending { it.start })
                    ?.id
            part?.let { s to it }
        }
        return event.parts.associate { p -> p.id to chosen.filter { it.second == p.id }.map { it.first }.sortedBy { it.from }.map { it.id } }
    }

    private fun overlap(part: Part, s: SessionHeard): Long =
        Duration.between(maxOf(part.start, s.from), minOf(part.end, s.to)).toMillis()

    private const val FAKE = "fake"
}
