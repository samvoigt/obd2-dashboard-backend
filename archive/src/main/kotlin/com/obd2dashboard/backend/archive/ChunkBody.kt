package com.obd2dashboard.backend.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * A chunk's body, inflated if it came gzipped, **never beyond [MAX_UNCOMPRESSED]**.
 *
 * The limit is enforced while inflating, not after: a small gzip can claim
 * gigabytes, and the only safe time to stop is the moment the count passes the
 * limit. `GZIPInputStream` reads concatenated gzip members as one stream, so a
 * multi-member body inflates whole.
 */
public object ChunkBody {
    /** Contract §6.2: at most 1 MB uncompressed per chunk; the tablet splits larger backlogs. */
    public const val MAX_UNCOMPRESSED: Int = 1 shl 20

    public fun decode(body: ByteArray, gzipped: Boolean, limit: Int = MAX_UNCOMPRESSED): Decoded {
        if (!gzipped) return if (body.size > limit) Decoded.TooLarge else Decoded.Ok(body)
        val out = ByteArrayOutputStream(minOf(body.size * 4, limit))
        val buffer = ByteArray(16 * 1024)
        try {
            GZIPInputStream(ByteArrayInputStream(body)).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) return Decoded.TooLarge
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            return Decoded.NotGzip(e.message ?: "not gzip")
        }
        return Decoded.Ok(out.toByteArray())
    }

    public sealed interface Decoded {
        public class Ok(public val bytes: ByteArray) : Decoded
        public data object TooLarge : Decoded
        public data class NotGzip(val reason: String) : Decoded
    }
}
