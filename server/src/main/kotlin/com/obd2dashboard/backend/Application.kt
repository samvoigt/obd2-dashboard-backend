package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.CarAdmin
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.gcp.FirestoreCourseStore
import com.obd2dashboard.backend.archive.gcp.FirestoreMessageStore
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex
import com.obd2dashboard.backend.archive.gcp.GcsSegmentStore
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.Messages
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
import io.ktor.server.sse.SSE
import kotlinx.serialization.Serializable

/**
 * Cloud Run tells the container which port to listen on through `PORT`, and
 * expects `0.0.0.0` rather than loopback. 8080 is its default, so local runs and
 * the container agree without configuration.
 *
 * `GCP_PROJECT`, `SESSIONS_BUCKET` and `CREW_COOKIE_KEY` are required. A server started without
 * them could find no car and store no session, so it would accept no tablet at
 * all, and that should fail at deploy rather than in the paddock. The project is always named, never guessed: the
 * owner's `gcloud` default is an unrelated project.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val project = requiredEnv("GCP_PROJECT")
    val crewKey = CrewAuth.keyFrom(requiredEnv("CREW_COOKIE_KEY"))
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
    ) {
        module(
            registry, archive, InMemoryLiveHub(), project = project,
            messages = Messages(FirestoreMessageStore.connect(project)), crewKey = crewKey,
            admin = AdminConfig.fromEnvironment(System::getenv),
            courses = FirestoreCourseStore.connect(project),
        )
    }
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
    /** Set in production, so the instance can drain when its revision loses traffic (M4.8a). */
    project: String? = null,
    /** Crew messages (M5), on the store production names; no default, so it is never left in memory by mistake. */
    messages: Messages,
    /** Signs crew logins (M5.4). No default: a random one would log every crew member out at each restart. */
    crewKey: ByteArray,
    /** The admin page (M6). Off unless given; production builds it from the environment. */
    admin: AdminConfig = AdminConfig.DISABLED,
    /** Courses (M12), on the store production names; no default, as for messages. */
    courses: CourseStore,
) {
    val crew = CrewMessages(messages, hub, this, clock)
    val downlink = CourseDownlink(courses)
    val retiming = RetimingJobs(registry, archive, courses, this)
    val crewAuth = CrewAuth(crewKey, clock)
    val loginLimiter = LoginLimiter(clock)
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(Authentication) { carTokens(registry) }
    installLive(hub, project)
    install(SSE)

    routing {
        // Not /healthz: Cloud Run's front end reserves paths ending in "z" and
        // answers them with its own 404 before the request reaches us.
        get("/health") { call.respond(Health(status = "ok")) }

        browserRoutes(registry, hub, clock, crewAuth, crew)
        crewRoutes(registry, crewAuth, loginLimiter)
        val adminAuth = AdminAuth(crewKey, clock)
        adminSignInRoutes(adminAuth, admin)
        adminCarRoutes(registry, CarAdmin(registry, archive, messages, clock), archive, hub, clock, adminAuth, admin)
        // A course is in use once any session's laps were timed at it (its summary's track, M12.3).
        val courseInUse: suspend (String) -> Boolean = { id ->
            registry.list().any { car -> archive.sessionsOf(car.slug.value).any { it.summary?.track == id } }
        }
        adminCourseRoutes(
            courses, courseInUse, clock, adminAuth, admin,
            onChange = downlink::changed, onSaved = { retiming.courseSaved(it) }, retiming = retiming::progress,
        )
        publicCourseRoutes(courses)
        sessionRoutes(registry, archive, hub, clock, laps = retiming::sessionLaps)
        messageRoutes(registry, crewAuth, crew)
        webRoutes()

        // Outside `authenticate`: the socket authenticates after the upgrade, so it can refuse with a frame.
        liveRoutes(registry, archive, hub, crew, live, clock, downlink)

        authenticate(CAR_AUTH) {
            // Which car a token belongs to. A backend diagnostic, not in the contract.
            get("/v1/whoami") {
                val car = call.principal<CarPrincipal>()!!
                call.respond(PublicCar(car.slug, car.name))
            }
            archiveRoutes(archive, prepared = retiming::sessionPrepared)
            tabletCourseRoutes(courses)
        }
    }
}

@Serializable
data class Health(val status: String)
