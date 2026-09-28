package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.DriverStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.EventStore
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.events.SessionHeard
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.SlugCheck
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

/** Drivers and events (M14), passed into the module together. */
class EventStores(val drivers: DriverStore, val events: EventStore)

@Serializable
data class DriverView(val id: String, val name: String, val code: String)

@Serializable
data class SaveDriver(val name: String, val code: String)

@Serializable
data class PartView(
    /** Absent on a part the editor has just added: the server gives it the next id. */
    val id: String? = null,
    /** `practice` or `race`. */
    val kind: String,
    val name: String,
    /** Epoch milliseconds, the server's time: `[start, end)`. */
    val start: Long,
    val end: Long,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

@Serializable
data class EventView(
    val id: String,
    val name: String,
    val date: String,
    val course: String,
    val layout: String,
    val cars: List<String>,
    val parts: List<PartView>,
    val revision: Int,
)

/** A save: the revision it replaces (0 for a new event), so two editors can't both win. */
@Serializable
data class SaveEvent(
    val expected: Int,
    val name: String,
    val date: String,
    val course: String,
    val layout: String,
    val cars: List<String>,
    val parts: List<PartView>,
)

/** A session as an event's page lists it (M14). */
@Serializable
data class SessionBrief(
    val id: String,
    val car: String,
    /** When the server first and last heard it, epoch milliseconds: what places it in a part. */
    val heardFrom: Long,
    val heardTo: Long,
    /** The tablet's own start, as its page shows it. */
    val started: Long? = null,
    val source: String? = null,
    val driver: String? = null,
    val laps: Int = 0,
)

/** An event on the admin page: each part's sessions, and the entered cars' others around its day, to add by hand. */
@Serializable
data class AdminEvent(val event: EventView, val sessions: Map<String, List<SessionBrief>>, val others: List<SessionBrief>)

@Serializable
data class EventRefused(val error: String, val message: String, val problems: List<String>)

fun Driver.view(): DriverView = DriverView(id, name, code)

fun Event.view(): EventView = EventView(
    id, name, date, course, layout, cars,
    parts.map { PartView(it.id, it.kind.name.lowercase(), it.name, it.start.toEpochMilli(), it.end.toEpochMilli(), it.added, it.removed) },
    revision,
)

/** What joining a part needs of a stored session: the server's times, and its source (the header's, else the summary's). */
fun SessionRecord.heard(): SessionHeard = SessionHeard(id, car, created, updated, header?.source ?: summary?.source)

fun SessionRecord.brief(): SessionBrief = SessionBrief(
    id, car, created.toEpochMilli(), updated.toEpochMilli(), summary?.started, header?.source ?: summary?.source, driver, summary?.laps ?: 0,
)

/** Every session of [cars], as the index holds them. */
suspend fun ArchiveService.sessionsOfCars(cars: Collection<String>): List<SessionRecord> = cars.flatMap { sessionsOf(it) }

/**
 * The admin page's drivers and events (M14.3): signed in, from our page, and
 * every change logged with who made it (decision 25).
 */
fun Route.adminEventRoutes(
    stores: EventStores,
    courses: CourseStore,
    registry: CarRegistry,
    archive: ArchiveService,
    clock: Clock,
    auth: AdminAuth,
    config: AdminConfig,
) {
    val drivers = stores.drivers
    val events = stores.events

    get("/api/admin/drivers") {
        call.admin(auth, config, change = false) ?: return@get
        call.respond(drivers.list().map { it.view() })
    }

    post("/api/admin/drivers") {
        val email = call.admin(auth, config, change = true) ?: return@post
        val request = call.receive<SaveDriver>()
        val driver = Driver(newDriverId(), request.name.trim(), request.code.trim())
        call.saveDriver(drivers, driver, email, HttpStatusCode.Created)
    }

    put("/api/admin/drivers/{id}") {
        val email = call.admin(auth, config, change = true) ?: return@put
        val id = call.parameters["id"].orEmpty()
        drivers.get(id) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such driver."))
        val request = call.receive<SaveDriver>()
        call.saveDriver(drivers, Driver(id, request.name.trim(), request.code.trim()), email, HttpStatusCode.OK)
    }

    delete("/api/admin/drivers/{id}") {
        val email = call.admin(auth, config, change = true) ?: return@delete
        val id = call.parameters["id"].orEmpty()
        val driver = drivers.get(id) ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such driver."))
        if (archive.sessionsOfCars(registry.list().map { it.slug.value }).any { it.driver == id }) {
            return@delete call.respond(HttpStatusCode.Conflict, ApiError("in_use", "${driver.name} drove sessions, so stays. Rename instead."))
        }
        drivers.delete(id)
        adminLog.info("driver removed: {} ({}) by {}", driver.name, driver.code, email)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/admin/events") {
        call.admin(auth, config, change = false) ?: return@get
        call.respond(events.list().map { it.view() })
    }

    get("/api/admin/events/{id}") {
        call.admin(auth, config, change = false) ?: return@get
        val event = events.get(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
        val sessions = archive.sessionsOfCars(event.cars)
        val inParts = EventRules.sessionsIn(event, sessions.map { it.heard() })
        val byId = sessions.associateBy { it.id }
        val placed = inParts.values.flatten().toSet()
        val day = runCatching { LocalDate.parse(event.date) }.getOrNull()
        val around = day?.let { it.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) to it.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC) }
        val others = sessions
            .filter { it.id !in placed && it.heard().source != "fake" }
            .filter { s -> around == null || (s.created.isBefore(around.second) && !s.updated.isBefore(around.first)) }
            .sortedBy { it.created }
        call.respond(AdminEvent(event.view(), inParts.mapValues { (_, ids) -> ids.mapNotNull { byId[it]?.brief() } }, others.map { it.brief() }))
    }

    put("/api/admin/events/{id}") {
        val email = call.admin(auth, config, change = true) ?: return@put
        val id = call.parameters["id"].orEmpty()
        val request = call.receive<SaveEvent>()
        val current = events.get(id)
        val event = request.toEvent(id, current)
        val problems = EventRules.eventProblems(event) + existenceProblems(event, courses, registry)
        if (problems.isNotEmpty()) {
            return@put call.respond(HttpStatusCode.BadRequest, EventRefused("invalid", "The event can't be saved as it is.", problems))
        }
        val saved = events.save(event, request.expected, clock.instant())
            ?: return@put call.respond(
                HttpStatusCode.Conflict,
                ApiError("conflict", if (request.expected == 0) "An event already has that id." else "Someone saved this event since you opened it. Reload it, and make your change again."),
            )
        adminLog.info("event saved: {} r{} by {}", id, saved.revision, email)
        call.respond(if (saved.revision == 1) HttpStatusCode.Created else HttpStatusCode.OK, saved.view())
    }

    delete("/api/admin/events/{id}") {
        val email = call.admin(auth, config, change = true) ?: return@delete
        val id = call.parameters["id"].orEmpty()
        if (!events.delete(id)) return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
        adminLog.info("event removed: {} by {}", id, email)
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun ApplicationCall.saveDriver(drivers: DriverStore, driver: Driver, email: String, created: HttpStatusCode) {
    val problems = EventRules.driverProblems(driver, drivers.list())
    if (problems.isNotEmpty()) return respond(HttpStatusCode.BadRequest, EventRefused("invalid", "The driver can't be saved as they are.", problems))
    val saved = drivers.put(driver)
        ?: return respond(HttpStatusCode.Conflict, ApiError("conflict", "Another driver took the code ${driver.code} just now."))
    adminLog.info("driver saved: {} ({}) by {}", saved.name, saved.code, email)
    respond(created, saved.view())
}

/** The event as it will be stored: new parts given the next ids, in turn. */
private fun SaveEvent.toEvent(id: String, current: Event?): Event {
    var draft = Event(id, name.trim(), date.trim(), course, layout, cars, current?.parts.orEmpty())
    val parts = parts.map { p ->
        val kind = if (p.kind == "race") PartKind.RACE else PartKind.PRACTICE
        val partId = p.id ?: draft.nextPartId()
        Part(partId, kind, p.name.trim(), Instant.ofEpochMilli(p.start), Instant.ofEpochMilli(p.end), p.added.distinct(), p.removed.distinct())
            .also { part -> draft = draft.copy(parts = draft.parts + part) }
    }
    return draft.copy(parts = parts)
}

/** The course and its layout, and every car, must exist. */
private suspend fun existenceProblems(event: Event, courses: CourseStore, registry: CarRegistry): List<String> = buildList {
    val course = if (event.course.isBlank()) null else courses.get(event.course)
    if (event.course.isNotBlank() && course == null) add("there's no course ${event.course}")
    val shape = course?.let { (CourseRules.check(it.geojson) as? CourseCheck.Ok)?.shape }
    if (shape != null && event.layout.isNotBlank() && shape.layouts.none { it.id == event.layout }) add("${course.name} has no layout ${event.layout}")
    for (car in event.cars) {
        val slug = (Slug.check(car) as? SlugCheck.Ok)?.slug
        if (slug == null || registry.get(slug) == null) add("there's no car $car")
    }
}

private val random = SecureRandom()

/** `d-` and 8 hex digits: a driver's id, never shown. */
private fun newDriverId(): String = "d-" + ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
