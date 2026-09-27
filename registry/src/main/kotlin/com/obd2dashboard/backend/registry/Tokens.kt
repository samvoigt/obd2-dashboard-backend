package com.obd2dashboard.backend.registry

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A car's tablet token. A generated one is `obd2_` and 43 base64url characters,
 * 256 random bits. **The owner may choose one instead** (decision 24): 8 to 128
 * characters from `A–Z a–z 0–9 . _ ~ -`, the characters a bearer token can carry
 * as they are. Every generated token is also a valid chosen one.
 *
 * **Stored as its SHA-256.** A fast hash suits generated tokens, which cannot be
 * guessed. A chosen one is weaker against someone holding the store, which Sam
 * accepted: a lookup by hash cannot use a salted, slow hash.
 *
 * **The prefix is for recognition**, by a person reading a config and by secret
 * scanners finding one pasted where it should not be.
 */
public object Tokens {
    public const val PREFIX: String = "obd2_"
    private const val RANDOM_BYTES = 32
    private const val HINT_LENGTH = 4
    public const val MIN_LENGTH: Int = 8
    public const val MAX_LENGTH: Int = 128
    private val ALLOWED = Regex("[A-Za-z0-9._~-]*")
    private val SHAPE = Regex("[A-Za-z0-9._~-]{$MIN_LENGTH,$MAX_LENGTH}")
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    public fun generate(random: SecureRandom): String {
        val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
        return PREFIX + encoder.encodeToString(bytes)
    }

    /** Lower-case hex SHA-256 of [token]'s UTF-8 bytes: what is stored and looked up. */
    public fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * The last few characters, stored so "which token is on the tablet?" can be
     * answered without keeping the token: 4, or a quarter of a short chosen one.
     */
    public fun hint(token: String): String = token.takeLast(minOf(HINT_LENGTH, token.length / 4))

    /** Whether [candidate] could be a token at all, so garbage never reaches the store. */
    public fun isWellFormed(candidate: String): Boolean = SHAPE.matches(candidate)

    /** Why [candidate] cannot be a chosen token, for a person; null if it can. */
    public fun problemWith(candidate: String): String? = when {
        candidate.length < MIN_LENGTH -> "a token needs at least $MIN_LENGTH characters"
        candidate.length > MAX_LENGTH -> "a token has at most $MAX_LENGTH characters"
        !ALLOWED.matches(candidate) -> "a token may use only letters, digits and . _ ~ - (no spaces)"
        else -> null
    }
}
