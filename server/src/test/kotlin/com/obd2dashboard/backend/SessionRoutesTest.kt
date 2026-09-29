package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionIndex
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.LineBlock
import com.obd2dashboard.backend.archive.LineHash
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Past sessions on the site (M7.3). */
class SessionRoutesTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-26T18:00:00Z"), ZoneOffset.UTC)
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val archive = ArchiveService(index, store, clock)
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )

    init {
        runBlocking {
            registry.addCar(Slug.parse("yaris"), "Yaris")
            registry.addCar(Slug.parse("outback"), "Outback")
        }
    }

    private fun ApplicationTestBuilder.app() {
        application { module(registry, archive, InMemoryLiveHub(clock), clock = clock, messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), admin = config) }
    }

    private val fixture: List<String> =
        SessionRoutesTest::class.java.getResourceAsStream("/session-v3.jsonl")!!.use { it.readBytes() }.decodeToString().split('\n').dropLast(1)

    /**
     * The fixture as session [id] starting at [wall], with a VIN in its header,
     * a fault, and a lap, uploaded; completed unless [complete] is false.
     */
    private suspend fun upload(id: String, car: String, wall: Long, complete: Boolean = true, source: String? = null): List<String> {
        val shift = wall - 1758719312623
        val lines = fixture.mapIndexed { i, line ->
            var l = line.replace("7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f", id)
                .replace(Regex("\"wall\":(\\d+)")) { "\"wall\":${it.groupValues[1].toLong() + shift}" }
            if (i == 0) l = l.replace("\"pids\":48", "\"vin\":\"TSTVEHCLE00000001\",\"pids\":48")
            if (i == 0 && source != null) l = l.replace("\"type\":\"session\",", "\"type\":\"session\",\"source\":\"$source\",")
            l
        } + listOf(
            """{"type":"fault","codes":["P0420"],"seq":900001,"at":1,"wall":${wall + 60_000}}""",
            """{"type":"lap","track":"nhms","layout":"Road Course","lap":1,"time":94.532,"seq":900002,"at":2,"wall":${wall + 120_000}}""",
        )
        archive.open(car, id, lines[0].toByteArray())
        val rest = lines.drop(1).joinToString("") { "$it\n" }.toByteArray()
        archive.append(car, id, 1, (LineBlock.split(rest) as LineBlock.Split.Ok).lines)
        if (complete) {
            val hash = LineHash().apply { lines.forEach { addLine(it.toByteArray()) } }.hex()
            archive.complete(car, id, lines.size - 1L, lines.size.toLong(), hash) shouldBe ArchiveService.Complete.Done
        }
        return lines
    }

    private val t = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()

    @Test
    fun `a car's sessions come as drives, newest first, with their summaries and never a VIN`() = testApplication {
        app()
        runBlocking {
            upload(A, "yaris", t) // a drive of two sessions, 5 minutes apart
            upload(B, "yaris", t + Duration.ofMinutes(8).toMillis())
            upload(C, "yaris", t + Duration.ofHours(3).toMillis()) // another drive
            upload(D, "outback", t) // another car's
        }
        val body = client.get("/api/cars/yaris/sessions").bodyAsText()
        body shouldNotContain "TSTVEHCLE"
        body shouldNotContain "vin"
        val drives = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        drives.map { d -> d.getValue("sessions").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content } } shouldBe
            listOf(listOf(C), listOf(B, A))
        val first = drives[1].getValue("sessions").jsonArray.last().jsonObject
        first.getValue("state").jsonPrimitive.content shouldBe "complete"
        first.getValue("track").jsonPrimitive.content shouldBe "nhms"
        first.getValue("bestLap").jsonObject.getValue("time").jsonPrimitive.content shouldBe "94.532"
        first.getValue("faults").jsonArray.single().jsonPrimitive.content shouldBe "P0420"
        client.get("/api/cars/nope/sessions").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `one session's page data, its signals, and a 404 for bad or unknown ids`() = testApplication {
        app()
        runBlocking { upload(A, "yaris", t) }
        val body = client.get("/api/sessions/$A").bodyAsText()
        body shouldNotContain "TSTVEHCLE"
        val detail = Json.parseToJsonElement(body).jsonObject
        detail.getValue("car").jsonPrimitive.content shouldBe "yaris"
        detail.getValue("carName").jsonPrimitive.content shouldBe "Yaris"
        detail.getValue("signals").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content } shouldBe
            listOf("engine.rpm", "diagnostics.mil")
        client.get("/api/sessions/${A.uppercase()}").status shouldBe HttpStatusCode.OK
        for (bad in listOf("nope", "00000000-0000-4000-8000-000000000000", "../../etc")) {
            client.get("/api/sessions/$bad").status shouldBe HttpStatusCode.NotFound
            client.get("/api/sessions/$bad/series").status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun `the series is sent gzipped as stored, with an ETag that saves a repeat`() = testApplication {
        app()
        runBlocking { upload(A, "yaris", t) }
        val raw = client.get("/api/sessions/$A/series")
        raw.status shouldBe HttpStatusCode.OK
        raw.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
        raw.headers[HttpHeaders.ContentType]!! shouldContain "application/json"
        val json = GZIPInputStream(ByteArrayInputStream(raw.bodyAsBytes())).readBytes().decodeToString() // really gzip
        json shouldNotContain "TSTVEHCLE"
        Json.parseToJsonElement(json).jsonObject.getValue("numbers").jsonObject.keys shouldContain2 "engine.rpm"
        val tag = raw.headers[HttpHeaders.ETag]!!
        tag shouldContain "series-v"
        client.get("/api/sessions/$A/series") { header(HttpHeaders.IfNoneMatch, tag) }.status shouldBe HttpStatusCode.NotModified

        // Deleted, and its id used again for another drive: a new tag, so a browser's copy of the old one is never kept (M16.4).
        runBlocking {
            archive.delete(A)
            upload(A, "yaris", t + 3_600_000)
        }
        val again = client.get("/api/sessions/$A/series") { header(HttpHeaders.IfNoneMatch, tag) }
        again.status shouldBe HttpStatusCode.OK
        (again.headers[HttpHeaders.ETag] != tag) shouldBe true
    }

    @Test
    fun `a session still uploading has a series, no summary, and says so`() = testApplication {
        app()
        runBlocking { upload(A, "yaris", t, complete = false) }
        val item = Json.parseToJsonElement(client.get("/api/cars/yaris/sessions").bodyAsText())
            .jsonArray.single().jsonObject.getValue("sessions").jsonArray.single().jsonObject
        item.getValue("state").jsonPrimitive.content shouldBe "uploading"
        item.getValue("laps").jsonPrimitive.content shouldBe "0"
        client.get("/api/sessions/$A/series").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `the download is the admin's, byte for byte, VIN and all`() = testApplication {
        app()
        val lines = runBlocking { upload(A, "yaris", t) }
        client.get("/api/admin/sessions/$A/download").status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/sessions/$A/download").status shouldBe HttpStatusCode.NotFound // no public download
        val cookie = client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val download = client.get("/api/admin/sessions/$A/download") { header(HttpHeaders.Cookie, cookie) }
        download.status shouldBe HttpStatusCode.OK
        download.headers[HttpHeaders.ContentDisposition]!! shouldContain "$A.jsonl.gz"
        download.headers[HttpHeaders.ContentEncoding] shouldBe null // a file to save, not a transfer encoding
        val bytes = GZIPInputStream(ByteArrayInputStream(download.bodyAsBytes())).readBytes()
        bytes.decodeToString() shouldBe lines.joinToString("") { "$it\n" }
    }

    @Test
    fun `a session still uploading is served thinned, with its own tag, and nothing new is a 304 (M19_4)`() = testApplication {
        app()
        runBlocking { upload(A, "yaris", t, complete = false) }
        val first = client.get("/api/sessions/$A/series")
        first.status shouldBe HttpStatusCode.OK
        val tag = first.headers[HttpHeaders.ETag]!!
        tag.startsWith("\"thin-") shouldBe true
        client.get("/api/sessions/$A/series") { header(HttpHeaders.IfNoneMatch, tag) }.status shouldBe HttpStatusCode.NotModified
        // Not streamed, so the live run doesn't hold it: its laps come from its series.
        client.get("/api/sessions/$A/live-laps").status shouldBe HttpStatusCode.NoContent
    }

    @Test
    fun `an unfinished session downloads as its segments, zipped`() = testApplication {
        app()
        val lines = runBlocking { upload(A, "yaris", t, complete = false) }
        val cookie = client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val bytes = GZIPInputStream(ByteArrayInputStream(client.get("/api/admin/sessions/$A/download") { header(HttpHeaders.Cookie, cookie) }.bodyAsBytes())).readBytes()
        bytes.decodeToString() shouldBe lines.joinToString("") { "$it\n" }
        // Answered complete and not yet one object (M19.3): its segments, zipped, all the same.
        runBlocking {
            archive.complete("yaris", A, lines.size - 1L, lines.size.toLong(), java.security.MessageDigest.getInstance("SHA-256").digest(lines.joinToString("") { "$it\n" }.toByteArray()).joinToString("") { "%02x".format(it) }) shouldBe ArchiveService.Complete.Done
            archive.session(A)!!.let { it.complete shouldBe true; (it.segments.isNotEmpty()) shouldBe true }
        }
        val again = GZIPInputStream(ByteArrayInputStream(client.get("/api/admin/sessions/$A/download") { header(HttpHeaders.Cookie, cookie) }.bodyAsBytes())).readBytes()
        again.decodeToString() shouldBe lines.joinToString("") { "$it\n" }
    }

    @Test
    fun `a session with no archived lines is listed only while live`() = testApplication {
        val hub = InMemoryLiveHub(clock)
        application { module(registry, archive, hub, clock = clock, messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), admin = config) }
        runBlocking { archive.announce("yaris", A) } // what the live lane does first
        fun listed() = runBlocking { client.get("/api/cars/yaris/sessions").bodyAsText() }
        listed() shouldNotContain A
        client.get("/api/sessions/$A").status shouldBe HttpStatusCode.NotFound // not live: nothing to show
        client.get("/api/sessions/$A/series").status shouldBe HttpStatusCode.NotFound
        runBlocking {
            val tablet = hub.attach("yaris", object : com.obd2dashboard.backend.live.TabletHandle {
                override fun superseded() {}
                override fun close(code: Short, reason: String) {}
                override fun send(frame: String) {}
            })
            val frame = com.obd2dashboard.backend.live.TabletFrames.parse(
                """{"t":"session","record":{"type":"session","v":3,"id":"$A","device":"d","app":"1","started":"2026-09-26T17:59:00Z","signals":[],"seq":0,"at":0}}""",
            ) as com.obd2dashboard.backend.live.TabletFrames.Parsed.Ok
            tablet.apply(frame.frame)
        }
        listed() shouldContain A
        listed() shouldContain "\"state\":\"live\""
        listed() shouldContain "\"ended\":${clock.millis()}" // still going: it ends now
        client.get("/api/sessions/$A").status shouldBe HttpStatusCode.OK // its page, from the live stream alone
        // Live with nothing uploaded, as a tablet session is until its end: no series, and no 404 (M11).
        client.get("/api/sessions/$A/series").status shouldBe HttpStatusCode.NoContent
    }

    @Test
    fun `a session's state, at the edge of quiet`() {
        val record = com.obd2dashboard.backend.archive.SessionRecord(A, "yaris", null, null, 10, emptyList(), false, null, 0, clock.instant(), clock.instant())
        val quietFrom = clock.instant().plus(com.obd2dashboard.backend.admin.CarAdmin.UPLOAD_QUIET)
        sessionState(record, null, quietFrom.minusMillis(1)) shouldBe "uploading"
        sessionState(record, null, quietFrom) shouldBe "incomplete"
        sessionState(record, A, quietFrom) shouldBe "live"
        sessionState(record.copy(complete = true), null, clock.instant()) shouldBe "complete"
    }

    @Test
    fun `drives group sessions under 10 minutes apart, end to start`() {
        fun s(id: String, startMin: Long, endMin: Long) = SessionItem(id, startMin * 60_000, endMin * 60_000, 1, "complete")
        drives(emptyList()) shouldBe emptyList()
        val d = drives(listOf(s("a", 0, 30), s("c", 50, 60), s("b", 39, 45)))
        d.map { drive -> drive.sessions.map { it.id } } shouldBe listOf(listOf("c", "b", "a"))
        d.single().started shouldBe 0
        d.single().ended shouldBe 60 * 60_000
        // Exactly 10 minutes apart is a new drive.
        drives(listOf(s("a", 0, 30), s("b", 40, 45))).map { drive -> drive.sessions.map { it.id } } shouldBe listOf(listOf("b"), listOf("a"))
        // A long session covering a short one keeps the drive's end at the longest.
        drives(listOf(s("a", 0, 100), s("b", 5, 10), s("c", 105, 110))).single().sessions.map { it.id } shouldBe listOf("c", "b", "a")
    }

    @Test
    fun `test data is never part of a drive, and doesn't bridge two sessions (M11)`() {
        fun s(id: String, startMin: Long, endMin: Long, source: String? = null) =
            SessionItem(id, startMin * 60_000, endMin * 60_000, 1, "complete", source = source)
        // Tablet sessions group like any other; the fake one stands alone.
        drives(listOf(s("t", 0, 2, "tablet"), s("f", 3, 4, "fake"), s("c", 6, 20)))
            .map { d -> d.sessions.map { it.id } } shouldBe listOf(listOf("f"), listOf("c", "t")) // newest first, by start
        // A fake session between two real ones 15 minutes apart doesn't join them.
        drives(listOf(s("a", 0, 10), s("f", 12, 20, "fake"), s("b", 25, 30)))
            .map { d -> d.sessions.map { it.id } } shouldBe listOf(listOf("b"), listOf("f"), listOf("a"))
    }

    @Test
    fun `a session's source is listed, from its summary once complete, from its header while uploading`() = testApplication {
        app()
        runBlocking {
            upload(A, "yaris", t, source = "tablet")
            upload(B, "yaris", t + Duration.ofMinutes(3).toMillis(), source = "fake")
            upload(C, "yaris", t + Duration.ofMinutes(6).toMillis(), complete = false, source = "tablet")
            upload(D, "yaris", t + Duration.ofMinutes(9).toMillis())
        }
        val sessions = Json.parseToJsonElement(client.get("/api/cars/yaris/sessions").bodyAsText()).jsonArray
            .flatMap { d -> d.jsonObject.getValue("sessions").jsonArray.map { it.jsonObject } }
            .associate { it.getValue("id").jsonPrimitive.content to it["source"]?.jsonPrimitive?.contentOrNull }
        sessions shouldBe mapOf(A to "tablet", B to "fake", C to "tablet", D to null)
    }

    @Test
    fun `a session stored before M11 gains its source from its rebuilt summary`() = testApplication {
        // As the first drive's sessions are stored: no source in the header, a version-1 summary.
        val beforeM11 = object : SessionIndex by index {
            private fun SessionRecord.old() = copy(header = header?.copy(source = null))
            override suspend fun get(id: String) = index.get(id)?.old()
            override suspend fun listByCar(car: String) = index.listByCar(car).map { it.old() }
            override suspend fun list() = index.list().map { it.old() }
        }
        application {
            module(registry, ArchiveService(beforeM11, store, clock), InMemoryLiveHub(clock), clock = clock, messages = testMessages(), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(), admin = config)
        }
        runBlocking {
            upload(A, "yaris", t, source = "tablet")
            index.get(A)?.summary?.let { index.setSummary(A, it.copy(version = 1, source = null)) }
        }
        val item = Json.parseToJsonElement(client.get("/api/cars/yaris/sessions").bodyAsText()).jsonArray
            .single().jsonObject.getValue("sessions").jsonArray.single().jsonObject
        item["source"]?.jsonPrimitive?.contentOrNull shouldBe "tablet"
        index.get(A)!!.summary!!.version shouldBe SessionSummary.VERSION
    }

    private infix fun Set<String>.shouldContain2(key: String) = (key in this) shouldBe true

    private companion object {
        const val A = "a1111111-1111-4111-8111-11111111aaaa"
        const val B = "b2222222-2222-4222-8222-22222222bbbb"
        const val C = "c3333333-3333-4333-8333-33333333cccc"
        const val D = "d4444444-4444-4444-8444-44444444dddd"
    }
}
