package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
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
 * `GCP_PROJECT` is required. A server started without it could not find any
 * car, so it would accept no tablet at all, and that should fail at deploy
 * rather than in the paddock. The project is always named, never guessed: the
 * owner's `gcloud` default is an unrelated project.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val registry = CarRegistry(FirestoreCarStore.connect(requiredEnv("GCP_PROJECT")))
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(registry) }
        .start(wait = true)
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set")

fun Application.module(registry: CarRegistry) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(Authentication) { carTokens(registry) }

    routing {
        // Not /healthz: Cloud Run's front end reserves paths ending in "z" and
        // answers them with its own 404 before the request reaches us.
        get("/health") { call.respond(Health(status = "ok")) }

        // The landing page's list: public, so named fields only (PublicCar), never a Car.
        get("/api/cars") {
            call.respond(registry.list().map { PublicCar(it.slug.value, it.name) })
        }

        authenticate(CAR_AUTH) {
            // Which car a token belongs to. A backend diagnostic, not in the contract.
            get("/v1/whoami") {
                val car = call.principal<CarPrincipal>()!!
                call.respond(PublicCar(car.slug, car.name))
            }
        }
    }
}

@Serializable
data class Health(val status: String)
