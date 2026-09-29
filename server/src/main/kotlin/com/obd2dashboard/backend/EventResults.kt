package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.CarRace
import com.obd2dashboard.backend.timing.Consistency
import com.obd2dashboard.backend.timing.Provisional
import com.obd2dashboard.backend.timing.Race
import com.obd2dashboard.backend.timing.StintMark
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** A part as anyone sees it: no hand-made lists, which are the admin's. */
@Serializable
data class PublicPart(val id: String, val kind: String, val name: String, val start: Long, val end: Long)

@Serializable
data class CarRef(val slug: String, val name: String)

/** An event as anyone sees it (M14.5). */
@Serializable
data class PublicEvent(
    val id: String,
    val name: String,
    val date: String,
    val course: String,
    val courseName: String,
    val layout: String,
    val layoutName: String,
    val cars: List<CarRef>,
    val parts: List<PublicPart>,
    /** The event's revision: what an edit to its race names (M15.4). */
    val revision: Int = 0,
)

/** A session in an event's results: its laps as they stand on the event's course. */
@Serializable
data class SessionResult(
    val id: String,
    val car: String,
    val driver: DriverView? = null,
    /** When the server first heard it, epoch milliseconds. */
    val heardFrom: Long,
    val laps: List<StandingLap> = emptyList(),
    /** Set when its laps were timed on another layout than the event's: shown, never counted. */
    val otherLayout: String? = null,
    /** Its fastest lap on track (§18); null without one. */
    val best: StandingLap? = null,
    /** Still being driven or uploaded: its laps are provisional (M17.6). */
    val live: Boolean = false,
    /** What the admin or the crew called it (M18.3). */
    val name: String? = null,
)

/** A driver's best lap in a part, or over all practice; a null driver is "not set". */
@Serializable
data class DriverBest(val driver: DriverView? = null, val car: String, val session: String, val lap: StandingLap)

/** A driver's sectors in a part (M16.2): their best of each, the gap to the best of all, and their theoretical best. */
@Serializable
data class SectorRow(
    val driver: DriverView? = null,
    /** Their best lap on track, seconds. */
    val best: Double? = null,
    val sectors: List<Double?>,
    val gaps: List<Double?>,
    val theoretical: Double? = null,
)

/** How consistent a driver was in a part (M16.2); a null driver is "not set". */
@Serializable
data class DriverConsistency(val driver: DriverView? = null, val consistency: Consistency)

@Serializable
data class PartResults(
    val part: PublicPart,
    val sessions: List<SessionResult>,
    val bests: List<DriverBest>,
    val bestSectors: List<Double?>,
    /** The best of each sector added up, when every sector has one (M16.2). */
    val theoretical: Double? = null,
    val sectorRows: List<SectorRow> = emptyList(),
    val consistency: List<DriverConsistency> = emptyList(),
)

/**
 * The race as one timeline (M15.4): each car's, classified. Laps, stops and
 * stints are on each tablet's clock; [tabletOffset] (by car) turns one into
 * the time of day.
 */
@Serializable
data class RaceResults(
    val part: PublicPart,
    val cars: List<CarRace>,
    /** As entered, epoch milliseconds, real time. */
    val green: Long? = null,
    val flag: Long? = null,
    /** How far each car's tablet clock is behind the server's, milliseconds. */
    val tabletOffset: Map<String, Long> = emptyMap(),
    /** Each car's stints as edited; absent for the default. */
    val edited: Map<String, List<StintView>> = emptyMap(),
)

@Serializable
data class EventResults(
    val event: PublicEvent,
    val parts: List<PartResults>,
    val practiceBests: List<DriverBest>,
    val practiceBestSectors: List<Double?>,
    val race: RaceResults? = null,
    /** Over all practice (M16.2). */
    val practiceTheoretical: Double? = null,
    val practiceSectorRows: List<SectorRow> = emptyList(),
    val practiceConsistency: List<DriverConsistency> = emptyList(),
)

