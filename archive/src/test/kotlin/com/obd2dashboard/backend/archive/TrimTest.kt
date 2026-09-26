package com.obd2dashboard.backend.archive

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrimTest {
    // Stored: lines 0..10.
    private val acked = 10L

    @Test
    fun `the exact continuation appends everything`() {
        Trim.plan(acked, first = 11, count = 5) shouldBe Trim.Plan.Append(skip = 0, first = 11, last = 15)
    }

    @Test
    fun `one past the end is a gap from the next line`() {
        Trim.plan(acked, first = 12, count = 5) shouldBe Trim.Plan.Gap(missingFrom = 11)
    }

    @Test
    fun `a whole duplicate writes nothing`() {
        Trim.plan(acked, first = 1, count = 10) shouldBe Trim.Plan.Duplicate
        Trim.plan(acked, first = 10, count = 1) shouldBe Trim.Plan.Duplicate
    }

    @Test
    fun `a one-line overlap skips one`() {
        Trim.plan(acked, first = 10, count = 2) shouldBe Trim.Plan.Append(skip = 1, first = 11, last = 11)
    }

    @Test
    fun `a long overlap keeps only the tail`() {
        Trim.plan(acked, first = 1, count = 20) shouldBe Trim.Plan.Append(skip = 10, first = 11, last = 20)
    }

    @Test
    fun `with nothing stored, line 0 is the next`() {
        Trim.plan(-1, first = 0, count = 1) shouldBe Trim.Plan.Append(skip = 0, first = 0, last = 0)
        Trim.plan(-1, first = 1, count = 1) shouldBe Trim.Plan.Gap(missingFrom = 0)
    }

    @Test
    fun `nonsense is refused, not planned`() {
        shouldThrow<IllegalArgumentException> { Trim.plan(acked, first = 11, count = 0) }
        shouldThrow<IllegalArgumentException> { Trim.plan(acked, first = -1, count = 1) }
    }

    @Test
    fun `the hash matches shasum over the fixture, line by line and in one go`() {
        val lines = (LineBlock.split(Fixtures.session) as LineBlock.Split.Ok).lines
        LineHash().also { h -> (0 until lines.size).forEach { h.addLine(lines.line(it)) } }.hex() shouldBe
            Fixtures.SESSION_SHA256
        LineHash().also { it.addLines(Fixtures.session) }.hex() shouldBe Fixtures.SESSION_SHA256
    }
}
