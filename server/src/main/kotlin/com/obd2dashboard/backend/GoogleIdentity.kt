package com.obd2dashboard.backend

import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.auth.oauth2.TokenVerifier
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Who may use the admin page (M6): `ADMIN_EMAILS`, comma-separated. Empty means nobody. */
class Allowlist(emails: Collection<String>) {
    private val emails = emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun allows(email: String): Boolean = email.trim().lowercase() in emails

    companion object {
        fun parse(raw: String?): Allowlist = Allowlist(raw.orEmpty().split(','))
    }
}

/** The outcome of a sign-in: the verified email, or why not. */
sealed interface SignIn {
    data class Allowed(val email: String) : SignIn
    data class Refused(val reason: Refusal) : SignIn
}

enum class Refusal { Malformed, BadSignature, Expired, WrongAudience, WrongIssuer, EmailNotVerified, NotAllowed }

/** Turns a sign-in credential into an email (M6.2). The dev server has its own. */
fun interface IdentityVerifier {
    suspend fun verify(credential: String): SignIn
}

/**
 * A Google ID token from "Sign in with Google", checked with Google's own
 * `TokenVerifier` for its signature and expiry, and here for the rest, so each
 * refusal can say why (the library throws one exception for everything).
 */
class GoogleIdentity(
    private val clientId: String,
    private val allowlist: Allowlist,
    private val clock: Clock = Clock.systemUTC(),
    certificatesLocation: String? = null,
) : IdentityVerifier {
    private val verifier: TokenVerifier = TokenVerifier.newBuilder()
        .apply { certificatesLocation?.let { setCertificatesLocation(it) } }
        .setClock { clock.millis() }
        .build()

    override suspend fun verify(credential: String): SignIn {
        val payload = try {
            JsonWebSignature.parse(GsonFactory.getDefaultInstance(), credential).payload
        } catch (_: Exception) {
            return SignIn.Refused(Refusal.Malformed)
        }
        val verified = withContext(Dispatchers.IO) {
            try {
                verifier.verify(credential)
                true
            } catch (_: TokenVerifier.VerificationException) {
                false
            }
        }
        if (!verified) {
            val expired = payload.expirationTimeSeconds?.let { it * 1000 <= clock.millis() } ?: false
            return SignIn.Refused(if (expired) Refusal.Expired else Refusal.BadSignature)
        }
        if (clientId !in payload.audienceAsList.orEmpty()) return SignIn.Refused(Refusal.WrongAudience)
        if (payload.issuer !in ISSUERS) return SignIn.Refused(Refusal.WrongIssuer)
        val email = payload["email"] as? String
        if (email == null || !isTrue(payload["email_verified"])) return SignIn.Refused(Refusal.EmailNotVerified)
        if (!allowlist.allows(email)) return SignIn.Refused(Refusal.NotAllowed)
        return SignIn.Allowed(email.lowercase())
    }

    private fun isTrue(value: Any?): Boolean = value == true || value == "true"

    private companion object {
        /** Google signs with either form. */
        val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
