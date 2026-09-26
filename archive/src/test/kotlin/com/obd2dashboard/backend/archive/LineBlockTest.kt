package com.obd2dashboard.backend.archive

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import org.junit.Test

class LineBlockTest {
    private fun ok(body: String): LineBlock =
        LineBlock.split(body.toByteArray()).shouldBeInstanceOf<LineBlock.Split.Ok>().lines

    @Test
    fun `splits on newline and keeps each line's bytes`() {
        val lines = ok("a\nbc\n\n{}\n")
        lines.size shouldBe 4
        lines.line(0).decodeToString() shouldBe "a"
        lines.line(1).decodeToString() shouldBe "bc"
        lines.line(2).decodeToString() shouldBe ""
        lines.line(3).decodeToString() shouldBe "{}"
    }

    @Test
    fun `the last line must end in a newline`() {
        LineBlock.split("a\nb".toByteArray()) shouldBe LineBlock.Split.MissingFinalNewline
    }

    @Test
    fun `an empty body is empty`() {
        LineBlock.split(ByteArray(0)) shouldBe LineBlock.Split.Empty
    }

    @Test
    fun `a carriage return stays part of its line`() {
        ok("a\r\nb\n").line(0).toList() shouldBe listOf('a'.code.toByte(), '\r'.code.toByte())
    }

    @Test
    fun `writing from a line reproduces the bytes exactly`() {
        val body = "one\ntwo\nthree\n"
        val lines = ok(body)
        ByteArrayOutputStream().also { lines.writeFrom(0, it) }.toString() shouldBe body
        ByteArrayOutputStream().also { lines.writeFrom(1, it) }.toString() shouldBe "two\nthree\n"
        lines.bytesFrom(2).decodeToString() shouldBe "three\n"
        lines.bytesFrom(3).size shouldBe 0
    }

    @Test
    fun `the fixture splits into its lines and writes back byte for byte`() {
        val lines = ok(Fixtures.session.decodeToString())
        lines.size shouldBe Fixtures.SESSION_LINES
        lines.bytesFrom(0).contentEquals(Fixtures.session) shouldBe true
    }
}
