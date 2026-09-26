package com.obd2dashboard.backend

import com.obd2dashboard.backend.registry.Car
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A crew login: a signed cookie per car (decision 11, Sam 2026-09-26).
 *
 * The cookie is `v1.<slug>.<expiry>.<fingerprint>.<hmac>`:
 * - **the HMAC** (SHA-256, with the server's `CREW_COOKIE_KEY`) covers
 *   everything before it, so nothing in it can be altered;
 * - **the fingerprint** is the start of a hash of the car's stored passcode
 *   hash, so **changing the passcode logs everyone out**, while the cookie
 *   still says nothing about the passcode;
 * - **30 days**, as Sam asked.
 */
class CrewAuth(private val key: ByteArray, private val clock: Clock = Clock.systemUTC()) {
    init {
        require(key.size >= 32) { "the crew cookie key must be at least 32 bytes" }
    }

    fun cookieName(slug: String): String = "crew_$slug"

    fun issue(car: Car): String {
        val expiry = clock.instant().plus(LIFETIME).epochSecond
        val body = "v1.${car.slug.value}.$expiry.${fingerprint(car)}"
        return "$body.${sign(body)}"
    }

    /** Whether [cookie] is a live login for [car] as it stands now: its own, unexpired, unaltered, and from the current passcode. */
    fun verify(cookie: String?, car: Car): Boolean {
        if (cookie == null) return false
        val parts = cookie.split('.')
        if (parts.size != 5 || parts[0] != "v1") return false
        val body = parts.take(4).joinToString(".")
        if (!MessageDigest.isEqual(sign(body).toByteArray(), parts[4].toByteArray())) return false
        val expiry = parts[2].toLongOrNull() ?: return false
        return parts[1] == car.slug.value &&
            clock.instant().isBefore(Instant.ofEpochSecond(expiry)) &&
            car.passcodeHash != null &&
            MessageDigest.isEqual(parts[3].toByteArray(), fingerprint(car).toByteArray())
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.toByteArray()))
    }

    private fun fingerprint(car: Car): String {
        val stored = car.passcodeHash ?: return "none"
        return MessageDigest.getInstance("SHA-256").digest(stored.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    companion object {
        val LIFETIME: Duration = Duration.ofDays(30)

        /** `CREW_COOKIE_KEY` is base64url, as `gcp-setup.sh` writes it. */
        fun keyFrom(encoded: String): ByteArray = Base64.getUrlDecoder().decode(encoded.trim().trimEnd('='))
    }
}

/**
 * At most [limit] failed logins per car in [window]; past that, a `429` until the
 * oldest failure leaves the window. In memory, which is right for one instance
 * (decision 7); PBKDF2's cost slows every attempt as well.
 */
class LoginLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val limit: Int = 10,
    private val window: Duration = Duration.ofMinutes(10),
) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** Null if an attempt may go ahead; otherwise how long to wait. */
    fun waitFor(car: String): Duration? {
        val q = failures[car] ?: return null
        synchronized(q) {
            val now = clock.instant()
            while (q.isNotEmpty() && !q.peekFirst().plus(window).isAfter(now)) q.removeFirst()
            if (q.size < limit) return null
            return Duration.between(now, q.peekFirst().plus(window))
        }
    }

    fun failed(car: String) {
        val q = failures.computeIfAbsent(car) { ArrayDeque() }
        synchronized(q) { q.addLast(clock.instant()) }
    }
}
