package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Duration
import kotlinx.serialization.Serializable

@Serializable
data class SendRequest(val text: String, val preset: String? = null, val ttlSeconds: Long? = null)

/** A message as the crew sees it: its state and every time it moved, which the tablet's wire form does not need. */
@Serializable
data class MessageView(
    val id: String,
    val text: String,
    val preset: String? = null,
    val state: String,
    val sentAt: Long,
    val expiresAt: Long,
    val receivedAt: Long? = null,
    val displayedAt: Long? = null,
    val endedAt: Long? = null,
    val replacedBy: String? = null,
) {
    companion object {
        fun of(m: Message) = MessageView(
            m.id, m.text, m.preset, m.state.wire, m.sentAt.toEpochMilli(), m.expiresAt.toEpochMilli(),
            m.receivedAt?.toEpochMilli(), m.displayedAt?.toEpochMilli(), m.endedAt?.toEpochMilli(), m.replacedBy,
        )
    }
}

/** The crew's messages (contract §5.4): send, clear, list. **Crew only**: `401` without a login for this car. */
fun Route.messageRoutes(registry: CarRegistry, auth: CrewAuth, crew: CrewMessages) {
    post("/api/cars/{slug}/messages") {
        val car = call.pathCar(registry) ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(auth, car)) return@post call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val request = call.receive<SendRequest>()
        val ttl = request.ttlSeconds?.let { Duration.ofSeconds(it) } ?: Messages.DEFAULT_TTL
        when (val checked = Messages.check(request.text, request.preset, ttl)) {
            is Messages.Checked.Bad -> call.respond(HttpStatusCode.BadRequest, ApiError("bad_message", checked.reason))
            is Messages.Checked.Ok -> call.respond(
                HttpStatusCode.Created,
                MessageView.of(crew.send(car.slug.value, checked.text, checked.preset, checked.ttl)),
            )
        }
    }

    delete("/api/cars/{slug}/messages/{id}") {
        val car = call.pathCar(registry) ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(auth, car)) return@delete call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        val cleared = crew.clear(car.slug.value, call.parameters["id"].orEmpty())
            ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_active", "No such active message."))
        call.respond(MessageView.of(cleared))
    }

    get("/api/cars/{slug}/messages") {
        val car = call.pathCar(registry) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        if (!call.isCrew(auth, car)) return@get call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "Log in with the crew passcode."))
        crew.expire(car.slug.value)
        call.respond(crew.messages.recent(car.slug.value).map(MessageView::of))
    }
}
