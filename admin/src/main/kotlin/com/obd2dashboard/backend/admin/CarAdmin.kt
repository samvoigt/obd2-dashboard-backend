package com.obd2dashboard.backend.admin

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens
import java.time.Clock
import java.time.Duration

/**
 * The owner's operations that span a car, its sessions and its messages: one
 * place for the rule, called by `admin.sh` and by the admin page (M6.1).
 */
public class CarAdmin(
    private val registry: CarRegistry,
    private val archive: ArchiveService,
    private val messages: Messages,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Registers a car. With [chosenToken] (decision 24), its generated token is
     * replaced before anyone sees it, and if that token is refused, the car is
     * removed again, so a refusal leaves nothing behind. Returns the generated
     * token, or null when one was chosen.
     */
    public suspend fun addCar(slug: Slug, name: String, chosenToken: String? = null): String? {
        if (chosenToken == null) return registry.addCar(slug, name).token
        Tokens.problemWith(chosenToken)?.let { throw RegistryException.InvalidToken(it) }
        registry.addCar(slug, name)
        try {
            registry.setToken(slug, chosenToken)
        } catch (e: RegistryException) {
            registry.removeCar(slug)
            throw e
        }
        return null
    }

    /**
     * Refuses a car that does not exist, or still has sessions: they are kept
     * until the owner deletes them (decision 16), and a removal must not orphan
     * them silently.
     */
    public suspend fun checkRemovable(slug: Slug) {
        registry.get(slug) ?: throw RegistryException.NoSuchCar(slug)
        val count = archive.sessionsOf(slug.value).size
        if (count > 0) throw CarHasSessions(slug, count)
    }

    /**
     * Removes the car, so its token stops working at once, then its messages,
     * which were a record for its sessions (M5.8). Returns how many messages went.
     */
    /**
     * Refuses a session that is unknown, or that its tablet is still sending: live
     * on it ([live], which only the server knows), or with an incomplete upload
     * active within [UPLOAD_QUIET]. Deleting one of those wouldn't stick: its next
     * chunk gets `not_open`, and the tablet re-sends it from line 0 (contract §6).
     * Returns the record.
     */
    public suspend fun checkDeletable(id: String, live: Boolean = false): SessionRecord {
        val record = archive.session(id) ?: throw NoSuchSession(id)
        if (live) throw SessionBusy(id, live = true)
        val quietFor = Duration.between(record.updated, clock.instant())
        if (!record.complete && quietFor < UPLOAD_QUIET) throw SessionBusy(id, live = false)
        return record
    }

    /** Deletes a session's data and record (decision 16), if [checkDeletable] allows. */
    public suspend fun deleteSession(id: String, live: Boolean = false) {
        checkDeletable(id, live)
        archive.delete(id)
    }

    public suspend fun removeCar(slug: Slug): Int {
        checkRemovable(slug)
        registry.removeCar(slug)
        return messages.deleteCar(slug.value)
    }

    public companion object {
        /** How long an incomplete upload must be quiet before it may be deleted. */
        public val UPLOAD_QUIET: Duration = Duration.ofMinutes(5)
    }
}

/** A session is being sent right now, so deleting it would not stick. */
public class SessionBusy(public val id: String, public val live: Boolean) :
    Exception(if (live) "session $id is live" else "session $id is still uploading")

/** No session has that id. */
public class NoSuchSession(public val id: String) : Exception("no session $id")

/** A car still has [count] sessions, so it cannot be removed yet. */
public class CarHasSessions(public val slug: Slug, public val count: Int) :
    Exception("$slug has $count session(s)")
