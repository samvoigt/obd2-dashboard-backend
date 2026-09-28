package com.obd2dashboard.backend

import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.events.Stint
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
data class SaveFlags(val expected: Int, val green: Long? = null, val flag: Long? = null)

/** A stint as edited: from [start] on the tablet's clock, milliseconds. */
@Serializable
data class StintView(val start: Long, val driver: String? = null)

/** A car's stints (M15.3); null or empty goes back to the default, split at every stop. */
@Serializable
data class SaveStints(val expected: Int, val stints: List<StintView>? = null)

/** What an edit to the race answers: the event's new revision, for the next edit. */
@Serializable
data class RaceSaved(val revision: Int)

/**
 * **The race's flags and stints** (M15.3): set by the admin, or by the crew of
 * a car entered in the event. Two routes each, because each sign-in's cookie
 * reaches only its own paths (M14.4); one rule. Every edit names the revision
 * it was made on, and is logged with who made it.
 */
fun Route.raceRoutes(
    stores: EventStores,
    registry: CarRegistry,
    crewAuth: CrewAuth,
    adminAuth: AdminAuth,
    config: AdminConfig,
    clock: Clock,
    /** A race's flags or stints saved: what the tablets are told may change (M17.4). */
    onChanged: () -> Unit = {},
) {
    put("/api/admin/events/{id}/race") {
        val email = call.admin(adminAuth, config, change = true) ?: return@put
        call.flags(stores, clock, onChanged, call.parameters["id"].orEmpty(), null, email)
    }

    put("/api/admin/events/{id}/race/stints/{car}") {
        val email = call.admin(adminAuth, config, change = true) ?: return@put
        call.stints(stores, clock, onChanged, call.parameters["id"].orEmpty(), call.parameters["car"].orEmpty(), email)
    }

    put("/api/cars/{slug}/events/{id}/race") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        call.flags(stores, clock, onChanged, call.parameters["id"].orEmpty(), car.slug.value, "the crew of ${car.slug.value}")
    }

    put("/api/cars/{slug}/events/{id}/race/stints") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        call.stints(stores, clock, onChanged, call.parameters["id"].orEmpty(), car.slug.value, "the crew of ${car.slug.value}")
    }
}

private suspend fun ApplicationCall.flags(stores: EventStores, clock: Clock, onChanged: () -> Unit, id: String, crewOf: String?, who: String) {
    val request = receive<SaveFlags>()
    editRace(stores, clock, onChanged, id, crewOf, request.expected, who, "flags ${request.green?.let(Instant::ofEpochMilli)} to ${request.flag?.let(Instant::ofEpochMilli)}") {
        it.copy(green = request.green?.let(Instant::ofEpochMilli), flag = request.flag?.let(Instant::ofEpochMilli))
    }
}

private suspend fun ApplicationCall.stints(stores: EventStores, clock: Clock, onChanged: () -> Unit, id: String, car: String, who: String) {
    val request = receive<SaveStints>()
    val stints = request.stints.orEmpty().map { Stint(it.start, it.driver) }
    val unknown = stints.mapNotNull { it.driver }.distinct().filter { stores.drivers.get(it) == null }
    if (unknown.isNotEmpty()) return respond(HttpStatusCode.BadRequest, EventRefused("invalid", "No such driver.", unknown.map { "there's no driver $it" }))
    val what = if (stints.isEmpty()) "stints of $car back to the default" else "${stints.size} stint(s) of $car"
    editRace(stores, clock, onChanged, id, car, request.expected, who, what) { race ->
        race.copy(stints = if (stints.isEmpty()) race.stints - car else race.stints + (car to stints.sortedBy { it.start }))
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
    who: String,
    what: String,
    change: (Part) -> Part,
) {
    val event = stores.events.get(id) ?: return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
    if (crewOf != null && crewOf !in event.cars) return respond(HttpStatusCode.NotFound, ApiError("not_found", "This car isn't in that event."))
    val race = event.race ?: return respond(HttpStatusCode.Conflict, ApiError("no_race", "This event has no race."))
    val changed: Event = event.copy(parts = event.parts.map { if (it.id == race.id && it.kind == PartKind.RACE) change(it) else it })
    val problems = EventRules.eventProblems(changed)
    if (problems.isNotEmpty()) return respond(HttpStatusCode.BadRequest, EventRefused("invalid", "The race can't be saved as it is.", problems))
    val saved = stores.events.save(changed, expected, clock.instant())
        ?: return respond(HttpStatusCode.Conflict, ApiError("conflict", "Someone changed this event since you opened it. Reload it, and make your change again."))
    adminLog.info("race of {} set, {}, by {}", id, what, who)
    onChanged()
    respond(RaceSaved(saved.revision))
}
