package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex
import com.obd2dashboard.backend.archive.gcp.GcsSegmentStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import java.time.Clock
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.engine.EngineConnectorBuilder
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
 * `GCP_PROJECT` and `SESSIONS_BUCKET` are required. A server started without
 * them could find no car and store no session, so it would accept no tablet at
 * all, and that should fail at deploy rather than in the paddock. The project is always named, never guessed: the
 * owner's `gcloud` default is an unrelated project.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val project = requiredEnv("GCP_PROJECT")
    val registry = CarRegistry(FirestoreCarStore.connect(project))
    val archive = ArchiveService(
        FirestoreSessionIndex.connect(project),
        GcsSegmentStore.connect(project, requiredEnv("SESSIONS_BUCKET")),
    )
    val server = embeddedServer(
        Netty,
        configure = {
            connectors.add(EngineConnectorBuilder().apply { this.port = port; host = "0.0.0.0" })
            // Cloud Run allows 10 s after SIGTERM: time for every socket's 1012 to go out.
            shutdownGracePeriod = 2_000
            shutdownTimeout = 8_000
        },
    ) { module(registry, archive, InMemoryLiveHub()) }
    server.start(wait = true)
}

private fun requiredEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set")

fun Application.module(
    registry: CarRegistry,
    archive: ArchiveService,
    hub: LiveHub,
    live: LiveConfig = LiveConfig(),
    clock: Clock = Clock.systemUTC(),
) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(Authentication) { carTokens(registry) }
    installLive(hub)

    routing {
        // Not /healthz: Cloud Run's front end reserves paths ending in "z" and
        // answers them with its own 404 before the request reaches us.
        get("/health") { call.respond(Health(status = "ok")) }

        // The landing page's list: public, so named fields only (PublicCar), never a Car.
        get("/api/cars") {
            call.respond(registry.list().map { PublicCar(it.slug.value, it.name) })
        }

        // Outside `authenticate`: the socket authenticates after the upgrade, so it can refuse with a frame.
        liveRoutes(registry, archive, hub, live, clock)

        authenticate(CAR_AUTH) {
            // Which car a token belongs to. A backend diagnostic, not in the contract.
            get("/v1/whoami") {
                val car = call.principal<CarPrincipal>()!!
                call.respond(PublicCar(car.slug, car.name))
            }
            archiveRoutes(archive)
        }
    }
}

@Serializable
data class Health(val status: String)
