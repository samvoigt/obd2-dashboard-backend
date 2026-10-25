package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.DriverChange
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionVideo
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

/** A driver change within a session (M27): from [from] on the tablet's `wall` (milliseconds), [driver] (null: not set). */
@Serializable
data class DriverChangeView(val from: Long, val driver: String? = null)

/** A session's driver changes, the whole list (M27); empty: its driver throughout. */
@Serializable
data class SetDriverChanges(val changes: List<DriverChangeView> = emptyList())

fun DriverChange.view(): DriverChangeView = DriverChangeView(from, driver)

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

    // Driver changes (M27): the same two sign-ins, the same rule.
    put("/api/admin/sessions/{id}/driver-changes") {
        call.signedIn(change = true) ?: return@put
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, record.car)) ?: return@put
        call.setDriverChanges(record, drivers, archive, who.email, onChanged)
    }

    put("/api/cars/{slug}/sessions/{id}/driver-changes") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?.takeIf { it.car == car.slug.value }
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        call.setDriverChanges(record, drivers, archive, "the crew of ${car.slug.value}", onChanged)
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

    // A session's GPS latency and videos (M30): the same two sign-ins, the same rule.
    put("/api/admin/sessions/{id}/gps-latency") {
        call.signedIn(change = true) ?: return@put
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, record.car)) ?: return@put
        call.setGpsLatency(record, archive, who.email, onChanged)
    }

    put("/api/cars/{slug}/sessions/{id}/gps-latency") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?.takeIf { it.car == car.slug.value }
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        call.setGpsLatency(record, archive, "the crew of ${car.slug.value}", onChanged)
    }

    put("/api/admin/sessions/{id}/videos") {
        call.signedIn(change = true) ?: return@put
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, record.car)) ?: return@put
        call.setVideos(record, archive, who.email, onChanged)
    }

    put("/api/cars/{slug}/sessions/{id}/videos") {
        val car = call.pathCar(registry) ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(crewAuth, car)) return@put call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val record = archive.session(call.parameters["id"].orEmpty().lowercase())
            ?.takeIf { it.car == car.slug.value }
            ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        call.setVideos(record, archive, "the crew of ${car.slug.value}", onChanged)
    }
}

/** How far a session's GPS receiver runs behind (M30.2), milliseconds; null or 0 clears it. */
@Serializable
data class SetGpsLatency(val latencyMs: Long? = null)

/** Past any receiver's: a second's worth at most is what's been measured (JOURNAL, M30). */
const val GPS_LATENCY_MAX_MS: Long = 5_000

/** A video of a session (M30.3): [file]'s first frame at [startWall] on the tablet's `wall`, [durationMs] long. */
@Serializable
data class SessionVideoView(val file: String, val startWall: Long, val durationMs: Long)

/** A session's videos, the whole list (M30.3); empty: none. */
@Serializable
data class SetVideos(val videos: List<SessionVideoView> = emptyList())

fun SessionVideo.view(): SessionVideoView = SessionVideoView(file, startWall, durationMs)

/** A camera's chapters in one session: far more than a day's racing records. */
const val VIDEOS_MAX: Int = 200
const val VIDEO_FILE_MAX: Int = 200
/** A day: no chapter, nor a session, is longer. */
const val VIDEO_DURATION_MAX_MS: Long = 86_400_000

/** The videos to store, in order, or why they can't be. */
fun sessionVideos(raw: List<SessionVideoView>): Result<List<SessionVideo>> {
    val videos = raw.map { SessionVideo(it.file.trim(), it.startWall, it.durationMs) }.sortedBy { it.startWall }
    return when {
        videos.size > VIDEOS_MAX -> Result.failure(IllegalArgumentException("At most $VIDEOS_MAX videos in a session."))
        videos.any { it.file.isEmpty() || it.file.length > VIDEO_FILE_MAX || it.file.any(Char::isISOControl) } ->
            Result.failure(IllegalArgumentException("A video's file is one line of up to $VIDEO_FILE_MAX characters."))
        videos.any { it.startWall <= 0 } -> Result.failure(IllegalArgumentException("A video starts at a moment on the tablet's clock."))
        videos.any { it.durationMs <= 0 || it.durationMs > VIDEO_DURATION_MAX_MS } ->
            Result.failure(IllegalArgumentException("A video is longer than nothing and shorter than a day."))
        else -> Result.success(videos)
    }
}

private suspend fun ApplicationCall.setGpsLatency(record: SessionRecord, archive: ArchiveService, who: String, onChanged: () -> Unit) {
    val latency = receive<SetGpsLatency>().latencyMs?.takeIf { it != 0L }
    if (latency != null && latency !in 1..GPS_LATENCY_MAX_MS) {
        return respond(HttpStatusCode.BadRequest, ApiError("invalid", "A GPS latency is between 0 and $GPS_LATENCY_MAX_MS ms."))
    }
    if (!archive.setGpsLatency(record.id, latency)) return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    adminLog.info("gps latency: session {} of {} to {} by {}", record.id, record.car, latency?.let { "$it ms" } ?: "none", who)
    onChanged()
    respond(SetGpsLatency(latency))
}

private suspend fun ApplicationCall.setVideos(record: SessionRecord, archive: ArchiveService, who: String, onChanged: () -> Unit) {
    val videos = sessionVideos(receive<SetVideos>().videos).getOrElse { return respond(HttpStatusCode.BadRequest, ApiError("invalid", it.message ?: "Not a video.")) }
    if (!archive.setVideos(record.id, videos)) return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    adminLog.info("videos: session {} of {}, {} by {}", record.id, record.car, videos.size, who)
    onChanged()
    respond(SetVideos(videos.map { it.view() }))
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

private suspend fun ApplicationCall.setDriverChanges(record: SessionRecord, drivers: DriverStore, archive: ArchiveService, who: String, onChanged: () -> Unit) {
    if (record.heard().source == "fake") {
        return respond(HttpStatusCode.Conflict, ApiError("fake", "This session is test data; nobody drove it."))
    }
    val changes = receive<SetDriverChanges>().changes.map { DriverChange(it.from, it.driver) }.sortedBy { it.from }
    if (changes.map { it.from }.toSet().size != changes.size) return respond(HttpStatusCode.BadRequest, ApiError("invalid", "Two changes at one moment."))
    if (changes.any { it.from <= 0 }) return respond(HttpStatusCode.BadRequest, ApiError("invalid", "A change is a moment on the tablet's clock."))
    if (changes.size > MAX_CHANGES) return respond(HttpStatusCode.BadRequest, ApiError("invalid", "At most $MAX_CHANGES changes in a session."))
    val unknown = changes.mapNotNull { it.driver }.distinct().filter { drivers.get(it) == null }
    if (unknown.isNotEmpty()) return respond(HttpStatusCode.BadRequest, ApiError("no_driver", "No such driver."))
    if (!archive.setDriverChanges(record.id, changes)) return respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    adminLog.info("driver changes: session {} of {}, {} by {}", record.id, record.car,
        changes.joinToString(", ") { "${it.driver ?: "nobody"} from ${it.from}" }.ifEmpty { "none" }, who)
    onChanged()
    respond(SetDriverChanges(changes.map { it.view() }))
}

/** Driver changes in one session: far more than any day's racing has. */
private const val MAX_CHANGES = 50
