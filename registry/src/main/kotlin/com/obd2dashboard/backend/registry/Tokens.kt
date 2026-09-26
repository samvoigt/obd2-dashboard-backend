package com.obd2dashboard.backend.registry

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A car's tablet token: `obd2_` and 43 base64url characters, 256 random bits.
 *
 * **Stored as its SHA-256, and a fast hash is right here.** A slow hash protects
 * something a person chose and an attacker can guess; 256 random bits cannot be
 * guessed, so stretching them would cost every request and buy nothing.
 *
 * **The prefix is for recognition**, by a person reading a config and by secret
 * scanners finding one pasted where it should not be.
 */
public object Tokens {
    public const val PREFIX: String = "obd2_"
    private const val RANDOM_BYTES = 32
    private const val HINT_LENGTH = 4

    /** 43: 32 bytes in unpadded base64url. */
    private const val ENCODED_LENGTH = 43
    private val SHAPE = Regex("${Regex.escape(PREFIX)}[A-Za-z0-9_-]{$ENCODED_LENGTH}")
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
     * answered without keeping the token. 4 of 43 random characters leave far
     * more than enough unknown.
     */
    public fun hint(token: String): String = token.takeLast(HINT_LENGTH)

    /** Whether [candidate] could be a token at all, so garbage never reaches the store. */
    public fun isWellFormed(candidate: String): Boolean = SHAPE.matches(candidate)
}
