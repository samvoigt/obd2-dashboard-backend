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
    fun `the hint is the last four characters, or a quarter of a short token`() {
        Tokens.generate(random).let { Tokens.hint(it) shouldBe it.takeLast(4) }
        Tokens.hint("bears-15") shouldBe "15"
        Tokens.hint("abcdefghijklmnoP") shouldBe "mnoP"
        Tokens.hint("abcdefghijklmnO") shouldBe "mnO"
    }

    @Test
    fun `a chosen token is 8 to 128 of letters, digits and dot, underscore, tilde, hyphen`() {
        for (good in listOf("bears-15", "Outback.Blue_2026~x", "a".repeat(128), Tokens.generate(random))) {
            Tokens.isWellFormed(good) shouldBe true
            Tokens.problemWith(good) shouldBe null
        }
        Tokens.problemWith("bears15") shouldBe "a token needs at least 8 characters"
        Tokens.problemWith("a".repeat(129)) shouldBe "a token has at most 128 characters"
        for (bad in listOf("bad bears", "bears/15!", "bearsé123", "bears\t15x")) {
            Tokens.problemWith(bad) shouldBe "a token may use only letters, digits and . _ ~ - (no spaces)"
        }
    }

    @Test
    fun `malformed tokens are recognised as such`() {
        for (bad in listOf("", "obd2_", "bears15", "a".repeat(129), "bad bears", "Bearer x", "obd2_" + "!".repeat(43), "tab\tbears")) {
            Tokens.isWellFormed(bad) shouldBe false
        }
    }
}
