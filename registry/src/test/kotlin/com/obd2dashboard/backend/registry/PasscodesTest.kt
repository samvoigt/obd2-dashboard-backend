package com.obd2dashboard.backend.registry

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.security.SecureRandom
import org.junit.Test

class PasscodesTest {
    private val random = SecureRandom()

    // A small count keeps tests fast; the stored string carries it, as a real one does.
    private val fast = 1_000

    @Test
    fun `a passcode round-trips`() {
        val stored = Passcodes.hash("pit-lane".toCharArray(), random, fast)
        Passcodes.verify("pit-lane".toCharArray(), stored) shouldBe true
    }

    @Test
    fun `a wrong passcode fails`() {
        val stored = Passcodes.hash("pit-lane".toCharArray(), random, fast)
        Passcodes.verify("pit-lanf".toCharArray(), stored) shouldBe false
        Passcodes.verify("".toCharArray(), stored) shouldBe false
    }

    @Test
    fun `the stored string names its parameters and does not contain the passcode`() {
        val stored = Passcodes.hash("pit-lane".toCharArray(), random, fast)
        stored shouldStartWith "pbkdf2-sha256\$$fast\$"
        stored shouldNotContain "pit-lane"
    }

    @Test
    fun `the default is 600,000 iterations`() {
        Passcodes.hash("pit-lane".toCharArray(), random) shouldStartWith "pbkdf2-sha256\$600000\$"
    }

    @Test
    fun `the same passcode is salted differently each time`() {
        val a = Passcodes.hash("pit-lane".toCharArray(), random, fast)
        val b = Passcodes.hash("pit-lane".toCharArray(), random, fast)
        a shouldNotBe b
    }

    @Test
    fun `a hash made with another iteration count still verifies`() {
        val stored = Passcodes.hash("pit-lane".toCharArray(), random, 2_000)
        Passcodes.verify("pit-lane".toCharArray(), stored) shouldBe true
    }

    @Test
    fun `the iteration count is part of what is checked`() {
        val stored = Passcodes.hash("pit-lane".toCharArray(), random, fast)
        val tampered = stored.replaceFirst("\$$fast\$", "\$${fast + 1}\$")
        Passcodes.verify("pit-lane".toCharArray(), tampered) shouldBe false
    }

    @Test
    fun `an unreadable stored string refuses rather than throws`() {
        for (bad in listOf("", "plain", "md5\$1\$AA\$AA", "pbkdf2-sha256\$x\$AA\$AA", "pbkdf2-sha256\$0\$AA\$AA", "pbkdf2-sha256\$10\$!!\$AA")) {
            Passcodes.verify("pit-lane".toCharArray(), bad) shouldBe false
        }
    }
}
