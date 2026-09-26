package com.obd2dashboard.backend.registry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.security.SecureRandom
import org.junit.Test

class TokensTest {
    private val random = SecureRandom()

    @Test
    fun `a token is the prefix and 43 base64url characters`() {
        val token = Tokens.generate(random)
        token shouldStartWith Tokens.PREFIX
        token.length shouldBe Tokens.PREFIX.length + 43
        Tokens.isWellFormed(token) shouldBe true
    }

    @Test
    fun `two tokens differ`() {
        Tokens.generate(random) shouldNotBe Tokens.generate(random)
    }

    @Test
    fun `the hash is hex SHA-256 and is not the token`() {
        val token = Tokens.generate(random)
        val hash = Tokens.hash(token)
        hash.length shouldBe 64
        hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        hash shouldNotContain token.removePrefix(Tokens.PREFIX)
        Tokens.hash(token) shouldBe hash
    }

    @Test
    fun `the hash matches a known SHA-256`() {
        // printf 'abc' | shasum -a 256
        Tokens.hash("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `the hint is the last four characters`() {
        Tokens.hint("obd2_xxxxABCD") shouldBe "ABCD"
    }

    @Test
    fun `malformed tokens are recognised as such`() {
        val good = Tokens.generate(random)
        for (bad in listOf("", "obd2_", good.dropLast(1), good + "A", good.replaceFirst("obd2_", "obd3_"), "obd2_" + "!".repeat(43))) {
            Tokens.isWellFormed(bad) shouldBe false
        }
    }
}
