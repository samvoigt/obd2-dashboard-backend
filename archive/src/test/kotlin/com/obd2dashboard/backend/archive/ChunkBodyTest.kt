package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Test

class ChunkBodyTest {
    @Test
    fun `gzip inflates to the original`() {
        val decoded = ChunkBody.decode(Fixtures.gzip(Fixtures.session), gzipped = true)
        decoded.shouldBeInstanceOf<ChunkBody.Decoded.Ok>().bytes.contentEquals(Fixtures.session) shouldBe true
    }

    @Test
    fun `an uncompressed body is taken as it is`() {
        ChunkBody.decode(Fixtures.session, gzipped = false)
            .shouldBeInstanceOf<ChunkBody.Decoded.Ok>().bytes.contentEquals(Fixtures.session) shouldBe true
    }

    @Test
    fun `exactly the limit is accepted and one byte more is not`() {
        val limit = ChunkBody.MAX_UNCOMPRESSED
        ChunkBody.decode(Fixtures.gzip(ByteArray(limit)), gzipped = true).shouldBeInstanceOf<ChunkBody.Decoded.Ok>()
        ChunkBody.decode(Fixtures.gzip(ByteArray(limit + 1)), gzipped = true) shouldBe ChunkBody.Decoded.TooLarge
        ChunkBody.decode(ByteArray(limit + 1), gzipped = false) shouldBe ChunkBody.Decoded.TooLarge
    }

    @Test
    fun `a gzip bomb stops at the limit`() {
        // 1 GiB of zeros compresses to about a megabyte; stopping must not need the gigabyte.
        val bomb = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gz -> val zeros = ByteArray(1 shl 20); repeat(1024) { gz.write(zeros) } }
        }.toByteArray()
        val start = System.nanoTime()
        ChunkBody.decode(bomb, gzipped = true) shouldBe ChunkBody.Decoded.TooLarge
        ((System.nanoTime() - start) / 1_000_000 < 2_000) shouldBe true
    }

    @Test
    fun `concatenated gzip members inflate as one body`() {
        val half = Fixtures.session.size / 2
        val twoMembers = Fixtures.gzip(Fixtures.session.copyOfRange(0, half)) +
            Fixtures.gzip(Fixtures.session.copyOfRange(half, Fixtures.session.size))
        ChunkBody.decode(twoMembers, gzipped = true)
            .shouldBeInstanceOf<ChunkBody.Decoded.Ok>().bytes.contentEquals(Fixtures.session) shouldBe true
    }

    @Test
    fun `a body that is not gzip says so`() {
        ChunkBody.decode(Fixtures.session, gzipped = true).shouldBeInstanceOf<ChunkBody.Decoded.NotGzip>()
        ChunkBody.decode(Fixtures.gzip(Fixtures.session).copyOf(20), gzipped = true)
            .shouldBeInstanceOf<ChunkBody.Decoded.NotGzip>()
    }
}
