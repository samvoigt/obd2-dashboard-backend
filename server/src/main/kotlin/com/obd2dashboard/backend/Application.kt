package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.AccessStore
import com.obd2dashboard.backend.admin.CarAdmin
import com.obd2dashboard.backend.admin.InMemoryAccessStore
import com.obd2dashboard.backend.admin.InMemoryUserStore
import com.obd2dashboard.backend.admin.UserStore
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.gcp.FirestoreCourseStore
import com.obd2dashboard.backend.archive.gcp.FirestoreDriverStore
import com.obd2dashboard.backend.archive.gcp.FirestoreEventStore
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
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.launch
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
            events = EventStores(FirestoreDriverStore.connect(project), FirestoreEventStore.connect(project)),
            watchRetiming = true,
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
    /** Drivers and events (M14); no default, as for courses. */
    events: EventStores,
    /** Catch up on courses' re-timing, on start and every minute (M18.2): production's and the dev server's, not tests'. */
    watchRetiming: Boolean = false,
    /** Invited users (M23). In memory unless given; production names Firestore's. */
    users: UserStore = InMemoryUserStore(),
    /** Every car's, event's, course's and driver's creator and editors (M23); as for users. */
    access: AccessStore = InMemoryAccessStore(),
) {
    val crew = CrewMessages(messages, hub, this, clock)
    val downlink = CourseDownlink(courses)
    val retiming = RetimingJobs(registry, archive, courses, this)
    if (watchRetiming) retiming.watch()
    // Sessions answered complete and not yet one object when the server stopped (M19.3): finished now.
    launch {
        runCatching { archive.unfinished() }.getOrDefault(emptyList()).forEach { id ->
            runCatching { archive.finish(id) }.onFailure { log.warn("finishing $id failed; it's read as its segments", it) }
        }
    }
    // The live run (M17.2) tells `timing` (M17.4) what changed; `timing` needs it to work out where a car stands.
    var timingDownlink: TimingDownlink? = null
    val liveTimings = LiveTimings(archive, this, clock) { car -> timingDownlink?.changed(car) }
    val timingPage = TimingPage(hub)
    val timing = TimingDownlink(CarTimings(archive, courses, events, retiming, liveTimings, hub), hub, clock, this, standing = timingPage::standing)
    timingDownlink = timing
    val resultsHold = ResultsHold(clock)
    val liveSeries = LiveSeries(archive, clock)
    // Something results and timing are made of was edited: both work it out again at once.
    val edited: () -> Unit = { timing.nudge(); resultsHold.clear() }
    val crewAuth = CrewAuth(crewKey, clock)
    val loginLimiter = LoginLimiter(clock)
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(Authentication) { carTokens(registry) }
    installLive(hub, project)
    install(SSE)
    // What a signed-in page is sent (M21): never kept by a browser or a proxy.
    install(
        createApplicationPlugin("AdminNoStore") {
            onCall { call -> if (call.request.path().startsWith("/api/admin/")) call.response.header(HttpHeaders.CacheControl, "no-store") }
        },
    )

    routing {
        // Not /healthz: Cloud Run's front end reserves paths ending in "z" and
        // answers them with its own 404 before the request reaches us.
        get("/health") { call.respond(Health(status = "ok")) }

        browserRoutes(registry, hub, clock, crewAuth, crew)
        crewRoutes(registry, crewAuth, loginLimiter)
        val adminAuth = AdminAuth(crewKey, clock)
        // Who may do what (M23): every signed-in route asks it, through `call.may` and `call.signedIn`.
        this@module.attributes.put(Gate.KEY, Gate(adminAuth, admin, users, access))
        adminSignInRoutes(adminAuth, admin)
        adminAccessRoutes()
        adminCarRoutes(registry, CarAdmin(registry, archive, messages, clock), archive, hub, clock, adminAuth, admin)
        // A course is in use once any session's laps were timed at it (its summary's track, M12.3).
        val courseInUse: suspend (String) -> Boolean = { id ->
            registry.list().any { car -> archive.sessionsOf(car.slug.value).any { it.summary?.track == id } }
        }
        adminCourseRoutes(
            courses, courseInUse, clock, adminAuth, admin,
            onChange = { downlink.changed(); edited() }, onSaved = { retiming.courseSaved(it) },
            onRemoved = { retiming.courseRemoved(it) }, retiming = retiming::progress,
        )
        adminEventRoutes(events, courses, registry, archive, clock, adminAuth, admin, onChanged = edited)
        publicCourseRoutes(courses)
        publicEventRoutes(events, courses, registry, archive, retiming, liveTimings, hub, resultsHold)
        sessionRoutes(
            registry, archive, hub, clock, laps = { car, id, on -> retiming.sessionLaps(car, id, on) }, drivers = events.drivers, events = events.events,
            messages = { car, from, to -> messages.between(car, from, to) },
            liveSeries = liveSeries, liveLaps = { car, id -> liveTimings.laps(car, id) },
        )
        messageRoutes(registry, crewAuth, crew)
        driverRoutes(events.drivers, archive, registry, crewAuth, adminAuth, admin, onChanged = edited)
        raceRoutes(events, registry, crewAuth, adminAuth, admin, clock, onChanged = edited)
        webRoutes()

        // Outside `authenticate`: the socket authenticates after the upgrade, so it can refuse with a frame.
        liveRoutes(registry, archive, hub, crew, live, clock, downlink, liveTimings, timing)

        authenticate(CAR_AUTH) {
            // Which car a token belongs to. A backend diagnostic, not in the contract.
            get("/v1/whoami") {
                val car = call.principal<CarPrincipal>()!!
                call.respond(PublicCar(car.slug, car.name))
            }
            archiveRoutes(archive, prepared = retiming::sessionPrepared, completed = { car, id -> liveTimings.completed(car, id); liveSeries.completed(id) })
            tabletCourseRoutes(courses)
        }
    }
}

@Serializable
data class Health(val status: String)
