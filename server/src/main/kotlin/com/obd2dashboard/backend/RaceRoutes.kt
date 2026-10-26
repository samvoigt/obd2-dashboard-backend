package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.registry.CarRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable

/** The race's green flag and chequered flag (M15.3), epoch milliseconds, real time; null clears one. */
@Serializable
data class SaveFlags(
    val expected: Int,
    val green: Long? = null,
    val flag: Long? = null,
    /** Which race session (M26): its part's id; none for a race in one. */
    val part: String? = null,
)

/** What an edit to the race answers: the event's new revision, for the next edit. */
@Serializable
data class RaceSaved(val revision: Int)

/**
 * **The race's flags** (M15.3): set by the admin, or by the crew of a car
 * entered in the event. Two routes, because each sign-in's cookie reaches only
 * its own paths (M14.4); one rule. Every edit names the revision it was made
 * on, and is logged with who made it. A race's stints aren't edited: they
 * follow from who drove each session (M32, decision 48).
 */
fun Route.raceRoutes(
    stores: EventStores,
    registry: CarRegistry,
    crewAuth: CrewAuth,
    adminAuth: AdminAuth,
    config: AdminConfig,
    clock: Clock,
    /** A race's flags saved: what the tablets are told may change (M17.4). */
    onChanged: () -> Unit = {},
) {
    put("/api/admin/events/{id}/race") {
        // The event's creator and editors, or a master admin (M23).
        val email = call.may(Action.EDIT, Thing(Kind.EVENT, call.parameters["id"].orEmpty()))?.email ?: return@put
        call.flags(stores, clock, onChanged, call.parameters["id"].orEmpty(), null, email)
    }

    put("/api/cars/{slug}/events/{id}/race") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        call.flags(stores, clock, onChanged, call.parameters["id"].orEmpty(), car.slug.value, "the crew of ${car.slug.value}")
    }
}

private suspend fun ApplicationCall.flags(stores: EventStores, clock: Clock, onChanged: () -> Unit, id: String, crewOf: String?, who: String) {
    val request = receive<SaveFlags>()
    editRace(stores, clock, onChanged, id, crewOf, request.expected, request.part, who, "flags ${request.green?.let(Instant::ofEpochMilli)} to ${request.flag?.let(Instant::ofEpochMilli)}") {
        it.copy(green = request.green?.let(Instant::ofEpochMilli), flag = request.flag?.let(Instant::ofEpochMilli))
    }
}

/** The event's race changed by [change], if [crewOf]'s car is entered (for a crew), the rules hold, and nobody saved since [expected]. */
private suspend fun ApplicationCall.editRace(
    stores: EventStores,
    clock: Clock,
    onChanged: () -> Unit,
    id: String,
    crewOf: String?,
    expected: Int,
    /** Which race session (M26); none means the race's only one. */
    part: String?,
    who: String,
    what: String,
    change: (Part) -> Part,
) {
    val event = stores.events.get(id) ?: return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
    if (crewOf != null && crewOf !in event.cars) return respond(HttpStatusCode.NotFound, ApiError("not_found", "This car isn't in that event."))
    val races = event.races
    if (races.isEmpty()) return respond(HttpStatusCode.Conflict, ApiError("no_race", "This event has no race."))
    val race = when {
        part != null -> races.firstOrNull { it.id == part }
            ?: return respond(HttpStatusCode.NotFound, ApiError("not_found", "This event's race has no session $part."))
        races.size == 1 -> races.single()
        else -> return respond(HttpStatusCode.BadRequest, ApiError("which_session", "This race is run in sessions: say which."))
    }
    val changed: Event = event.copy(parts = event.parts.map { if (it.id == race.id && it.kind == PartKind.RACE) change(it) else it })
    val problems = EventRules.eventProblems(changed)
    if (problems.isNotEmpty()) return respond(HttpStatusCode.BadRequest, EventRefused("invalid", "The race can't be saved as it is.", problems))
    val saved = stores.events.save(changed, expected, clock.instant())
        ?: return respond(HttpStatusCode.Conflict, ApiError("conflict", "Someone changed this event since you opened it. Reload it, and make your change again."))
    adminLog.info("race of {} set, {}, by {}", if (races.size > 1) "$id (${race.id})" else id, what, who)
    onChanged()
    respond(RaceSaved(saved.revision))
}
