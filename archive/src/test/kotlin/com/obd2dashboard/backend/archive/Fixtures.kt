package com.obd2dashboard.backend.archive

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** A synthetic v3 session, 47 lines; its hash below is from `shasum -a 256`, not from this code. */
object Fixtures {
    const val SESSION_ID = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
    const val SESSION_SHA256 = "2830fae7b9ddfd711c94543eaa9943bf8e9d64504ebc9d627f4d7d4d9647c781"
    const val SESSION_LINES = 47

    val session: ByteArray by lazy {
        Fixtures::class.java.getResourceAsStream("/session-v3.jsonl")!!.use { it.readBytes() }
    }

    fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
