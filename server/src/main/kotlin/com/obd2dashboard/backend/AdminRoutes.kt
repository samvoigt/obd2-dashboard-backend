package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.CarAdmin
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Role
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.Who
import com.obd2dashboard.backend.admin.normalEmail
import com.obd2dashboard.backend.admin.CarHasSessions
import com.obd2dashboard.backend.admin.NoSuchSession
import com.obd2dashboard.backend.admin.SessionBusy
import com.obd2dashboard.backend.archive.SessionIds
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.CarStatus
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.registry.Car
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.SlugCheck
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class AdminConfigView(val enabled: Boolean, val googleClientId: String?, val dev: Boolean, val devUser: Boolean = false)

@Serializable
data class AdminSignInRequest(val credential: String)

/** Who's signed in (M23): `master` (the allowlist) or `user` (invited). */
@Serializable
data class AdminMe(val email: String, val role: String)

/** A role as the API writes it: `master` or `user`. */
val Role.wire: String get() = name.lowercase()

/** Every admin action is logged: who, what, to what. Never a token or a passcode (M6). */
internal val adminLog = LoggerFactory.getLogger("admin")

private fun ApplicationCall.fromOurPage(): Boolean = isSameOrigin(request.header(HttpHeaders.Origin), request.header(HttpHeaders.Host))

/** Admin sign-in (M6.3): Google's ID token in, a 30-day cookie out. */
fun Route.adminSignInRoutes(auth: AdminAuth, config: AdminConfig) {
    get("/api/admin/config") {
        call.respond(AdminConfigView(config.enabled, config.googleClientId, config.dev, config.devUser))
    }

    post("/api/admin/login") {
        val identity = config.identity
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("not_configured", "Signing in is not set up yet."))
        if (!call.fromOurPage()) return@post call.respond(HttpStatusCode.Forbidden, ApiError("origin", "Changes must come from this site."))
        when (val result = identity.verify(call.receive<AdminSignInRequest>().credential)) {
            is SignIn.Allowed -> {
                // A master admin (the allowlist) or an invited user (M23); any other Google account is refused.
                val email = result.email.normalEmail()
                val role = when {
                    config.allowlist.allows(email) -> Role.MASTER
                    call.application.gate.users.get(email) != null -> Role.USER
                    else -> null
                }
                if (role == null) {
                    adminLog.info("sign-in refused: {}", Refusal.NotAllowed)
                    return@post call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "That Google account can't use this page."))
                }
                adminLog.info("sign-in: {} ({})", email, role.wire)
                call.response.cookies.append(cookie(auth.issue(email), AdminAuth.LIFETIME.seconds.toInt()))
                call.respond(AdminMe(email, role.wire))
            }
            is SignIn.Refused -> {
                adminLog.info("sign-in refused: {}", result.reason)
                val message = if (result.reason == Refusal.NotAllowed) {
                    "That Google account can't use this page."
                } else {
                    "Google's sign-in could not be checked; try again."
                }
                call.respond(HttpStatusCode.Unauthorized, ApiError("auth", message))
            }
        }
    }

    delete("/api/admin/login") {
        if (!call.fromOurPage()) return@delete call.respond(HttpStatusCode.Forbidden, ApiError("origin", "Changes must come from this site."))
        call.response.cookies.append(cookie("", 0))
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/admin/me") {
        val who = call.signedIn(change = false) ?: return@get
        call.respond(AdminMe(who.email, who.role.wire))
    }
}

