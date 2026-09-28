package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.Slug
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
)

/** A driver's best lap in a part, or over all practice; a null driver is "not set". */
@Serializable
data class DriverBest(val driver: DriverView? = null, val car: String, val session: String, val lap: StandingLap)

@Serializable
data class PartResults(val part: PublicPart, val sessions: List<SessionResult>, val bests: List<DriverBest>, val bestSectors: List<Double?>)

@Serializable
data class EventResults(val event: PublicEvent, val parts: List<PartResults>, val practiceBests: List<DriverBest>, val practiceBestSectors: List<Double?>)

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

    fun bestSectors(sessions: List<SessionResult>): List<Double?> {
        val laps = sessions.filter { it.otherLayout == null }.flatMap { it.laps }
        val n = laps.maxOfOrNull { it.sectors.size } ?: 0
        return (0 until n).map { i -> laps.filter { it.sectors.size > i && countsForBest(it, i) }.minOfOrNull { it.sectors[i] } }
    }
}

/** Events, public (M14.5): the list, and one with its practice results. */
fun Route.publicEventRoutes(
    stores: EventStores,
    courses: CourseStore,
    registry: CarRegistry,
    archive: ArchiveService,
    retiming: RetimingJobs,
) {
    suspend fun public(event: Event): PublicEvent {
        val course = courses.get(event.course)
        val layoutName = course?.let { (CourseRules.check(it.geojson) as? CourseCheck.Ok)?.shape }?.layouts?.firstOrNull { it.id == event.layout }?.name
        return PublicEvent(
            event.id, event.name, event.date, event.course, course?.name ?: event.course, event.layout, layoutName ?: event.layout,
            event.cars.map { slug -> CarRef(slug, runCatching { registry.get(Slug.parse(slug))?.name }.getOrNull() ?: slug) },
            event.parts.map { PublicPart(it.id, it.kind.name.lowercase(), it.name, it.start.toEpochMilli(), it.end.toEpochMilli()) },
        )
    }

    get("/api/events") {
        call.respond(stores.events.list().map { public(it) })
    }

    get("/api/events/{id}") {
        val event = stores.events.get(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
        val records = archive.sessionsOfCars(event.cars).associateBy { it.id }
        val inParts = EventRules.sessionsIn(event, records.values.map { it.heard() })
        val drivers = stores.drivers.list().associateBy { it.id }
        val pub = public(event)
        val parts = event.parts.zip(pub.parts).map { (part, publicPart) ->
            val results = inParts[part.id].orEmpty().mapNotNull { records[it] }.map { r ->
                val laps = if (r.complete) retiming.sessionLaps(r.car, r.id, event.course) else null
                val other = laps?.layout?.takeIf { it != event.layout }
                SessionResult(
                    r.id, r.car, r.driver?.let { drivers[it] }?.view(), r.created.toEpochMilli(),
                    laps?.laps.orEmpty(), other, if (other == null) Results.best(laps?.laps.orEmpty()) else null,
                )
            }
            PartResults(publicPart, results, Results.driverBests(results), Results.bestSectors(results))
        }
        val practice = parts.filter { it.part.kind == PartKind.PRACTICE.name.lowercase() }.flatMap { it.sessions }
        call.respond(EventResults(pub, parts, Results.driverBests(practice), Results.bestSectors(practice)))
    }
}
