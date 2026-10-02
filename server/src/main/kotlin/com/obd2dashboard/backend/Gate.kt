package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.AccessStore
import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Permissions
import com.obd2dashboard.backend.admin.Role
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.UserStore
import com.obd2dashboard.backend.admin.Who
import com.obd2dashboard.backend.admin.normalEmail
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/**
 * Who's signed in, and what they may do (M23): the one check every signed-in
 * route makes. A master admin is on the allowlist (decision 25); a user was
 * invited, and is in [users]; anyone else isn't signed in, whatever their
 * cookie says. Both are checked again on every request, so removing someone
 * ends their sign-in at once.
 */
class Gate(
    val auth: AdminAuth,
    val config: AdminConfig,
    val users: UserStore,
    val access: AccessStore,
) {
    /** Who [cookie] belongs to now, or null. */
    suspend fun who(cookie: String?): Who? {
        val email = auth.emailOf(cookie)?.normalEmail() ?: return null
        return when {
            config.allowlist.allows(email) -> Who(email, Role.MASTER)
            users.get(email) != null -> Who(email, Role.USER)
            else -> null
        }
    }

    /** Whether [who] may do [action] to [thing] (null: creating something new). */
    suspend fun may(who: Who, action: Action, thing: Thing?): Boolean =
        Permissions.may(who, action, thing?.let { access.get(it) })

    /** [thing] was just made by [who]: its creator, for good. */
    suspend fun created(thing: Thing, who: Who) = access.set(thing, Access(who.email))

    /** [thing] was deleted: its record goes with it. */
    suspend fun deleted(thing: Thing) = access.remove(thing)

    companion object {
        val KEY = AttributeKey<Gate>("Gate")
    }
}

/** The application's gate, set once by `module`. */
val Application.gate: Gate get() = attributes[Gate.KEY]

/**
 * Who's signed in, or null having answered: `403` for a change not from our
 * own site, `401` without a sign-in. For a route anyone signed in may use.
 */
suspend fun ApplicationCall.signedIn(change: Boolean): Who? {
    if (change && !isSameOrigin(request.header(HttpHeaders.Origin), request.header(HttpHeaders.Host))) {
        respond(HttpStatusCode.Forbidden, ApiError("origin", "Changes must come from this site."))
        return null
    }
    val who = application.gate.who(request.cookies[AdminAuth.COOKIE])
    if (who == null) respond(HttpStatusCode.Unauthorized, ApiError("auth", "Sign in first."))
    return who
}

/**
 * Who's signed in, if they may do [action] to [thing] (null: creating
 * something new); else null having answered: `403` "not yours" (or the
 * origin), `401` without a sign-in. Reads are [Action.EDIT] too: what's not
 * yours to change isn't yours to see through the admin API.
 */
suspend fun ApplicationCall.may(action: Action, thing: Thing?): Who? {
    val who = signedIn(change = action != Action.EDIT || request.local.method.value != "GET") ?: return null
    if (!application.gate.may(who, action, thing)) {
        respond(HttpStatusCode.Forbidden, ApiError("not_allowed", notAllowed(action, thing)))
        return null
    }
    return who
}

private fun notAllowed(action: Action, thing: Thing?): String {
    val what = thing?.let { "${it.kind.key} ${it.id}" } ?: "that"
    return when (action) {
        Action.DELETE -> "Only whoever made $what, or a master admin, can delete it."
        Action.SHARE -> "Only whoever made $what, or a master admin, can choose who edits it."
        Action.INVITE -> "Only a master admin can invite or remove users."
        else -> "You can't change $what. Ask whoever made it to add you."
    }
}
