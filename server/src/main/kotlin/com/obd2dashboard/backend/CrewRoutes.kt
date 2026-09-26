package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.Car
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.Passcodes
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.SlugCheck
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val passcode: String)

@Serializable
data class CrewStatus(val crew: Boolean)

/** The car named in the path, or null (unknown, or not a slug at all). */
suspend fun ApplicationCall.pathCar(registry: CarRegistry): Car? =
    (Slug.check(parameters["slug"].orEmpty()) as? SlugCheck.Ok)?.let { registry.get(it.slug) }

/** Whether this request carries a live crew login for [car]. */
fun ApplicationCall.isCrew(auth: CrewAuth, car: Car): Boolean =
    auth.verify(request.cookies[auth.cookieName(car.slug.value)], car)

/** Crew login (decision 11): a passcode for a 30-day cookie, per car. */
fun Route.crewRoutes(registry: CarRegistry, auth: CrewAuth, limiter: LoginLimiter) {
    post("/api/cars/{slug}/login") {
        val car = call.pathCar(registry) ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        val stored = car.passcodeHash
            ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("no_passcode", "This car has no crew passcode set; the owner sets one with admin.sh."))
        limiter.waitFor(car.slug.value)?.let { wait ->
            call.response.header(HttpHeaders.RetryAfter, wait.seconds.coerceAtLeast(1).toString())
            return@post call.respond(HttpStatusCode.TooManyRequests, ApiError("too_many", "Too many wrong passcodes; try again shortly."))
        }
        val passcode = call.receive<LoginRequest>().passcode.toCharArray()
        // PBKDF2 at 600k iterations is ~0.3 s of CPU: off the thread that serves requests.
        val right = try {
            withContext(Dispatchers.Default) { Passcodes.verify(passcode, stored) }
        } finally {
            passcode.fill('\u0000')
        }
        if (!right) {
            limiter.failed(car.slug.value)
            return@post call.respond(HttpStatusCode.Unauthorized, ApiError("auth", "That passcode is not right."))
        }
        call.response.cookies.append(cookie(auth, car, auth.issue(car), CrewAuth.LIFETIME.seconds.toInt()))
        call.respond(CrewStatus(crew = true))
    }

    delete("/api/cars/{slug}/login") {
        val car = call.pathCar(registry) ?: return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        call.response.cookies.append(cookie(auth, car, "", 0))
        call.respond(CrewStatus(crew = false))
    }

    get("/api/cars/{slug}/crew") {
        val car = call.pathCar(registry) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        call.respond(CrewStatus(call.isCrew(auth, car)))
    }
}

/** HttpOnly, Secure, SameSite=Strict, and scoped to this car's paths only. */
private fun cookie(auth: CrewAuth, car: Car, value: String, maxAge: Int) = Cookie(
    name = auth.cookieName(car.slug.value),
    value = value,
    maxAge = maxAge,
    path = "/api/cars/${car.slug.value}",
    secure = true,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)
