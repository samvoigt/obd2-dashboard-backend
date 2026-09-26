package com.obd2dashboard.backend.registry

import java.time.Instant

/**
 * A registered car: a page on the site, and the token that feeds it.
 *
 * **A car is not a vehicle.** It is whatever its token was issued for; the
 * server never identifies one by VIN (decision 10). Which vehicle a session came
 * from is the session log's business.
 *
 * **Holds hashes, never secrets.** Nothing here can be turned back into a token
 * or a passcode, so a `Car` is safe to log, but not to serve: the hashes still
 * help an offline guesser, which is why public responses name their fields
 * rather than serialising this (M2.5).
 */
public data class Car(
    val slug: Slug,
    val name: String,
    /** [Tokens.hash] of the current token. */
    val tokenHash: String,
    /** [Tokens.hint] of the current token. */
    val tokenHint: String,
    val tokenIssued: Instant,
    /** [Passcodes.hash] of the crew passcode; null until one is set. */
    val passcodeHash: String?,
    val created: Instant,
    val updated: Instant,
) {
    public companion object {
        public const val MAX_NAME_LENGTH: Int = 60
    }
}

/** A car and the token just made for it: the only time a token exists outside the tablet. */
public data class IssuedToken(val car: Car, val token: String) {
    // Never print the token by accident, in a log line or a failed assertion.
    override fun toString(): String = "IssuedToken(car=${car.slug}, token=${Tokens.PREFIX}…${car.tokenHint})"
}
