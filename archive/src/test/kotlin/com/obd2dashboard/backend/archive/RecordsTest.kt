package com.obd2dashboard.backend.archive

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class RecordsTest {
    private fun lines(body: String) = (LineBlock.split(body.toByteArray()) as LineBlock.Split.Ok).lines

    @Test
    fun `objects pass, whatever their type and fields`() {
        Records.parseObject("""{"type":"mystery","anything":[1,2]}""".toByteArray()).shouldNotBeNull()
        Records.firstNonObject(lines(Fixtures.session.decodeToString())).shouldBeNull()
    }

    @Test
    fun `anything that is not one JSON object fails`() {
        for (bad in listOf("", " ", "[1]", "42", "\"s\"", "null", "{", "{}{}", "{\"a\":1} x", "\u0000")) {
            Records.parseObject(bad.toByteArray()).shouldBeNull()
        }
    }

    @Test
    fun `invalid UTF-8 fails rather than being patched`() {
        val bytes = "{\"a\":\"".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray()
        Records.parseObject(bytes).shouldBeNull()
    }

    @Test
    fun `the first bad line is named`() {
        Records.firstNonObject(lines("{}\n{}\nnope\n{}\n")) shouldBe 2
        Records.firstNonObject(lines("{}\n\n")) shouldBe 1
    }

    private val v3 = Fixtures.session.decodeToString().lineSequence().first().toByteArray()

    @Test
    fun `a v3 session record is read, with absent fields as null`() {
        val header = SessionHeader.parse(v3, Fixtures.SESSION_ID).shouldBeInstanceOf<SessionHeader.Parsed.Ok>().header
        header.v shouldBe 3
        header.started shouldBe "2026-09-24T13:08:32.623Z"
        header.device shouldBe "0b7e2a90-4f11-4c2e-8d55-3f6c1a2b9e70"
        header.app shouldBe "1.0 (42)"
        header.protocol shouldBe "ISO 15765-4 CAN, 11-bit ID, 500 kbaud"
        header.vin.shouldBeNull() // the fixture has none: absent, as the app writes it
        header.source.shouldBeNull() // a car's session: absent (§3.2)
    }

    @Test
    fun `a session's source is read as sent (M11)`() {
        for (source in listOf("tablet", "fake", "something-new")) {
            val line = v3.decodeToString().replace("\"type\":\"session\",", "\"type\":\"session\",\"source\":\"$source\",")
            (SessionHeader.parse(line.toByteArray(), Fixtures.SESSION_ID) as SessionHeader.Parsed.Ok).header.source shouldBe source
        }
    }

    @Test
    fun `a VIN is read, and never printed`() {
        val withVin = """{"type":"session","v":3,"id":"${Fixtures.SESSION_ID}","started":"x","vin":"TSTVEHCLE00000001"}"""
        val header = (SessionHeader.parse(withVin.toByteArray(), Fixtures.SESSION_ID) as SessionHeader.Parsed.Ok).header
        header.vin shouldBe "TSTVEHCLE00000001"
        header.toString() shouldNotContain "TSTVEHCLE"
    }

    @Test
    fun `the id may differ in case from the URL, and the URL's spelling is kept`() {
        val upper = Fixtures.SESSION_ID.uppercase()
        (SessionHeader.parse(v3, upper) as SessionHeader.Parsed.Ok).header.id shouldBe upper
    }

    @Test
    fun `wrong records are refused with a reason`() {
        fun reason(line: String, id: String = Fixtures.SESSION_ID) =
            SessionHeader.parse(line.toByteArray(), id).shouldBeInstanceOf<SessionHeader.Parsed.Bad>().reason
        reason("""{"type":"sample","v":3,"id":"${Fixtures.SESSION_ID}","started":"x"}""") shouldContain "not a session"
        reason("""{"type":"session","v":2,"id":"${Fixtures.SESSION_ID}","started":"x"}""") shouldContain "v2"
        reason("""{"type":"session","v":"3","id":"${Fixtures.SESSION_ID}","started":"x"}""") shouldContain "version"
        reason("""{"type":"session","id":"${Fixtures.SESSION_ID}","started":"x"}""") shouldContain "version"
        reason("""{"type":"session","v":3,"started":"x"}""") shouldContain "no id"
        reason(String(v3), id = "00000000-0000-0000-0000-000000000000") shouldContain "not the one in the URL"
        reason("""{"type":"session","v":3,"id":"${Fixtures.SESSION_ID}"}""") shouldContain "start"
        reason("not json") shouldContain "not a JSON object"
    }

    @Test
    fun `session ids are UUIDs`() {
        SessionIds.isValid(Fixtures.SESSION_ID) shouldBe true
        SessionIds.isValid(Fixtures.SESSION_ID.uppercase()) shouldBe true
        for (bad in listOf("", "x", "7d4c9b1e2f6a4e8b9c3d5a1b2c3d4e5f", "${Fixtures.SESSION_ID}0", "../../etc", "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5g")) {
            SessionIds.isValid(bad) shouldBe false
        }
        SessionIds.normalise("ABC") shouldBe "abc"
    }
}