/**
 * **Practice results** (M14.5), pure: each driver's best lap on track, never
 * an in- or out-lap (§18), fastest first; and the best of each sector, where an
 * in-lap's last sector and an out-lap's first never count (§22.6).
 */
object Results {
    fun onTrack(lap: StandingLap): Boolean = !lap.pitIn && !lap.pitOut

    fun best(laps: List<StandingLap>): StandingLap? = laps.filter(::onTrack).minByOrNull { it.time }

    /** One per driver (a session with none counts as "not set"), fastest first. */
    fun driverBests(sessions: List<SessionResult>): List<DriverBest> =
        sessions.filter { it.otherLayout == null }
            .flatMap { s -> s.laps.filter(::onTrack).map { s to it } }
            .groupBy { (s, _) -> s.driver?.id }
            .map { (_, laps) -> laps.minBy { (_, lap) -> lap.time }.let { (s, lap) -> DriverBest(s.driver, s.car, s.id, lap) } }
            .sortedBy { it.lap.time }

    /** Whether sector [i] of [lap] can be a best (§22.6): not an in-lap's last, nor an out-lap's first. */
    fun countsForBest(lap: StandingLap, i: Int): Boolean = !(lap.pitIn && i == lap.sectors.size - 1) && !(lap.pitOut && i == 0)

    /** A row per driver (M16.2): their best of each sector, the gap to the best of all, their theoretical best; quickest first. */
    fun sectorRows(sessions: List<SessionResult>): List<SectorRow> {
        val counted = sessions.filter { it.otherLayout == null }
        val all = bestSectors(counted)
        return counted.groupBy { it.driver?.id }.map { (_, mine) ->
            val theirs = bestSectors(mine).let { s -> all.indices.map { s.getOrNull(it) } }
            SectorRow(
                mine.first().driver,
                mine.flatMap { it.laps }.filter(::onTrack).minOfOrNull { it.time },
                theirs,
                theirs.mapIndexed { i, t -> all[i]?.let { best -> t?.let { ms(it - best) } } },
                Consistency.theoreticalBest(theirs),
            )
        }.sortedWith(compareBy(nullsLast()) { it.best })
    }

    /** Each driver's consistency over their laps on track (M16.2), the quickest first. */
    fun consistency(sessions: List<SessionResult>): List<DriverConsistency> =
        sessions.filter { it.otherLayout == null }.groupBy { it.driver?.id }.mapNotNull { (_, mine) ->
            Consistency.of(mine.flatMap { it.laps }.filter(::onTrack).map { it.time })?.let { DriverConsistency(mine.first().driver, it) }
        }.sortedBy { it.consistency.best }

    private fun ms(seconds: Double): Double = kotlin.math.round(seconds * 1000) / 1000.0

    fun bestSectors(sessions: List<SessionResult>): List<Double?> {
        val laps = sessions.filter { it.otherLayout == null }.flatMap { it.laps }
        val n = laps.maxOfOrNull { it.sectors.size } ?: 0
        return (0 until n).map { i -> laps.filter { it.sectors.size > i && countsForBest(it, i) }.minOfOrNull { it.sectors[i] } }
    }
}

/** A driver's best in one practice part (M15.5). */
@Serializable
data class DriverPracticeBest(val part: String, val best: DriverBest)

/** A stint a driver drove (M15.5): the car, and the stint as the race has it. */
@Serializable
data class DriverStint(val car: String, val stint: com.obd2dashboard.backend.timing.RaceStint)

@Serializable
data class DriverEvent(val event: PublicEvent, val practice: List<DriverPracticeBest>, val stints: List<DriverStint>)

/** A driver's best practice lap at a course, over every event (M15.5). */
@Serializable
data class CourseBest(val course: String, val courseName: String, val event: String, val best: DriverBest)

/** `GET /api/drivers/{id}` (M15.5): a driver's events, practice bests, stints, and best per course. */
@Serializable
data class DriverRecord(val driver: DriverView, val events: List<DriverEvent>, val courses: List<CourseBest>)

