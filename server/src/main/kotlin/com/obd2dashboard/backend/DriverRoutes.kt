package com.obd2dashboard.backend

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
) {
    get("/api/drivers") {
        call.respond(drivers.list().map { it.view() })
    }

    put("/api/admin/sessions/{id}/driver") {
        val email = call.admin(adminAuth, config, change = true) ?: return@put
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        call.setDriver(record, drivers, archive, email)
    }

    put("/api/cars/{slug}/sessions/{id}/driver") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?.takeIf { it.car == car.slug.value }
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        call.setDriver(record, drivers, archive, "the crew of ${car.slug.value}")
    }
}

private suspend fun ApplicationCall.setDriver(record: SessionRecord, drivers: DriverStore, archive: ArchiveService, who: String) {
    if (record.heard().source == "fake") {
        return respond(HttpStatusCode.Conflict, ApiError("fake", "This session is test data; nobody drove it."))
    }
    val id = receive<SetDriver>().driver
    val driver = id?.let { drivers.get(it) ?: return respond(HttpStatusCode.BadRequest, ApiError("no_driver", "No such driver.")) }
    if (!archive.setDriver(record.id, driver?.id)) return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    adminLog.info("driver set: session {} of {} to {} by {}", record.id, record.car, driver?.code ?: "nobody", who)
    respond(SetDriver(driver?.id))
}
