package com.obd2dashboard.backend

import com.google.api.gax.rpc.ApiException
import com.google.cloud.BaseServiceException
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.ChunkBody
import com.obd2dashboard.backend.archive.LineBlock
import com.obd2dashboard.backend.archive.SessionIds
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.log
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readBuffer
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** `{"ackedThrough": n}`: a chunk's answer (§6.2), and a `PUT`'s too (extra, and harmless). */
@Serializable
data class Acked(val ackedThrough: Long)

/** `{"complete": true}` (§6.3). */
@Serializable
data class Completed(val complete: Boolean = true)

/**
 * A `409`. The contract shows it as `{"missingFrom": n}` (§6.2–6.3) and says every
 * archive `4xx` carries `{error, message, skipChunk}` (§6.4); this is both.
 */
@Serializable
data class Missing(val error: String, val message: String, val skipChunk: Boolean, val missingFrom: Long)

/** `/complete`'s body (§6.3). */
@Serializable
data class CompleteRequest(val lastIndex: Long, val recordCount: Long, val sha256: String)

/** Contract §6: the archive lane. Mounted inside `authenticate(CAR_AUTH)`. */
fun Route.archiveRoutes(
    archive: ArchiveService,
    /** After a completed session is prepared: its re-timing (M13.4). */
    prepared: suspend (car: String, id: String) -> Unit = { _, _ -> },
    /** The moment a session is complete: its live run is done with (M17.2). */
    completed: (car: String, id: String) -> Unit = { _, _ -> },
) {
    put("/v1/sessions/{id}") {
        archiveCall(call) { car, id ->
            val body = call.readCapped(MAX_OPEN_BYTES) ?: return@archiveCall call.tooLarge("a session record is at most $MAX_OPEN_BYTES bytes")
            when (val result = archive.open(car, id, body)) {
                is ArchiveService.Open.Created -> call.respond(HttpStatusCode.Created, Acked(result.ackedThrough))
                is ArchiveService.Open.Existing -> call.respond(HttpStatusCode.OK, Acked(result.ackedThrough))
                ArchiveService.Open.WrongCar -> call.wrongCar(id)
                is ArchiveService.Open.BadRecord -> call.badRecord(result.reason)
            }
        }
    }

    post("/v1/sessions/{id}/chunks") {
        archiveCall(call) { car, id ->
            val first = call.request.headers["X-First-Index"]?.toLongOrNull()?.takeIf { it >= 0 }
                ?: return@archiveCall call.badRecord("X-First-Index must be a line index, 0 or more")
            val gzipped = when (call.request.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()) {
                null, "", "identity" -> false
                "gzip" -> true
                else -> return@archiveCall call.badRecord("Content-Encoding must be gzip or absent")
            }
            val raw = call.readCapped(MAX_RAW_CHUNK_BYTES) ?: return@archiveCall call.tooLarge("a chunk's body is at most $MAX_RAW_CHUNK_BYTES bytes")
            val decoded = when (val d = ChunkBody.decode(raw, gzipped)) {
                is ChunkBody.Decoded.Ok -> d.bytes
                ChunkBody.Decoded.TooLarge -> return@archiveCall call.tooLarge("a chunk is at most ${ChunkBody.MAX_UNCOMPRESSED} bytes uncompressed")
                is ChunkBody.Decoded.NotGzip -> return@archiveCall call.badRecord("the body is not valid gzip")
            }
            val lines = when (val split = LineBlock.split(decoded)) {
                is LineBlock.Split.Ok -> split.lines
                LineBlock.Split.Empty -> return@archiveCall call.badRecord("a chunk has at least one line")
                LineBlock.Split.MissingFinalNewline -> return@archiveCall call.badRecord("every line, the last included, must end in \\n")
            }
            call.request.headers["X-Record-Count"]?.let { claimed ->
                if (claimed.toLongOrNull() != lines.size.toLong()) {
                    return@archiveCall call.badRecord("X-Record-Count says $claimed but the chunk has ${lines.size} lines")
                }
            }
            when (val result = archive.append(car, id, first, lines)) {
                is ArchiveService.Append.Acked -> call.respond(HttpStatusCode.OK, Acked(result.ackedThrough))
                is ArchiveService.Append.Gap -> call.missing(result.missingFrom)
                ArchiveService.Append.NotOpen -> call.notOpen(id)
                ArchiveService.Append.WrongCar -> call.wrongCar(id)
                is ArchiveService.Append.BadRecord -> call.badRecord(result.reason)
            }
        }
    }

    post("/v1/sessions/{id}/complete") {
        archiveCall(call) { car, id ->
            val body = call.readCapped(MAX_OPEN_BYTES) ?: return@archiveCall call.tooLarge("too large")
            val request = try {
                Json.decodeFromString<CompleteRequest>(body.decodeToString())
            } catch (e: SerializationException) {
                return@archiveCall call.badRecord("the body must be {\"lastIndex\", \"recordCount\", \"sha256\"}")
            } catch (e: IllegalArgumentException) {
                return@archiveCall call.badRecord("the body must be {\"lastIndex\", \"recordCount\", \"sha256\"}")
            }
            if (!SHA256.matches(request.sha256)) return@archiveCall call.badRecord("sha256 must be 64 hex digits")
            when (val result = archive.complete(car, id, request.lastIndex, request.recordCount, request.sha256)) {
                ArchiveService.Complete.Done -> {
                    completed(car, id)
                    call.respond(HttpStatusCode.OK, Completed())
                    // After the answer, so the tablet never waits on it (M7.1). A failure
                    // is only logged: the summary is built again on first view.
                    val log = call.application.log
                    call.application.launch {
                        try {
                            archive.prepare(id) // the summary and the series, in one pass (M7.2)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("preparing $id failed; it will be built on first view", e)
                            return@launch
                        }
                        try {
                            prepared(car, id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("re-timing $id failed; it is re-timed on view", e)
                        }
                    }
                }
                // Sent again (the tablet never saw the first answer, §23): the same answer, and nothing done again.
                ArchiveService.Complete.AlreadyDone -> call.respond(HttpStatusCode.OK, Completed())
                is ArchiveService.Complete.Gap -> call.missing(result.missingFrom)
                ArchiveService.Complete.NotOpen -> call.notOpen(id)
                ArchiveService.Complete.WrongCar -> call.wrongCar(id)
                is ArchiveService.Complete.BadRecord -> call.badRecord(result.reason)
            }
        }
    }
}

private const val MAX_OPEN_BYTES = 64 * 1024
private const val MAX_RAW_CHUNK_BYTES = 2 * 1024 * 1024
private val SHA256 = Regex("[0-9a-fA-F]{64}")

/**
 * The car and a checked, lower-cased session id, then [block]. A failure of the
 * stores themselves (Google's clients, I/O) is a `503` with `Retry-After`, which
 * the tablet waits out (§6.4); anything else stays a `500`, a fault to look into.
 */
private suspend fun archiveCall(call: ApplicationCall, block: suspend (car: String, id: String) -> Unit) {
    val car = call.principal<CarPrincipal>()!!.slug
    val raw = call.parameters["id"].orEmpty()
    if (!SessionIds.isValid(raw)) return call.badRecord("the session id must be a UUID")
    try {
        block(car, SessionIds.normalise(raw))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e !is BaseServiceException && e !is ApiException && e !is IOException) throw e
        call.application.log.warn("Archive storage unavailable: ${e.message}")
        call.response.header(HttpHeaders.RetryAfter, "30")
        call.respond(HttpStatusCode.ServiceUnavailable, ApiError("unavailable", "Storage is briefly unavailable; retry shortly."))
    }
}

/** The body, or null if it is longer than [limit]; never reads more than [limit] + 1 bytes. */
private suspend fun ApplicationCall.readCapped(limit: Int): ByteArray? {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > limit) return null }
    val bytes = receiveChannel().readBuffer(limit + 1L).readByteArray()
    return if (bytes.size > limit) null else bytes
}

private suspend fun ApplicationCall.badRecord(reason: String) =
    respond(HttpStatusCode.BadRequest, ApiError("bad_record", reason))

private suspend fun ApplicationCall.wrongCar(id: String) =
    respond(HttpStatusCode.BadRequest, ApiError("wrong_car", "Session $id belongs to a different car."))

private suspend fun ApplicationCall.notOpen(id: String) =
    respond(HttpStatusCode.NotFound, ApiError("not_open", "Session $id has not been opened; PUT its session record first."))

private suspend fun ApplicationCall.missing(from: Long) =
    respond(HttpStatusCode.Conflict, Missing("missing", "Lines from $from are missing; resend from there.", false, from))

private suspend fun ApplicationCall.tooLarge(reason: String) =
    respond(HttpStatusCode.PayloadTooLarge, ApiError("too_large", reason))
