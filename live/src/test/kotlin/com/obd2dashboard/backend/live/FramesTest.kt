package com.obd2dashboard.backend.live

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class FramesTest {
    private fun bad(text: String) = TabletFrames.parse(text).shouldBeInstanceOf<TabletFrames.Parsed.Bad>().reason

    @Test
    fun `hello is read, v3 or later`() {
        parse("""{"t":"hello","v":3,"device":"d","app":"1.0","wall":1758719312000}""") shouldBe
            TabletFrame.Hello(3, "d", "1.0", 1758719312000)
        parse("""{"t":"hello","v":4}""") shouldBe TabletFrame.Hello(4, null, null, null)
        // The features it can use (the courses proposal's §1); anything that isn't a string is ignored.
        (parse("""{"t":"hello","v":3,"features":["courses.1","timing.1",7]}""") as TabletFrame.Hello).features shouldBe
            setOf("courses.1", "timing.1")
        bad("""{"t":"hello","v":2}""") shouldContain "v2"
        bad("""{"t":"hello"}""") shouldContain "version"
    }

    @Test
    fun `a session record arrives without its VIN, and its id normalised`() {
        val frame = parse(sessionFrame(id = SESSION.uppercase())).shouldBeInstanceOf<TabletFrame.Session>()
        frame.id shouldBe SESSION
        frame.record.containsKey("vin") shouldBe false
        frame.record.toString() shouldNotContain VIN
        frame.record.string("device") shouldBe "dev"
    }

    @Test
    fun `a VIN in any record is removed, not only the session's`() {
        val frame = parse(batch("""{"type":"future","vin":"$VIN","seq":1}""")).shouldBeInstanceOf<TabletFrame.Batch>()
        frame.records.single().toString() shouldNotContain VIN
        parse(snapshotFrame("""{"type":"sample","signal":"x","value":1,"vin":"$VIN"}""")).toString() shouldNotContain VIN
    }

    @Test
    fun `snapshot, batch and end carry their session and records`() {
        val b = parse(batch(sample("engine.rpm", 1800.0, 5))).shouldBeInstanceOf<TabletFrame.Batch>()
        b.session shouldBe SESSION
        b.records.single().string("signal") shouldBe "engine.rpm"
        parse(snapshotFrame()).shouldBeInstanceOf<TabletFrame.Snapshot>().records shouldBe emptyList()
        parse("""{"t":"end","session":"$SESSION","lastSeq":75566}""") shouldBe TabletFrame.End(SESSION, 75566)
    }

    @Test
    fun `received and displayed are parsed, and unknown kinds pass as Unknown`() {
        parse("""{"t":"received","id":"m_1"}""") shouldBe TabletFrame.Received("m_1")
        parse("""{"t":"displayed","id":"m_1"}""") shouldBe TabletFrame.Displayed("m_1")
        parse("""{"t":"config","anything":1}""") shouldBe TabletFrame.Unknown("config")
    }

    @Test
    fun `malformed frames say why`() {
        bad("not json") shouldContain "JSON object"
        bad("[1]") shouldContain "JSON object"
        bad("""{"x":1}""") shouldContain "\"t\""
        bad("""{"t":5}""") shouldContain "\"t\""
        bad("""{"t":"session"}""") shouldContain "no record"
        bad("""{"t":"session","record":{"type":"sample","v":3,"id":"$SESSION"}}""") shouldContain "not a session record"
        bad("""{"t":"session","record":{"type":"session","v":3,"id":"nope"}}""") shouldContain "UUID"
        bad("""{"t":"session","record":{"type":"session","v":2,"id":"$SESSION"}}""") shouldContain "v2"
        bad("""{"t":"batch","session":"$SESSION"}""") shouldContain "no records"
        bad("""{"t":"batch","session":"$SESSION","records":[1]}""") shouldContain "objects"
        bad("""{"t":"batch","session":"x","records":[]}""") shouldContain "session id"
        bad("""{"t":"end"}""") shouldContain "session id"
        bad("""{"t":"received"}""") shouldContain "id"
    }

    @Test
    fun `a frame over 64 KB is refused, and one at the limit is not`() {
        val pad = { n: Int -> """{"t":"unknownkind","p":"${"x".repeat(n)}"}""" }
        val base = pad(0).length
        TabletFrames.parse(pad(TabletFrames.MAX_FRAME_BYTES - base)).shouldBeInstanceOf<TabletFrames.Parsed.Ok>()
        bad(pad(TabletFrames.MAX_FRAME_BYTES - base + 1)) shouldContain "at most"
    }

    @Test
    fun `server frames are the contract's shapes`() {
        ServerFrames.welcome(1758719312042) shouldBe """{"t":"welcome","serverWall":1758719312042}"""
        ServerFrames.error(ErrorCode.Auth, "refused") shouldBe """{"t":"error","code":"auth","message":"refused","fatal":true}"""
        ServerFrames.error(ErrorCode.BadMessage, "x") shouldContain "\"fatal\":false"
        ErrorCode.Superseded.fatal shouldBe true
        ErrorCode.UnsupportedVersion.wire shouldBe "unsupported_version"
    }
}
