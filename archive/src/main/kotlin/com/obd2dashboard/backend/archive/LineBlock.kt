package com.obd2dashboard.backend.archive

import java.io.OutputStream

/**
 * Lines of a session log as they arrived: one byte array and where each line
 * starts and ends in it. Nothing is decoded or copied until asked for, and what
 * is written back out is **the bytes that came in** (contract §6: nothing on the
 * archive path is re-serialised).
 *
 * A line's bytes exclude its `\n`; [writeFrom] puts one back after each, which
 * is exactly the form the contract's hash is taken over (§6.3).
 */
public class LineBlock private constructor(
    private val data: ByteArray,
    private val starts: IntArray,
    private val ends: IntArray,
) {
    public val size: Int get() = starts.size

    /** A copy of line [i]'s bytes, without its `\n`. */
    public fun line(i: Int): ByteArray = data.copyOfRange(starts[i], ends[i])

    /** Writes lines [from] to the end, each followed by `\n`. */
    public fun writeFrom(from: Int, out: OutputStream) {
        for (i in from until size) {
            out.write(data, starts[i], ends[i] - starts[i])
            out.write('\n'.code)
        }
    }

    /** Lines [from] to the end, each followed by `\n`, as one array. */
    public fun bytesFrom(from: Int): ByteArray {
        if (from >= size) return ByteArray(0)
        return data.copyOfRange(starts[from], ends[size - 1] + 1)
    }

    public companion object {
        /**
         * Splits [body] on `\n`. **Every line must end in `\n`**, the last one
         * included (§6.2): a body without it was cut short, and its last line
         * cannot be told from a partial one. A `\r` is kept as part of its line;
         * the bytes are the tablet's, not ours to normalise.
         */
        public fun split(body: ByteArray): Split {
            if (body.isEmpty()) return Split.Empty
            if (body.last() != '\n'.code.toByte()) return Split.MissingFinalNewline
            var count = 0
            for (b in body) if (b == '\n'.code.toByte()) count++
            val starts = IntArray(count)
            val ends = IntArray(count)
            var line = 0
            var start = 0
            for (i in body.indices) {
                if (body[i] == '\n'.code.toByte()) {
                    starts[line] = start
                    ends[line] = i
                    line++
                    start = i + 1
                }
            }
            return Split.Ok(LineBlock(body, starts, ends))
        }
    }

    public sealed interface Split {
        public data class Ok(val lines: LineBlock) : Split
        public data object Empty : Split
        public data object MissingFinalNewline : Split
    }
}
