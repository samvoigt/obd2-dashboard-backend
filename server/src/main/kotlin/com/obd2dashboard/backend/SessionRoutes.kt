package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.LapInfo
import com.obd2dashboard.backend.archive.SessionIds
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.events.DriverStore
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.SlugCheck
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Clock
import java.time.Duration
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable

/** A lap as the site shows it. */
@Serializable
data class LapView(val lap: Int, val time: Double)

/** A lap as it stands on a session's page (M13.5): the tablet's, or re-timed. Times on `wall`, epoch milliseconds. */
@Serializable
data class StandingLap(
    /** Through the run of the app, as the tablet numbers them. */
    val lap: Int,
    val time: Double,
    val sectors: List<Double>,
    val pitIn: Boolean,
    val pitOut: Boolean,
    val start: Long,
    val end: Long,
    /** `tablet` or `retimed`. */
    val source: String,
    /** False for a tablet lap that couldn't be checked (no crossings sent). */
    val checked: Boolean = true,
    /** What re-timing found where it disagrees with the tablet's lap by more than 2 ms. */
    val flag: LapFlag? = null,
)

/** Re-timing's lap where the tablet's is flagged; all null if it found none there. */
@Serializable
data class LapFlag(val time: Double? = null, val start: Long? = null, val end: Long? = null)

/** `GET /api/sessions/{id}/laps` (M13.5). */
@Serializable
data class SessionLaps(val course: String, val courseName: String, val courseVersion: Int, val layout: String, val laps: List<StandingLap>)

/** A session in a car's list (M7.3). Never the VIN: nothing here can hold it. */
@Serializable
data class SessionItem(
    val id: String,
    /** Epoch milliseconds. */
    val started: Long,
    val ended: Long,
    val lines: Long,
    /** `live`, `uploading`, `complete` or `incomplete`. */
    val state: String,
    val track: String? = null,
    val layout: String? = null,
    val laps: Int = 0,
    val bestLap: LapView? = null,
    val faults: List<String> = emptyList(),
    /** `tablet` (no car read, §20), `fake` (test data, §21), or null for a car's session (M11). */
    val source: String? = null,
)

/** Sessions close enough together to be one drive: an adapter reconnect starts a new session (§3.2). */
@Serializable
data class Drive(val started: Long, val ended: Long, val sessions: List<SessionItem>)

@Serializable
data class SignalView(val name: String, val unit: String, val kind: String)

/** One session's page (M7.3). The summary's fields are there once the session is complete. */
@Serializable
data class SessionDetail(
    val session: SessionItem,
    val car: String,
    val carName: String,
    val signals: List<SignalView> = emptyList(),
    val gaps: Int = 0,
    val missed: Long = 0,
    /** Who drove it (M14.4); null until someone says. */
    val driver: DriverView? = null,
)

/**
 * Past sessions (M7.3): public, like the live pages (decision 11), and never
 * the VIN. The prepared series is sent as stored, gzipped.
 */