/** HttpOnly, Secure, SameSite=Strict, and sent only to the admin API. */
private fun cookie(value: String, maxAge: Int) = Cookie(
    name = AdminAuth.COOKIE,
    value = value,
    maxAge = maxAge,
    path = "/api/admin",
    secure = true,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

/** A car as the admin page sees it: never a token or a hash, only the token's hint. */
@Serializable
data class AdminCar(
    val slug: String,
    val name: String,
    val tokenHint: String,
    /** Epoch milliseconds. */
    val tokenIssued: Long,
    val passcodeSet: Boolean,
    /** The landing page's live state: `live`, `stale`, `no_session` or `offline`. */
    val state: String,
    val sessions: Int,
    /** The session its tablet is streaming now, if any (M6.7). */
    val liveSession: String? = null,
    /** How far its tablet's clock is behind the server's, in ms (ahead if negative), from live batches (M11). */
    val clockOffsetMs: Long? = null,
    /** Who made it and edits it, and whether the caller may share or delete it (M23). */
    val access: AccessView? = null,
)

/** A session as the admin page lists it (M6.7): never its VIN. */
@Serializable
data class AdminSession(
    val id: String,
    /** Epoch milliseconds: the session's own start, else when its record was made. */
    val started: Long,
    val lines: Long,
    /** `live`, `uploading`, `complete` or `incomplete`. */
    val state: String,
    /** What the admin or the crew called it (M18.3). */
    val name: String? = null,
)

@Serializable
data class AddCarRequest(val slug: String, val name: String, val token: String? = null)

/** [token] is the generated token, present exactly once: in this response. */
@Serializable
data class CarWithToken(val car: AdminCar, val token: String? = null)

@Serializable
data class RenameRequest(val name: String)

@Serializable
data class TokenRequest(val token: String? = null)

@Serializable
data class PasscodeRequest(val passcode: String)

@Serializable
data class Removed(val slug: String, val messages: Int)

/**
 * The cars API (M6.4): thin calls to [CarRegistry] and [CarAdmin], the same rules
 * `admin.sh` uses. Every change is logged with who made it, never with a secret.
 */
fun Route.adminCarRoutes(
    registry: CarRegistry,
    carAdmin: CarAdmin,
    archive: ArchiveService,
    hub: LiveHub,
    clock: Clock,
    auth: AdminAuth,
    config: AdminConfig,
) {
    suspend fun view(car: Car, access: AccessView? = null): AdminCar {
        val status = hub.status(car.slug.value)
        return AdminCar(
            slug = car.slug.value,
            name = car.name,
            tokenHint = car.tokenHint,
            tokenIssued = car.tokenIssued.toEpochMilli(),
            passcodeSet = car.passcodeHash != null,
            state = status.freshness(clock.instant()).wire,
            sessions = archive.sessionsOf(car.slug.value).size,
            liveSession = status.liveSession(),
            clockOffsetMs = status.clockOffset?.toMillis(),
            access = access,
        )
    }

    /** [car] for [who], with what they may do to it; null if it isn't theirs to edit (M23). */
    suspend fun ApplicationCall.viewFor(who: Who, car: Car): AdminCar? {
        val access = accessView(who, application.gate.access.get(car.thing())) ?: return null
        return view(car, access)
    }

    /** Whether [id] is being streamed by its car's tablet right now. */
    suspend fun isLive(car: String, id: String) = hub.status(car).liveSession() == id

    fun sessionView(record: SessionRecord, live: String?): AdminSession = AdminSession(
        record.id,
        sessionStarted(record).toEpochMilli(),
        record.ackedThrough + 1,
        sessionState(record, live, clock.instant()),
        record.name,
    )

    suspend fun ApplicationCall.pathSlug(): Slug? =
        (Slug.check(parameters["slug"].orEmpty()) as? SlugCheck.Ok)?.slug
            ?: run { respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car.")); null }

    // Only the cars the caller may edit (M23), each with what else they may do to it.
    get("/api/admin/cars") {
        val who = call.signedIn(change = false) ?: return@get
        val records = call.application.gate.access.all()
        call.respond(registry.list().mapNotNull { car -> accessView(who, records[car.thing()])?.let { view(car, it) } })
    }

    post("/api/admin/cars") {
        val who = call.may(Action.CREATE, null) ?: return@post
        val request = call.receive<AddCarRequest>()
        call.refusals {
            val slug = Slug.parse(request.slug.trim())
            val token = carAdmin.addCar(slug, request.name, request.token)
            val car = registry.get(slug)!!
            call.application.gate.created(car.thing(), who)
            adminLog.info("car added: {} by {} ({} token)", slug, who.email, if (token == null) "chosen" else "generated")
            call.respond(HttpStatusCode.Created, CarWithToken(call.viewFor(who, car)!!, token))
        }
    }

    patch("/api/admin/cars/{slug}") {
        val slug = call.pathSlug() ?: return@patch
        val who = call.may(Action.EDIT, slug.thing()) ?: return@patch
        val name = call.receive<RenameRequest>().name
        call.refusals {
            registry.rename(slug, name)
            adminLog.info("car renamed: {} by {}", slug, who.email)
            call.respond(call.viewFor(who, registry.get(slug)!!)!!)
        }
    }

    post("/api/admin/cars/{slug}/token") {
        val slug = call.pathSlug() ?: return@post
        val who = call.may(Action.EDIT, slug.thing()) ?: return@post
        val chosen = call.receive<TokenRequest>().token
        call.refusals {
            val generated = if (chosen == null) {
                registry.rotateToken(slug).token
            } else {
                registry.setToken(slug, chosen)
                null
            }
            adminLog.info("token replaced: {} by {} ({})", slug, who.email, if (chosen == null) "generated" else "chosen")
            call.respond(CarWithToken(call.viewFor(who, registry.get(slug)!!)!!, generated))
        }
    }

    put("/api/admin/cars/{slug}/passcode") {
        val slug = call.pathSlug() ?: return@put
        val who = call.may(Action.EDIT, slug.thing()) ?: return@put
        val passcode = call.receive<PasscodeRequest>().passcode.toCharArray()
        call.refusals {
            // PBKDF2 is ~0.3 s of CPU: off the thread that serves requests.
            try {
                withContext(Dispatchers.Default) { registry.setPasscode(slug, passcode) }
            } finally {
                passcode.fill('\u0000')
            }
            adminLog.info("passcode set: {} by {}", slug, who.email)
            call.respond(call.viewFor(who, registry.get(slug)!!)!!)
        }
    }

    get("/api/admin/cars/{slug}/sessions") {
        val slug = call.pathSlug() ?: return@get
        call.may(Action.EDIT, slug.thing()) ?: return@get
        registry.get(slug) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        val live = hub.status(slug.value).liveSession()
        call.respond(archive.sessionsOf(slug.value).map { sessionView(it, live) }.sortedByDescending { it.started })
    }

    get("/api/admin/sessions/{id}/download") {
        // A car's sessions are its editors' to download (M23); asked once the session's car is known.
        call.signedIn(change = false) ?: return@get
        val raw = call.parameters["id"].orEmpty()
        val record = (if (SessionIds.isValid(raw)) archive.session(SessionIds.normalise(raw)) else null)?.takeIf { it.ackedThrough >= 0 }
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, record.car)) ?: return@get
        adminLog.info("session downloaded: {} of {} by {}", record.id, record.car, who.email)
        call.downloadSession(archive, record)
    }

    delete("/api/admin/sessions/{id}") {
        // Deleting a car's sessions is its creator's, or a master admin's (Sam, M23).
        call.signedIn(change = true) ?: return@delete
        val id = call.parameters["id"].orEmpty().lowercase()
        val record = archive.session(id)
            ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        val email = (call.may(Action.DELETE, Thing(Kind.CAR, record.car)) ?: return@delete).email
        try {
            // Live first: a live session is usually uploading too, and "live" is the reason that matters.
            carAdmin.deleteSession(id, live = isLive(record.car, id))
            adminLog.info("session deleted: {} of {} by {}", id, record.car, email)
            call.respond(HttpStatusCode.NoContent)
        } catch (_: NoSuchSession) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session."))
        } catch (e: SessionBusy) {
            val message = if (e.live) {
                "That session is live: its tablet is still sending. Delete it once the drive has ended."
            } else {
                "That session is still uploading. Try again once it has been quiet for 5 minutes."
            }
            call.respond(HttpStatusCode.Conflict, ApiError("busy", message))
        }
    }

    delete("/api/admin/cars/{slug}") {
        val slug = call.pathSlug() ?: return@delete
        val who = call.may(Action.DELETE, slug.thing()) ?: return@delete
        call.refusals {
            val messages = carAdmin.removeCar(slug)
            call.application.gate.deleted(slug.thing())
            adminLog.info("car removed: {} by {} ({} messages)", slug, who.email, messages)
            call.respond(Removed(slug.value, messages))
        }
    }
}

/** A car as the access records key it (M23). */
private fun Slug.thing(): Thing = Thing(Kind.CAR, value)

private fun Car.thing(): Thing = slug.thing()

/** The registry's refusals as statuses, keeping its plain words: bad input 400, unknown 404, a conflict 409. */
private suspend fun ApplicationCall.refusals(action: suspend () -> Unit) {
    try {
        action()
    } catch (e: RegistryException) {
        val status = when (e) {
            is RegistryException.NoSuchCar -> HttpStatusCode.NotFound
            is RegistryException.CarExists, is RegistryException.TokenInUse -> HttpStatusCode.Conflict
            else -> HttpStatusCode.BadRequest
        }
        respond(status, ApiError("refused", e.message.orEmpty().replaceFirstChar(Char::uppercase)))
    } catch (e: CarHasSessions) {
        respond(
            HttpStatusCode.Conflict,
            ApiError("has_sessions", "${e.slug} still has ${e.count} session(s). Delete them first."),
        )
    }
}
