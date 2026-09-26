package com.obd2dashboard.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Cloud Run tells the container which port to listen on through `PORT`, and
 * expects `0.0.0.0` rather than loopback. 8080 is its default, so local runs and
 * the container agree without configuration.
 *
 * `TABLET_API_KEY` is required: a server that started without it would accept
 * no tablet at all, and that should fail at deploy rather than in the paddock.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val tabletKey = System.getenv("TABLET_API_KEY")
        ?.takeIf { it.isNotBlank() }
        ?: error("TABLET_API_KEY is not set")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(tabletKey) }
        .start(wait = true)
}

fun Application.module(tabletKey: String) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    installTabletAuth(tabletKey)

    routing {
        get("/healthz") { call.respond(Health(status = "ok")) }

        authenticate(TABLET_AUTH) {
            // Lets the app's settings screen check a pasted key before relying on it.
            get("/tablet/ping") { call.respond(Health(status = "ok")) }
        }
    }
}

@Serializable
data class Health(val status: String)
