package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.events.DriverStore
import com.obd2dashboard.backend.registry.CarRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** Who drove a session (M14.4): a driver's id, or null to clear it. */
@Serializable
data class SetDriver(val driver: String?)

/**
 * **Who drove** (M14.4): public to read, set by the admin or the car's crew.
 * Two routes because each sign-in's cookie is scoped to its own paths
 * (the admin's `/api/admin`, the crew's `/api/cars/{slug}`); one rule.
 */
fun Route.driverRoutes(
    drivers: DriverStore,
    archive: ArchiveService,
    registry: CarRegistry,
    crewAuth: CrewAuth,
    adminAuth: AdminAuth,
    config: AdminConfig,
    /** A session's driver set: what the tablets are told may change (M17.4). */
    onChanged: () -> Unit = {},
) {
    get("/api/drivers") {
        call.respond(drivers.list().map { it.view() })
    }

    // A car's sessions are its editors' to name and set who drove (M23): asked once the session's car is known.
    put("/api/admin/sessions/{id}/driver") {
        call.signedIn(change = true) ?: return@put
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, record.car)) ?: return@put
        call.setDriver(record, drivers, archive, who.email, onChanged)
    }

    put("/api/cars/{slug}/sessions/{id}/driver") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?.takeIf { it.car == car.slug.value }
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        call.setDriver(record, drivers, archive, "the crew of ${car.slug.value}", onChanged)
    }

    // Names (M18.3): the same two sign-ins, the same rule for whose session it is.
    put("/api/admin/sessions/{id}/name") {
        call.signedIn(change = true) ?: return@put
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, record.car)) ?: return@put
        call.setName(record, archive, who.email, onChanged)
    }

    put("/api/cars/{slug}/sessions/{id}/name") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?.takeIf { it.car == car.slug.value }
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        call.setName(record, archive, "the crew of ${car.slug.value}", onChanged)
    }
}

/** A session's name (M18.3): trimmed, up to [NAME_MAX] characters, no control characters; empty clears it. */
@Serializable
data class SetName(val name: String? = null)

const val NAME_MAX: Int = 60

/** The name to store, or why it can't be. */
fun sessionName(raw: String?): Result<String?> {
    val name = raw?.trim().orEmpty()
    return when {
        name.isEmpty() -> Result.success(null)
        name.length > NAME_MAX -> Result.failure(IllegalArgumentException("A name is at most $NAME_MAX characters."))
        name.any { it.isISOControl() } -> Result.failure(IllegalArgumentException("A name is one line of text."))
        else -> Result.success(name)
    }
}

private suspend fun ApplicationCall.setName(record: SessionRecord, archive: ArchiveService, who: String, onChanged: () -> Unit) {
    val name = sessionName(receive<SetName>().name).getOrElse { return respond(HttpStatusCode.BadRequest, ApiError("invalid", it.message ?: "Not a name.")) }
    if (!archive.setName(record.id, name)) return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    adminLog.info("session named: {} of {} to {} by {}", record.id, record.car, name?.let { "\"$it\"" } ?: "nothing", who)
    onChanged()
    respond(SetName(name))
}

private suspend fun ApplicationCall.setDriver(record: SessionRecord, drivers: DriverStore, archive: ArchiveService, who: String, onChanged: () -> Unit) {
    if (record.heard().source == "fake") {
        return respond(HttpStatusCode.Conflict, ApiError("fake", "This session is test data; nobody drove it."))
    }
    val id = receive<SetDriver>().driver
    val driver = id?.let { drivers.get(it) ?: return respond(HttpStatusCode.BadRequest, ApiError("no_driver", "No such driver.")) }
    if (!archive.setDriver(record.id, driver?.id)) return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    adminLog.info("driver set: session {} of {} to {} by {}", record.id, record.car, driver?.code ?: "nobody", who)
    onChanged()
    respond(SetDriver(driver?.id))
}
