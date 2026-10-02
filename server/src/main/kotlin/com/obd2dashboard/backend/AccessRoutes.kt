package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Permissions
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.admin.Who
import com.obd2dashboard.backend.admin.normalEmail
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.Clock
import kotlinx.serialization.Serializable

/**
 * A thing's access as a signed-in list shows it (M23): who made it (null:
 * before M23, a master admin), its editors, and what the caller may do
 * beyond editing it.
 */
@Serializable
data class AccessView(val creator: String?, val editors: List<String>, val canShare: Boolean, val canDelete: Boolean)

/** [access] as [who] sees it, or null if [who] may not edit it, so a list leaves it out. */
fun accessView(who: Who, access: Access?): AccessView? {
    if (!Permissions.may(who, Action.EDIT, access)) return null
    return AccessView(
        creator = access?.creator?.ifEmpty { null },
        editors = access?.editors.orEmpty().sorted(),
        canShare = Permissions.may(who, Action.SHARE, access),
        canDelete = Permissions.may(who, Action.DELETE, access),
    )
}

/** What a thing's editors are, for its creator or a master admin choosing them. */
@Serializable
data class AccessDetail(val creator: String?, val editors: List<String>, val invitable: List<String>)

@Serializable
data class SetEditors(val editors: List<String>)

/** An invited user, for a master admin: who invited them, when, and what they made. */
@Serializable
data class UserView(val email: String, val invitedBy: String, val invited: Long, val created: List<String>)

@Serializable
data class InviteRequest(val email: String)

/**
 * Sharing and users (M23.5): a thing's editors, chosen by its creator or a
 * master admin; and the invited users, managed by a master admin. Every change
 * is logged with who made it.
 */
fun Route.adminAccessRoutes(clock: Clock = Clock.systemUTC()) {
    suspend fun ApplicationCall.thing(): Thing? {
        val kind = Kind.of(parameters["kind"].orEmpty())
        val id = parameters["id"].orEmpty()
        if (kind == null || id.isEmpty()) {
            respond(HttpStatusCode.NotFound, ApiError("not_found", "No such thing to share."))
            return null
        }
        return Thing(kind, id)
    }

    get("/api/admin/access/{kind}/{id}") {
        val thing = call.thing() ?: return@get
        call.may(Action.SHARE, thing) ?: return@get
        val gate = call.application.gate
        val access = gate.access.get(thing)
        val creator = access?.creator?.ifEmpty { null }
        call.respond(
            AccessDetail(
                creator = creator,
                editors = access?.editors.orEmpty().sorted(),
                invitable = gate.users.list().map { it.email }.filter { it != creator },
            ),
        )
    }

    put("/api/admin/access/{kind}/{id}") {
        val thing = call.thing() ?: return@put
        val who = call.may(Action.SHARE, thing) ?: return@put
        val gate = call.application.gate
        val access = gate.access.get(thing)
        val editors = call.receive<SetEditors>().editors.map { it.normalEmail() }.filter { it.isNotEmpty() }.toSet()
        val problems = editors.mapNotNull { email ->
            when {
                email == access?.creator -> "$email made it, so can already edit it."
                gate.config.allowlist.allows(email) -> "$email is a master admin, who can already edit everything."
                gate.users.get(email) == null -> "$email isn't an invited user."
                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            return@put call.respond(HttpStatusCode.BadRequest, ApiError("refused", problems.joinToString(" ")))
        }
        // A thing with no record (made before M23) gets one, still a master admin's: its creator stays unknown.
        val saved = Access(access?.creator ?: "", editors)
        gate.access.set(thing, saved)
        adminLog.info("editors set: {} to {} by {}", thing, editors.sorted(), who.email)
        val creator = access?.creator?.ifEmpty { null }
        call.respond(AccessDetail(creator, editors.sorted(), gate.users.list().map { it.email }.filter { it != creator }))
    }

    get("/api/admin/users") {
        call.may(Action.INVITE, null) ?: return@get
        val gate = call.application.gate
        val records = gate.access.all()
        call.respond(
            gate.users.list().map { user ->
                UserView(
                    email = user.email,
                    invitedBy = user.invitedBy,
                    invited = user.invited.toEpochMilli(),
                    created = records.filterValues { it.creator == user.email }.keys.map { it.key }.sorted(),
                )
            },
        )
    }

    post("/api/admin/users") {
        val who = call.may(Action.INVITE, null) ?: return@post
        val gate = call.application.gate
        val email = call.receive<InviteRequest>().email.normalEmail()
        if (!looksLikeEmail(email)) return@post call.respond(HttpStatusCode.BadRequest, ApiError("refused", "That isn't an email address."))
        if (gate.config.allowlist.allows(email)) {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("refused", "$email is a master admin already."))
        }
        val user = User(email, who.email, clock.instant())
        if (!gate.users.add(user)) return@post call.respond(HttpStatusCode.Conflict, ApiError("exists", "$email is already a user."))
        adminLog.info("user invited: {} by {}", email, who.email)
        call.respond(HttpStatusCode.Created, UserView(email, who.email, user.invited.toEpochMilli(), emptyList()))
    }

    delete("/api/admin/users/{email}") {
        val who = call.may(Action.INVITE, null) ?: return@delete
        val email = call.parameters["email"].orEmpty().normalEmail()
        // What they made stays theirs (Sam can still delete it, its editors still edit it); they lose access at once.
        if (!call.application.gate.users.remove(email)) {
            return@delete call.respond(HttpStatusCode.NotFound, ApiError("not_found", "$email isn't a user."))
        }
        adminLog.info("user removed: {} by {}", email, who.email)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Enough of an email to be one: something, an @, a dotted domain, no spaces. */
private fun looksLikeEmail(email: String): Boolean = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(email)
