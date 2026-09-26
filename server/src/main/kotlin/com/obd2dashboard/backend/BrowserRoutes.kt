package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.BrowserEvent
import com.obd2dashboard.backend.live.CarStatus
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.LiveSnapshot
import com.obd2dashboard.backend.live.LiveUpdate
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.SlugCheck
import com.obd2dashboard.backend.registry.Slug
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A car on the landing page: public, so named fields only (never a `Car`), and its live state. */
@Serializable
data class CarSummary(val slug: String, val name: String, val state: String)

/**
 * The website's data: the landing page's list, and each car's live stream.
 * Public (decision 11). **Nothing here can carry a VIN**: records reach the hub
 * with it already removed (M4.1), and this adds no field that could hold one.
 */
fun Route.browserRoutes(registry: CarRegistry, hub: LiveHub, clock: Clock) {
    get("/api/cars") {
        val now = clock.instant()
        call.respond(registry.list().map { CarSummary(it.slug.value, it.name, hub.status(it.slug.value).freshness(now).wire) })
    }

    route("/api/cars/{slug}/live") {
        // The 404 must come before the stream starts, or the status is already sent.
        install(
            createRouteScopedPlugin("KnownCar") {
                onCall { call ->
                    val slug = call.parameters["slug"].orEmpty()
                    val known = (Slug.check(slug) as? SlugCheck.Ok)?.let { registry.get(it.slug) } != null
                    if (!known) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No car \"$slug\"."))
                }
            },
        )
        sse {
            val slug = call.parameters["slug"]!!
            val keepAlive = launch {
                // Proxies close a quiet stream; a comment every 15 s is not an event to the page.
                while (true) {
                    delay(15.seconds)
                    send(ServerSentEvent(comments = "keep-alive"))
                }
            }
            try {
                hub.subscribe(slug).collect { send(encode(it, clock)) }
            } finally {
                keepAlive.cancel()
            }
        }
    }
}

private suspend fun ServerSSESession.send(event: Pair<String, JsonObject>) =
    send(ServerSentEvent(data = event.second.toString(), event = event.first))

/** One hub event as an SSE `event:` name and its JSON. Every one carries `serverNow` for the page's clock offset. */
internal fun encode(event: BrowserEvent, clock: Clock): Pair<String, JsonObject> {
    val now = clock.instant()
    return when (event) {
        is BrowserEvent.Snapshot -> "snapshot" to snapshotJson(event.snapshot, now.toEpochMilli(), clock)
        is BrowserEvent.Update -> when (val u = event.update) {
            is LiveUpdate.SessionStarted -> "session" to buildJsonObject {
                put("serverNow", now.toEpochMilli())
                put("session", u.header)
                put("signals", u.signals ?: JsonArray(emptyList()))
            }
            is LiveUpdate.Records -> "records" to buildJsonObject {
                put("serverNow", now.toEpochMilli())
                put("atMs", u.at.toEpochMilli())
                put("records", JsonArray(u.records))
            }
            is LiveUpdate.Status -> "status" to statusJson(u.status, now.toEpochMilli(), clock)
        }
    }
}

private fun snapshotJson(s: LiveSnapshot, nowMs: Long, clock: Clock) = buildJsonObject {
    put("serverNow", nowMs)
    put("status", statusJson(s.status, nowMs, clock))
    put("session", s.header ?: JsonPrimitive(null as String?))
    put("signals", s.signals ?: JsonArray(emptyList()))
    put("latest", JsonArray(s.latest))
    put("stopped", JsonArray(s.stopped))
    put("fault", s.fault ?: JsonPrimitive(null as String?))
    put(
        "history",
        buildJsonArray {
            s.history.forEach { add(buildJsonObject { put("atMs", it.at.toEpochMilli()); put("record", it.record) }) }
        },
    )
}

/** State and age, not a wall time: the page adds its own elapsed time (M4.2). */
private fun statusJson(status: CarStatus, nowMs: Long, clock: Clock) = buildJsonObject {
    put("serverNow", nowMs)
    put("state", status.freshness(clock.instant()).wire)
    status.lastDataAt?.let { put("lastDataAgoMs", Duration.between(it, clock.instant()).toMillis()) }
}
