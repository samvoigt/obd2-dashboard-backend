package com.obd2dashboard.backend.archive

import java.security.MessageDigest

/**
 * What to do with a chunk, given what is already stored (contract §6.2).
 *
 * Lines through [ackedThrough] are stored. A chunk of [count] lines starting at
 * [first] is:
 * - **wholly stored** → [Plan.Duplicate]: answer with `ackedThrough`, write nothing;
 * - **overlapping or continuing** → [Plan.Append]: skip the first
 *   [Plan.Append.skip] lines, which are already stored, and store the rest;
 * - **past the end** → [Plan.Gap]: `409`, resend from `ackedThrough + 1`.
 */
public object Trim {
    public fun plan(ackedThrough: Long, first: Long, count: Int): Plan {
        require(count > 0) { "a chunk has at least one line" }
        require(first >= 0) { "indexes start at 0" }
        val next = ackedThrough + 1
        if (first > next) return Plan.Gap(missingFrom = next)
        val last = first + count - 1
        if (last <= ackedThrough) return Plan.Duplicate
        return Plan.Append(skip = (next - first).toInt(), first = next, last = last)
    }

    public sealed interface Plan {
        public data object Duplicate : Plan
        public data class Append(val skip: Int, val first: Long, val last: Long) : Plan
        public data class Gap(val missingFrom: Long) : Plan
    }
}

/** SHA-256 over lines, each followed by one `\n`: the contract's hash (§6.3). */
public class LineHash {
    private val digest = MessageDigest.getInstance("SHA-256")

    /** Adds [line], which excludes its `\n`. */
    public fun addLine(line: ByteArray) {
        digest.update(line)
        digest.update('\n'.code.toByte())
    }

    /** Adds bytes that are already whole lines, `\n`s included. */
    public fun addLines(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        digest.update(bytes, offset, length)
    }

    public fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
}
