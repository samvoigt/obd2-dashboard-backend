package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.RegistryException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.response.header
import io.ktor.server.response.respond

const val CAR_AUTH = "car"

/** The car a request's token belongs to (contract §8: a token belongs to a car, not a tablet). */
data class CarPrincipal(val slug: String, val name: String)

/**
 * The car [token] belongs to, or null.
 *
 * **The one place a token becomes a car**, shared by the HTTP provider below and
 * by the live WebSocket (M4), which must authenticate *after* accepting the
 * upgrade so it can answer with an `error`/`auth` frame (contract §5.2).
 * No cache: the registry asks the store every time, so a rotated token fails
 * on the very next request.
 */
suspend fun CarRegistry.principalFor(token: String): CarPrincipal? =
    authenticate(token)?.let { CarPrincipal(it.slug.value, it.name) }

/**
 * The bearer token from [call], or null if there is none.
 *
 * Parsed by hand, not with Ktor's header parser, which throws on some malformed
 * headers: anything that is not `Bearer <something>` is simply no token.
 */
internal fun bearerToken(call: ApplicationCall): String? {
    val header = call.request.headers[HttpHeaders.Authorization] ?: return null
    val (scheme, rest) = header.trim().split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 } ?: return null
    return rest.trim().takeIf { scheme.equals("Bearer", ignoreCase = true) && it.isNotEmpty() }
}

/**
 * Authenticates by car token, answering a failure with a `401` and the
 * contract's error body, which Ktor's own bearer provider cannot do.
 */
class CarAuthProvider(config: Config) : AuthenticationProvider(config) {
    private val registry = config.registry

    class Config(name: String, val registry: CarRegistry) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val token = bearerToken(context.call)
        val principal = try {
            token?.let { registry.principalFor(it) }
        } catch (e: RegistryException.DuplicateToken) {
            // Corruption: answering as either car would be a guess about whose data this is.
            context.call.application.log.error("Refusing a request: ${e.message}")
            context.challenge(CAR_AUTH, AuthenticationFailedCause.Error(e.message.orEmpty())) { challenge, call ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError("server", "The server cannot tell which car this token belongs to. Try again later."),
                )
                challenge.complete()
            }
            return
        }
        if (principal != null) {
            context.principal(name, principal)
            return
        }
        val (cause, error) = if (token == null) {
            AuthenticationFailedCause.NoCredentials to
                ApiError("auth", "No token. Send the car's token as Authorization: Bearer <token>.")
        } else {
            AuthenticationFailedCause.InvalidCredentials to
                ApiError("auth", "This token is not recognised. It may have been rotated; ask for a new one.")
        }
        context.challenge(CAR_AUTH, cause) { challenge, call ->
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"car\"")
            call.respond(HttpStatusCode.Unauthorized, error)
            challenge.complete()
        }
    }
}

fun AuthenticationConfig.carTokens(registry: CarRegistry) {
    register(CarAuthProvider(CarAuthProvider.Config(CAR_AUTH, registry)))
}
