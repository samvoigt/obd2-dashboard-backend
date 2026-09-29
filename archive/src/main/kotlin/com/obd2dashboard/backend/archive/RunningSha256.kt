package com.obd2dashboard.backend.archive

import java.nio.ByteBuffer
import java.util.Base64

/**
 * **SHA-256 whose state can be kept** (M19.3), FIPS 180-4: fed a session's
 * lines as each chunk is acknowledged, its state stored with the session's
 * record, so `complete` only compares. The JDK's `MessageDigest` can't hand
 * over its state; this is checked against it.
 */
public class RunningSha256 private constructor(
    private val h: IntArray,
    private var length: Long,
    private val block: ByteArray,
    private var filled: Int,
) {
    public constructor() : this(INITIAL.copyOf(), 0, ByteArray(64), 0)

    public fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): RunningSha256 {
        var i = offset
        val end = offset + count
        length += count
        while (i < end) {
            val n = minOf(64 - filled, end - i)
            System.arraycopy(bytes, i, block, filled, n)
            filled += n
            i += n
            if (filled == 64) {
                compress(h, block)
                filled = 0
            }
        }
        return this
    }

    /** The digest of everything so far, as hex; the state is left as it was. */
    public fun hex(): String {
        val hh = h.copyOf()
        val tail = ByteArray(if (filled < 56) 64 else 128)
        System.arraycopy(block, 0, tail, 0, filled)
        tail[filled] = 0x80.toByte()
        ByteBuffer.wrap(tail, tail.size - 8, 8).putLong(length * 8)
        compress(hh, tail.copyOfRange(0, 64))
        if (tail.size == 128) compress(hh, tail.copyOfRange(64, 128))
        return hh.joinToString("") { "%08x".format(it) }
    }

    /** The state, to store: the eight words, the length, and the partial block. */
    public fun state(): String {
        val buf = ByteBuffer.allocate(32 + 8 + 1 + filled)
        h.forEach { buf.putInt(it) }
        buf.putLong(length)
        buf.put(filled.toByte())
        buf.put(block, 0, filled)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
    }

    public companion object {
        /** A hash carried on from [state]. */
        public fun restore(state: String): RunningSha256 {
            val buf = ByteBuffer.wrap(Base64.getUrlDecoder().decode(state))
            val h = IntArray(8) { buf.int }
            val length = buf.long
            val filled = buf.get().toInt()
            require(filled in 0..63) { "not a hash's state" }
            val block = ByteArray(64)
            buf.get(block, 0, filled)
            return RunningSha256(h, length, block, filled)
        }

        private val INITIAL: IntArray = longArrayOf(
            0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL, 0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
        ).let { l -> IntArray(l.size) { i -> l[i].toInt() } }

        private val K: IntArray = longArrayOf(
            0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
            0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
            0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
            0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
            0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
            0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
            0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
            0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
        ).let { l -> IntArray(l.size) { i -> l[i].toInt() } }

        private fun compress(h: IntArray, block: ByteArray) {
            val w = IntArray(64)
            val b = ByteBuffer.wrap(block)
            for (t in 0 until 16) w[t] = b.int
            for (t in 16 until 64) {
                val s0 = Integer.rotateRight(w[t - 15], 7) xor Integer.rotateRight(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = Integer.rotateRight(w[t - 2], 17) xor Integer.rotateRight(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var bb = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
                val maj = (a and bb) xor (a and c) xor (bb and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2
            }
            h[0] += a; h[1] += bb; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
    }
}
