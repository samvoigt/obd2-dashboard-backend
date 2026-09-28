package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.timing.Provisional
import com.obd2dashboard.backend.timing.RunTiming
import com.obd2dashboard.backend.timing.StintMark
import java.util.concurrent.ConcurrentHashMap

/**
 * **Where a car stands, gathered** (M17.3): its event now (the part its
 * session is in, by the rule results use), the course, its complete runs on
 * it (held until they'd change) and its provisional ones, and who drove what;
 * [Standings.of] does the rest. Null while the car isn't in a session.
 */
class CarTimings(
    private val archive: ArchiveService,
    private val courses: CourseStore,
    private val events: EventStores,
    private val retiming: RetimingJobs,
    private val live: LiveTimings,
    private val hub: LiveHub,
) {
    /** A car's complete runs on a course, and what they were built from. */
    private data class Held(val key: List<Any>, val runs: List<RunTiming>)

    private val held = ConcurrentHashMap<String, Held>()

    /** The event [car]'s session [session] is in, and its part; null if none. */
    suspend fun eventOf(car: String, session: String): Pair<Event, Part>? {
        val heard = archive.sessionsOf(car).map { it.heard() }
        return events.events.list().filter { car in it.cars }.firstNotNullOfOrNull { e ->
            EventRules.sessionsIn(e, heard).entries.firstOrNull { session in it.value }?.let { (part, _) -> e to e.parts.first { it.id == part } }
        }
    }

    suspend fun standing(car: String): Standing? {
        val session = hub.status(car).sessionId ?: return null
        val records = archive.sessionsOf(car)
        val complete = records.filter { it.complete }.map { it.id }.toSet()
        val inEvent = eventOf(car, session)
        val event = inEvent?.first
        val partOf = event?.let { e -> EventRules.sessionsIn(e, records.map { it.heard() }) }.orEmpty()
        val eventSessions = partOf.values.flatten().toSet()
        // The course: the event's, else the one the car's latest live lap names.
        val course = event?.let { courses.get(it.course) }
            ?: live.read(car) { s -> s.flatMap { it.trace.tabletLaps }.maxByOrNull { it.endAt ?: it.at ?: 0 }?.course }?.let { courses.get(it) }
        val drivers = events.drivers.list().associateBy { it.id }
        val sessionDrivers = records.associate { it.id to it.driver?.let(drivers::get) }
        if (course == null) {
            return Standings.of(car, session, null, emptyList(), emptySet(), sessionDrivers, roster = drivers)
        }
        val scope = if (event != null) eventSessions else records.map { it.id }.toSet()
        val completeRuns = completeRuns(car, course, records.sortedBy { it.created }.map { it.id }.filter { it in scope && it in complete })
        val provisional = live.read(car) { s -> Provisional.runs(course, s.filter { it.id !in complete }) }
        val runs = completeRuns + provisional
        val layout = event?.layout ?: runs.lastOrNull { session in it.sessions }?.layout ?: runs.lastOrNull()?.layout
        val counted = if (event != null) eventSessions + session else runs.flatMap { it.sessions }.toSet()
        val part = inEvent?.second
        val race = part?.takeIf { it.kind == PartKind.RACE }?.let { p ->
            RaceNowInput(partOf[p.id].orEmpty().toSet() + session, p.stints[car]?.map { StintMark(it.start, it.driver) }, null, null)
        }
        return Standings.of(
            car, session, layout?.let { CourseAt(course.id, course.version, it) }, runs, counted, sessionDrivers, race, drivers,
        )
    }

    /** [car]'s complete runs on [course] among [sessions], in the order the server heard them; held until any of those changes. */
    private suspend fun completeRuns(car: String, course: Course, sessions: List<String>): List<RunTiming> {
        val key = listOf(course.id, course.version, sessions)
        held[car]?.takeIf { it.key == key }?.let { return it.runs }
        val runs = sessions.mapNotNull { retiming.lapsOf(car, it, course.id)?.second }.distinctBy { it.sessions }
        held[car] = Held(key, runs)
        return runs
    }
}