fun Route.sessionRoutes(
    registry: CarRegistry,
    archive: ArchiveService,
    hub: LiveHub,
    clock: Clock,
    /** A complete session's laps as they stand (M13.5); null if it was at no course. */
    laps: suspend (car: String, id: String) -> SessionLaps? = { _, _ -> null },
    /** For a session's driver's name (M14.4). */
    drivers: DriverStore? = null,
) {
    suspend fun item(record: SessionRecord, live: String?): SessionItem {
        val summary = if (record.complete) record.summary?.takeIf { it.version == SessionSummary.VERSION } ?: archive.summary(record.id) else null
        // The summary just built, if it was missing: the record was read before it existed.
        val started = summary?.started ?: sessionStarted(record).toEpochMilli()
        val state = sessionState(record, live, clock.instant())
        return SessionItem(
            id = record.id,
            started = started,
            // A live session is still going: it ends now, as far as anyone can say.
            ended = if (state == "live") clock.millis() else summary?.ended ?: maxOf(record.updated.toEpochMilli(), started),
            lines = record.ackedThrough + 1,
            state = state,
            track = summary?.track,
            layout = summary?.layout,
            laps = summary?.laps ?: 0,
            bestLap = summary?.bestLap?.view(),
            faults = summary?.faults.orEmpty(),
            // The summary's, or while uploading the header's.
            source = summary?.source ?: record.header?.source,
        )
    }

    get("/api/cars/{slug}/sessions") {
        val slug = (Slug.check(call.parameters["slug"].orEmpty()) as? SlugCheck.Ok)?.slug
        val car = slug?.let { registry.get(it) } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        val live = hub.status(car.slug.value).liveSession()
        // A session the live lane announced has no archived lines for its first minutes: listed while live.
        val items = archive.sessionsOf(car.slug.value).filter { it.ackedThrough >= 0 || it.id == live }.map { item(it, live) }
        call.respond(drives(items))
    }

    get("/api/sessions/{id}") {
        // A live session has a page before its first chunk arrives (M7.6): from the live stream alone.
        val record = call.sessionRecord(archive, allowLive = hub) ?: return@get
        val car = registry.get(Slug.parse(record.car)) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val summary = if (record.complete) archive.summary(record.id) else null
        call.respond(
            SessionDetail(
                session = item(record, hub.status(record.car).liveSession()),
                car = car.slug.value,
                carName = car.name,
                signals = summary?.signals.orEmpty().map { SignalView(it.name, it.unit, it.kind) },
                gaps = summary?.gaps ?: 0,
                missed = summary?.missed ?: 0,
                driver = record.driver?.let { drivers?.get(it) }?.view(),
            ),
        )
    }

    get("/api/sessions/{id}/laps") {
        val record = call.sessionRecord(archive) ?: return@get
        if (!record.complete) return@get call.respond(HttpStatusCode.NoContent)
        call.respond(laps(record.car, record.id) ?: return@get call.respond(HttpStatusCode.NoContent))
    }

    get("/api/sessions/{id}/series") {
        // A live session with nothing uploaded yet (a tablet's uploads at its end): nothing to send, not a 404 (M11).
        val record = call.sessionRecord(archive, allowLive = hub) ?: return@get
        val key = archive.prepare(record.id) ?: return@get call.respond(HttpStatusCode.NoContent)
        // The key names the version and, while uploading, how far it goes: it is the content's identity.
        val tag = "\"${key.substringAfterLast('/')}\""
        call.response.header(HttpHeaders.ETag, tag)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        if (call.request.header(HttpHeaders.IfNoneMatch) == tag) return@get call.respond(HttpStatusCode.NotModified)
        call.response.header(HttpHeaders.ContentEncoding, "gzip")
        call.respondOutputStream(ContentType.Application.Json) {
            archive.readRaw(key) { it.copyTo(this) }
        }
    }
}

/** The session named in the path, or null having answered 404: a bad id never reaches a store. */
private suspend fun io.ktor.server.application.ApplicationCall.sessionRecord(
    archive: ArchiveService,
    /** With the hub, a session with no lines yet is found while it is live. */
    allowLive: LiveHub? = null,
): SessionRecord? {
    val raw = parameters["id"].orEmpty()
    val record = (if (SessionIds.isValid(raw)) archive.session(SessionIds.normalise(raw)) else null)
        ?.takeIf { it.ackedThrough >= 0 || (allowLive != null && allowLive.status(it.car).liveSession() == it.id) }
    if (record == null) respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    return record
}

/**
 * The exact log (M7.3), admin-only (Sam): the completed file as stored, or a
 * session's segments zipped as they stream. The VIN is in it, as it should be.
 */
suspend fun io.ktor.server.application.ApplicationCall.downloadSession(archive: ArchiveService, record: SessionRecord) {
    response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${record.id}.jsonl.gz\"")
    respondOutputStream(ContentType.parse("application/gzip")) {
        if (record.complete) {
            archive.readRaw(ArchiveService.sessionKey(record.id)) { it.copyTo(this) }
        } else {
            GZIPOutputStream(this).let { gz ->
                archive.read(record) { it.copyTo(gz) }
                gz.finish()
            }
        }
    }
}

/**
 * Newest first; a session starting within [gap] of the drive's end joins it.
 * **Test data is never part of a drive** (§21): each fake session stands alone,
 * and doesn't bridge the real ones either side of it (M11).
 */
fun drives(sessions: List<SessionItem>, gap: Duration = DRIVE_GAP): List<Drive> {
    val drives = mutableListOf<MutableList<SessionItem>>()
    val (fake, real) = sessions.partition { it.source == FAKE }
    for (s in real.sortedBy { it.started }) {
        val current = drives.lastOrNull()
        if (current != null && s.started - current.maxOf { it.ended } < gap.toMillis()) current += s else drives += mutableListOf(s)
    }
    fake.forEach { drives += mutableListOf(it) }
    return drives.map { d -> Drive(d.minOf { it.started }, d.maxOf { it.ended }, d.sortedByDescending { it.started }) }
        .sortedByDescending { it.started }
}

/** The `source` of a session of invented readings (§21). */
const val FAKE: String = "fake"

val DRIVE_GAP: Duration = Duration.ofMinutes(10)

private fun LapInfo.view() = LapView(lap, time)
