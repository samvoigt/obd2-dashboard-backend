package com.obd2dashboard.backend

import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The admin page's configuration (M6). **Disabled unless built otherwise**: with
 * no [identity], nobody can sign in. `main` builds it from the environment
 * ([fromEnvironment]); only the dev server builds one with a dev sign-in.
 */
class AdminConfig(
    /** Google's OAuth client ID for "Sign in with Google". Not a secret. */
    val googleClientId: String?,
    val allowlist: Allowlist,
    val identity: IdentityVerifier?,
    /** The page shows a dev sign-in button instead of Google's. */
    val dev: Boolean = false,
) {
    val enabled: Boolean get() = identity != null

    companion object {
        val DISABLED = AdminConfig(null, Allowlist(emptyList()), null)

        /**
         * Production: `GOOGLE_CLIENT_ID` and `ADMIN_EMAILS`. Without a client ID the
         * page is off, and the server still starts (M6.3). Never a dev sign-in.
         */
        fun fromEnvironment(env: (String) -> String?, clock: Clock = Clock.systemUTC()): AdminConfig {
            val clientId = env("GOOGLE_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() } ?: return DISABLED
            val allowlist = Allowlist.parse(env("ADMIN_EMAILS"))
            return AdminConfig(clientId, allowlist, GoogleIdentity(clientId, allowlist, clock))
        }
    }
}

/**
 * An admin sign-in: a signed cookie, `adm1.<email, base64url>.<expiry>.<hmac>`,
 * with the crew's key and HMAC (decision 22). Four parts and its own tag, where a
 * crew cookie has five and starts `v1`, so neither can pass as the other. 30 days
 * (Sam); the allowlist is checked again on every request, so removing an email
 * ends that sign-in at once.
 */
class AdminAuth(private val key: ByteArray, private val clock: Clock = Clock.systemUTC()) {
    init {
        require(key.size >= 32) { "the cookie key must be at least 32 bytes" }
    }

    fun issue(email: String): String {
        val expiry = clock.instant().plus(LIFETIME).epochSecond
        val body = "$TAG.${b64.encodeToString(email.toByteArray())}.$expiry"
        return "$body.${sign(body)}"
    }

    /** The email [cookie] was issued to, if it is unaltered, unexpired, and still allowed. */
    fun verify(cookie: String?, allowlist: Allowlist): String? {
        val parts = cookie?.split('.') ?: return null
        if (parts.size != 4 || parts[0] != TAG) return null
        val body = parts.take(3).joinToString(".")
        if (!MessageDigest.isEqual(sign(body).toByteArray(), parts[3].toByteArray())) return null
        val expiry = parts[2].toLongOrNull() ?: return null
        if (!clock.instant().isBefore(Instant.ofEpochSecond(expiry))) return null
        val email = runCatching { String(Base64.getUrlDecoder().decode(parts[1])) }.getOrNull() ?: return null
        return email.takeIf { allowlist.allows(it) }
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return b64.encodeToString(mac.doFinal(body.toByteArray()))
    }

    companion object {
        const val COOKIE = "admin"
        val LIFETIME: Duration = Duration.ofDays(30)
        private const val TAG = "adm1"
        private val b64 = Base64.getUrlEncoder().withoutPadding()
    }
}

/**
 * Whether a change came from one of our own pages: its `Origin` names the host
 * the request was sent to. The scheme is ignored, since TLS ends in front of
 * Cloud Run. A missing `Origin` is refused; browsers send one on every
 * `POST`, `PATCH`, `PUT` and `DELETE`.
 */
fun isSameOrigin(origin: String?, host: String?): Boolean {
    if (origin == null || host == null) return false
    val authority = runCatching { URI(origin).rawAuthority }.getOrNull() ?: return false
    return authority.equals(host, ignoreCase = true)
}
