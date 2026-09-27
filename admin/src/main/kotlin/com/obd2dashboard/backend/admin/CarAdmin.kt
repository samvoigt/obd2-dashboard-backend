package com.obd2dashboard.backend.admin

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens

/**
 * The owner's operations that span a car, its sessions and its messages: one
 * place for the rule, called by `admin.sh` and by the admin page (M6.1).
 */
public class CarAdmin(
    private val registry: CarRegistry,
    private val archive: ArchiveService,
    private val messages: Messages,
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
    public suspend fun removeCar(slug: Slug): Int {
        checkRemovable(slug)
        registry.removeCar(slug)
        return messages.deleteCar(slug.value)
    }
}

/** A car still has [count] sessions, so it cannot be removed yet. */
public class CarHasSessions(public val slug: Slug, public val count: Int) :
    Exception("$slug has $count session(s)")
