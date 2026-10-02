package com.obd2dashboard.backend.admin

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Who may do what (M23). Two roles: **master admins**, the `admin-emails`
 * allowlist, who may do anything; and **users**, invited by a master admin,
 * who create things and choose who else edits them. Only a thing's creator or
 * a master admin deletes it, or chooses its editors (Sam, 2026-10-01). Pure,
 * so the site's server and `admin.sh` can never disagree.
 */
public enum class Role { MASTER, USER }

/** Someone signed in: their email, lower case, and their role. */
public data class Who(val email: String, val role: Role)

/** The kinds of thing a user creates and shares. */
public enum class Kind {
    CAR, EVENT, COURSE, DRIVER;

    /** As it's written in an access record's key and the API's paths: `car`. */
    public val key: String get() = name.lowercase()

    public companion object {
        public fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
    }
}

/** One car, event, course or driver: `car:outback`. */
public data class Thing(val kind: Kind, val id: String) {
    val key: String get() = "${kind.key}:$id"

    override fun toString(): String = key

    public companion object {
        public fun parse(key: String): Thing? {
            val kind = Kind.of(key.substringBefore(':', "")) ?: return null
            val id = key.substringAfter(':', "")
            return if (id.isEmpty()) null else Thing(kind, id)
        }
    }
}

/** A thing's creator, and the users they chose to edit it. Emails lower case. */
public data class Access(val creator: String, val editors: Set<String> = emptySet()) {
    public fun withEditor(email: String): Access = copy(editors = editors + email.normalEmail())
    public fun withoutEditor(email: String): Access = copy(editors = editors - email.normalEmail())
}

public enum class Action {
    /** Make a new car, event, course or driver. */
    CREATE,

    /** Change it: rename, its token and passcode, a course's versions, an event and its race, its sessions' names and drivers, downloads. */
    EDIT,

    /** Choose its editors. */
    SHARE,

    /** Delete it, or a car's sessions. */
    DELETE,

    /** Invite or remove users. */
    INVITE,
}

public object Permissions {
    /**
     * Whether [who] may do [action] to a thing with [access]. A thing with no
     * record was made before M23, by a master admin, so it's theirs alone.
     */
    public fun may(who: Who, action: Action, access: Access?): Boolean = when {
        who.role == Role.MASTER -> true
        action == Action.CREATE -> true
        action == Action.INVITE -> false
        access == null -> false
        action == Action.EDIT -> who.email == access.creator || who.email in access.editors
        else -> who.email == access.creator // SHARE and DELETE: the creator's
    }
}

/** An email as it's compared and stored: trimmed, lower case. */
public fun String.normalEmail(): String = trim().lowercase()

/** A user Sam invited (M23). */
public data class User(val email: String, val invitedBy: String, val invited: Instant)

/** The invited users. */
public interface UserStore {
    public suspend fun list(): List<User>

    public suspend fun get(email: String): User?

    /** Adds [user]; false if that email is already a user. */
    public suspend fun add(user: User): Boolean

    /** Removes the user; false if there was none. */
    public suspend fun remove(email: String): Boolean
}

/** Every thing's access record, one each, keyed by [Thing.key]. */
public interface AccessStore {
    public suspend fun get(thing: Thing): Access?

    /** Every record: for filtering a list, and for `admin.sh`. */
    public suspend fun all(): Map<Thing, Access>

    public suspend fun set(thing: Thing, access: Access)

    public suspend fun remove(thing: Thing)
}

public class InMemoryUserStore : UserStore {
    private val users = ConcurrentHashMap<String, User>()

    override suspend fun list(): List<User> = users.values.sortedBy { it.email }

    override suspend fun get(email: String): User? = users[email.normalEmail()]

    override suspend fun add(user: User): Boolean {
        val email = user.email.normalEmail()
        return users.putIfAbsent(email, user.copy(email = email)) == null
    }

    override suspend fun remove(email: String): Boolean = users.remove(email.normalEmail()) != null
}

public class InMemoryAccessStore : AccessStore {
    private val records = ConcurrentHashMap<Thing, Access>()

    override suspend fun get(thing: Thing): Access? = records[thing]

    override suspend fun all(): Map<Thing, Access> = records.toMap()

    override suspend fun set(thing: Thing, access: Access) {
        records[thing] = access.copy(creator = access.creator.normalEmail(), editors = access.editors.map { it.normalEmail() }.toSet())
    }

    override suspend fun remove(thing: Thing) {
        records.remove(thing)
    }
}
