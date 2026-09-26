package com.obd2dashboard.backend.registry

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A car's crew passcode, stored with PBKDF2-HMAC-SHA256.
 *
 * **A slow hash, unlike [Tokens]**, because a person chooses a passcode and it can
 * be guessed; the iterations are what make each guess expensive offline.
 * 600,000 is OWASP's figure for this function. Built into the JDK, so nothing is
 * added to the build for it.
 *
 * **Stored as `pbkdf2-sha256$<iterations>$<salt>$<hash>`**, so the count can be
 * raised later without breaking passcodes already set: [verify] reads the
 * parameters from the stored string, never from the constant.
 *
 * Passcodes are `CharArray`, not `String`, so a caller can clear one after use.
 */
public object Passcodes {
    public const val MIN_LENGTH: Int = 6
    public const val DEFAULT_ITERATIONS: Int = 600_000
    private const val ALGORITHM = "pbkdf2-sha256"
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    public fun hash(
        passcode: CharArray,
        random: SecureRandom,
        iterations: Int = DEFAULT_ITERATIONS,
    ): String {
        require(iterations > 0) { "iterations must be positive" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(passcode, salt, iterations)
        return listOf(ALGORITHM, iterations, encoder.encodeToString(salt), encoder.encodeToString(hash))
            .joinToString("$")
    }

    /**
     * Whether [passcode] matches [stored], compared in constant time.
     *
     * **False, never an exception, for a stored string it cannot read**: a
     * corrupt record should refuse a login, not fail the request that tried.
     */
    public fun verify(passcode: CharArray, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != ALGORITHM) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(passcode, salt, iterations), expected)
    }

    private fun derive(passcode: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passcode, salt, iterations, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
