package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Test

/** The running hash (M19.3) against the JDK's. */
class RunningSha256Test {
    private fun jdk(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the known answers`() {
        RunningSha256().hex() shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        RunningSha256().update("abc".toByteArray()).hex() shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `the JDK's digest, fed in any pieces, carried through its stored state at any point`() {
        val random = Random(19)
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 3000))
            var hash = RunningSha256()
            var i = 0
            while (i < bytes.size) {
                val n = minOf(random.nextInt(0, 130), bytes.size - i)
                hash.update(bytes, i, n)
                i += n
                if (random.nextBoolean()) hash = RunningSha256.restore(hash.state()) // stored and read back
            }
            hash.hex() shouldBe jdk(bytes)
            hash.hex() shouldBe jdk(bytes) // asking leaves it as it was
        }
    }

    @Test
    fun `lengths at the padding's edges`() {
        for (n in listOf(55, 56, 63, 64, 65, 119, 120, 127, 128)) {
            val bytes = ByteArray(n) { it.toByte() }
            RunningSha256().update(bytes).hex() shouldBe jdk(bytes)
        }
    }
}