/** Events, public (M14.5): the list, and one with its practice results. */
fun Route.publicEventRoutes(
    stores: EventStores,
    courses: CourseStore,
    registry: CarRegistry,
    archive: ArchiveService,
    retiming: RetimingJobs,
    /** The live runs (M17.6): sessions not complete yet count, marked. */
    live: LiveTimings? = null,
    hub: LiveHub? = null,
    /** Results held briefly, so many viewers cost one computation. */
    held: ResultsHold = ResultsHold(),
) {
    suspend fun public(event: Event): PublicEvent {
        val course = courses.get(event.course)
        val layoutName = course?.let { (CourseRules.check(it.geojson) as? CourseCheck.Ok)?.shape }?.layouts?.firstOrNull { it.id == event.layout }?.name
        return PublicEvent(
            event.id, event.name, event.date, event.course, course?.name ?: event.course, event.layout, layoutName ?: event.layout,
            event.cars.map { slug -> CarRef(slug, runCatching { registry.get(Slug.parse(slug))?.name }.getOrNull() ?: slug) },
            event.parts.map { PublicPart(it.id, it.kind.name.lowercase(), it.name, it.start.toEpochMilli(), it.end.toEpochMilli()) },
            event.revision,
        )
    }

    get("/api/events") {
        call.respond(stores.events.list().map { public(it) })
    }

    /** An event's results (M14.5, M15.4): practice from each session's laps, the race as one timeline. */
    suspend fun eventResults(event: Event): EventResults {
        val records = archive.sessionsOfCars(event.cars).associateBy { it.id }
        val inParts = EventRules.sessionsIn(event, records.values.map { it.heard() })
        val drivers = stores.drivers.list().associateBy { it.id }
        val pub = public(event)
        // The live runs (M17.6), per car: its sessions in the event not complete yet, re-timed as complete ones are.
        val course = courses.get(event.course)
        val eventIds = inParts.values.flatten().toSet()
        val provisional = if (live == null || course == null) emptyMap() else event.cars.associateWith { car ->
            live.read(car) { held -> Provisional.runs(course, held.filter { s -> s.id in eventIds && records[s.id]?.complete == false }) }
        }
        val liveIds = provisional.values.flatten().flatMap { it.sessions }.toSet()
        val parts = event.parts.zip(pub.parts).map { (part, publicPart) ->
            val results = inParts[part.id].orEmpty().mapNotNull { records[it] }.map { r ->
                val laps = if (r.complete) retiming.sessionLaps(r.car, r.id, event.course)
                else provisional[r.car]?.firstOrNull { r.id in it.sessions }?.let { run ->
                    standingLaps(run, r.id)?.let { SessionLaps(run.course, course?.name ?: run.course, run.courseVersion, run.layout, it) }
                }
                val other = laps?.layout?.takeIf { it != event.layout }
                SessionResult(
                    r.id, r.car, r.driver?.let { drivers[it] }?.view(), r.created.toEpochMilli(),
                    laps?.laps.orEmpty(), other, if (other == null) Results.best(laps?.laps.orEmpty()) else null, live = r.id in liveIds, name = r.name,
                )
            }
            val sectors = Results.bestSectors(results)
            PartResults(
                publicPart, results, Results.driverBests(results), sectors,
                Consistency.theoreticalBest(sectors), Results.sectorRows(results), Results.consistency(results),
            )
        }
        val practice = parts.filter { it.part.kind == PartKind.PRACTICE.name.lowercase() }.flatMap { it.sessions }
        val race = event.race?.let { racePart ->
            val raceIds = inParts[racePart.id].orEmpty()
            val raceRecords = raceIds.mapNotNull { records[it] }
            val byCar = raceRecords.groupBy { it.car }
            val cars = byCar.mapNotNull { (car, sessions) ->
                // Each run once, in the order the server first heard its race sessions.
                val runs = (
                    sessions.sortedBy { it.created }.filter { it.complete }
                        .mapNotNull { retiming.lapsOf(car, it.id, event.course)?.second }
                        .distinctBy { it.sessions } + provisional[car].orEmpty()
                    ).filter { it.layout == event.layout }
                // The tablet's clock: as measured live and stored per session (M18.1), the smallest; else from each
                // summary as the sessions list gets it, stored or built now (M7.1); before any, the live lane's (M17.6).
                val offset = sessions.mapNotNull { it.clockOffsetMs }.minOrNull()
                    ?: Race.tabletOffset(sessions.filter { it.complete }.mapNotNull { r -> archive.summary(r.id)?.let { r.created to it.started } })
                    ?: hub?.status(car)?.clockOffset?.toMillis()
                Race.car(
                    car, runs, raceIds.toSet(),
                    drivers = sessions.associate { it.id to it.driver },
                    edited = racePart.stints[car]?.map { StintMark(it.start, it.driver) },
                    green = racePart.green?.let { g -> offset?.let { g.toEpochMilli() - it } },
                    flag = racePart.flag?.let { f -> offset?.let { f.toEpochMilli() - it } },
                )?.let { c -> c.copy(laps = c.laps.map { l -> if (l.session in liveIds) l.copy(live = true) else l }) }?.let { it to offset }
            }
            val publicRace = pub.parts.first { it.id == racePart.id }
            RaceResults(
                publicRace, Race.classify(cars.map { it.first }),
                racePart.green?.toEpochMilli(), racePart.flag?.toEpochMilli(),
                cars.mapNotNull { (c, o) -> o?.let { c.car to it } }.toMap(),
                racePart.stints.mapValues { (_, list) -> list.map { StintView(it.start, it.driver) } },
            )
        }
        val practiceSectors = Results.bestSectors(practice)
        return EventResults(
            pub, parts, Results.driverBests(practice), practiceSectors, race,
            Consistency.theoreticalBest(practiceSectors), Results.sectorRows(practice), Results.consistency(practice),
        )
    }

    get("/api/events/{id}") {
        val event = stores.events.get(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
        call.respond(held.get(event) { eventResults(event) })
    }

    get("/api/drivers/{id}") {
        val driver = stores.drivers.get(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such driver."))
        val events = stores.events.list().map { e ->
            val results = held.get(e) { eventResults(e) }
            val practice = results.parts.filter { it.part.kind == "practice" }.flatMap { p ->
                p.bests.filter { it.driver?.id == driver.id }.map { DriverPracticeBest(p.part.name, it) }
            }
            val stints = results.race?.cars.orEmpty().flatMap { c -> c.stints.filter { it.driver == driver.id }.map { DriverStint(c.car, it) } }
            DriverEvent(results.event, practice, stints)
        }.filter { it.practice.isNotEmpty() || it.stints.isNotEmpty() }
        val byCourse = events.flatMap { e -> e.practice.map { e.event to it.best } }
            .groupBy { (e, _) -> e.course }
            .map { (course, bests) -> bests.minBy { (_, b) -> b.lap.time }.let { (e, b) -> CourseBest(course, e.courseName, e.id, b) } }
            .sortedBy { it.courseName }
        call.respond(DriverRecord(driver.view(), events, byCourse))
    }
}

/**
 * An event's results held for [forMs] (M17.6): during a part the page asks
 * every 30 s, and many viewers cost one computation. Emptied whenever what
 * they're made of is edited (a driver, a race, an event, a course), so an
 * edit shows at once.
 */
class ResultsHold(private val clock: java.time.Clock = java.time.Clock.systemUTC(), private val forMs: Long = 10_000) {
    private val held = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, EventResults>>()

    suspend fun get(event: Event, compute: suspend () -> EventResults): EventResults {
        val key = "${event.id}@${event.revision}"
        val now = clock.millis()
        held[key]?.takeIf { now - it.first < forMs }?.let { return it.second }
        return compute().also { held[key] = now to it }
    }

    fun clear() = held.clear()
}

