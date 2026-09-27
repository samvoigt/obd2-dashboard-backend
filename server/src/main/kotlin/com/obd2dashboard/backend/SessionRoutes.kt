package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.LapInfo
import com.obd2dashboard.backend.archive.SessionIds
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
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
)

/**
 * Past sessions (M7.3): public, like the live pages (decision 11), and never
 * the VIN. The prepared series is sent as stored, gzipped.
 */
fun Route.sessionRoutes(registry: CarRegistry, archive: ArchiveService, hub: LiveHub, clock: Clock) {
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
        val record = call.sessionRecord(archive) ?: return@get
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
            ),
        )
    }

    get("/api/sessions/{id}/series") {
        val record = call.sessionRecord(archive) ?: return@get
        val key = archive.prepare(record.id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "That session has no lines yet."))
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
private suspend fun io.ktor.server.application.ApplicationCall.sessionRecord(archive: ArchiveService): SessionRecord? {
    val raw = parameters["id"].orEmpty()
    val record = if (SessionIds.isValid(raw)) archive.session(SessionIds.normalise(raw)) else null
    if (record == null || record.ackedThrough < 0) respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
    return record?.takeIf { it.ackedThrough >= 0 }
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

/** Newest first; a session starting within [gap] of the drive's end joins it. */
fun drives(sessions: List<SessionItem>, gap: Duration = DRIVE_GAP): List<Drive> {
    val drives = mutableListOf<MutableList<SessionItem>>()
    for (s in sessions.sortedBy { it.started }) {
        val current = drives.lastOrNull()
        if (current != null && s.started - current.maxOf { it.ended } < gap.toMillis()) current += s else drives += mutableListOf(s)
    }
    return drives.map { d -> Drive(d.first().started, d.maxOf { it.ended }, d.sortedByDescending { it.started }) }
        .sortedByDescending { it.started }
}

val DRIVE_GAP: Duration = Duration.ofMinutes(10)

private fun LapInfo.view() = LapView(lap, time)
