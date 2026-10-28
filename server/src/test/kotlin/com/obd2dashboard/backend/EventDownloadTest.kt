package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.InMemoryAccessStore
import com.obd2dashboard.backend.admin.InMemoryUserStore
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.DriverChange
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.BOX_WALL0
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** An event's data as one zip (M29): a folder per part, each car's sessions merged, who drove when beside them. */
class EventDownloadTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()
    private val courses = InMemoryCourseStore()
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore())
    private val users = InMemoryUserStore()
    private val access = InMemoryAccessStore()
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { SignIn.Allowed("$it@example.com") },
    )
    private val t0 = Instant.parse("2026-10-04T13:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)
    private fun cookieOf(email: String) = "${AdminAuth.COOKIE}=${AdminAuth(testCrewKey()).issue(email)}"

    private val a = "5ace0000-1111-4111-8111-00000000a029"
    private val b = "5ace0000-1111-4111-8111-00000000b029"
    private val r1 = "5ace0000-1111-4111-8111-00000000c029"
    private val r2 = "5ace0000-1111-4111-8111-00000000d029"
    private val g = "5ace0000-1111-4111-8111-00000000e029"
    private val stored = mutableMapOf<String, String>()

    /** A box session at 40 m/s (144 km/h by GPS), with a VIN in its header. */
    private fun session(id: String, car: String, from: Long, log: List<String>, driver: String?, changes: List<DriverChange> = emptyList()) = runBlocking {
        val speeds = log.mapNotNull { Regex("\"wall\":(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }.drop(1)
            .map { """{"type":"sample","signal":"gps.speed","value":144.0,"seq":0,"at":0,"wall":$it}""" }
        val lines = listOf(log[0].replace("\"id\":\"s\"", "\"id\":\"$id\",\"vin\":\"VIN-$car\"")) + log.drop(1) + speeds
        val text = lines.joinToString("\n", postfix = "\n")
        stored[id] = text
        val header = SessionHeader(id, 3, "2026-09-27T12:00:00Z", "tab", "1", "VIN-$car", null)
        index.create(SessionRecord(id, car, header, null, lines.size - 1L, emptyList(), true, null, 0, at(from), at(from + 10), driver = driver, driverChanges = changes))
        store.put(ArchiveService.sessionKey(id), text.toByteArray())
    }

    init {
        runBlocking {
            users.add(User("ann@example.com", "sam@example.com", Instant.EPOCH))
            users.add(User("bob@example.com", "sam@example.com", Instant.EPOCH))
            registry.addCar(Slug.parse("outback"), "Outback")
            registry.addCar(Slug.parse("gti"), "GTI")
            access.set(Thing(Kind.CAR, "outback"), Access("ann@example.com"))
            access.set(Thing(Kind.CAR, "gti"), Access("sam@example.com"))
            courses.save("box", 0, "The box", boxGeoJson(), Instant.EPOCH)
            stores.drivers.put(Driver("d-sam", "Sam Voigt", "SAM"))
            stores.drivers.put(Driver("d-alex", "Alex, \"Al\" Rider", "ALE"))
            stores.events.save(Event("box-weekend", "Box weekend", "2026-10-04", "box", "box", listOf("outback", "gti"), listOf(
                Part("p1", PartKind.PRACTICE, "Practice", at(0), at(60)),
                Part("p2", PartKind.RACE, "Race session 1", at(60), at(240)),
                Part("p3", PartKind.RACE, "Race session 2", at(1440), at(1600)),
            ), endDate = "2026-10-05"), 0, t0)
        }
        // Practice in two sessions back to back; race session 1 with a driver change; race session 2 the next day.
        session(a, "outback", 10, boxLog(0, 150, device = "tab-a"), "d-sam")
        session(b, "outback", 20, boxLog(160, 300, device = "tab-a"), "d-alex")
        session(g, "gti", 15, boxLog(0, 300, device = "tab-g"), null)
        session(r1, "outback", 70, boxLog(0, 300, device = "tab-r"), "d-sam", listOf(DriverChange(BOX_WALL0 + 150_000, "d-alex")))
        session(r2, "outback", 1450, boxLog(0, 300, device = "tab-s"), "d-alex")
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(index, store), InMemoryLiveHub(),
                messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = courses, events = stores,
                users = users, access = access)
        }
    }

    private suspend fun ApplicationTestBuilder.download(cookie: String?) =
        client.get("/api/admin/events/box-weekend/download") {
            header(HttpHeaders.Host, "localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name.removePrefix("box-weekend/")] = zip.readBytes().decodeToString()
            }
        }
        return out
    }

    private fun csv(text: String): List<Map<String, String>> {
        // Enough of CSV for these: quoted cells with commas and doubled quotes.
        fun cells(line: String): List<String> {
            val cells = mutableListOf<String>()
            var i = 0
            while (i <= line.length) {
                if (i < line.length && line[i] == '"') {
                    val sb = StringBuilder()
                    i++
                    while (true) {
                        if (line[i] == '"' && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2 }
                        else if (line[i] == '"') { i++; break }
                        else sb.append(line[i++])
                    }
                    cells += sb.toString()
                    i++
                } else {
                    val end = line.indexOf(',', i).let { if (it < 0) line.length else it }
                    cells += line.substring(i, end)
                    i = end + 1
                }
            }
            return cells
        }
        val rows = text.trimEnd().split("\r\n").map(::cells)
        return rows.drop(1).map { rows[0].zip(it).toMap() }
    }

    @Test
    fun `not signed in, or no car of yours, gets nothing`() = testApplication {
        app()
        download(null).status shouldBe HttpStatusCode.Unauthorized
        download(cookieOf("bob@example.com")).status shouldBe HttpStatusCode.Forbidden
        client.get("/api/admin/events/nope/download") { header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Cookie, cookieOf("sam@example.com")) }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `a folder per part, each car's sessions merged line for line, the VIN in it`() = testApplication {
        app()
        val response = download(cookieOf("ann@example.com"))
        response.status shouldBe HttpStatusCode.OK
        response.headers[HttpHeaders.ContentDisposition]!! shouldContain "box-weekend-data.zip"
        val files = unzip(response.bodyAsBytes())
        files.keys.toList() shouldBe listOf(
            "README.txt", "event.json", "results.json",
            "1-practice/outback/raw.jsonl", "1-practice/outback/sessions.json", "1-practice/outback/laps.csv",
            "2-race-session-1/outback/raw.jsonl", "2-race-session-1/outback/sessions.json", "2-race-session-1/outback/laps.csv", "2-race-session-1/outback/stints.csv",
            "3-race-session-2/outback/raw.jsonl", "3-race-session-2/outback/sessions.json", "3-race-session-2/outback/laps.csv", "3-race-session-2/outback/stints.csv",
        )
        files.getValue("1-practice/outback/raw.jsonl") shouldBe stored.getValue(a) + stored.getValue(b)
        files.getValue("2-race-session-1/outback/raw.jsonl") shouldBe stored.getValue(r1)
        files.getValue("1-practice/outback/raw.jsonl") shouldContain "VIN-outback"
        // Ann can't edit the GTI: left out, and said so; its laps are public, in the results.
        files.keys.none { "gti" in it } shouldBe true
        files.values.none { "VIN-gti" in it } shouldBe true
        files.getValue("README.txt") shouldContain "Left out, as you can't edit them: GTI."

        val sessions = Json.parseToJsonElement(files.getValue("1-practice/outback/sessions.json")).jsonArray.map { it.jsonObject }
        sessions.map { it.getValue("id").jsonPrimitive.content } shouldBe listOf(a, b)
        sessions[0].getValue("vin").jsonPrimitive.content shouldBe "VIN-outback"
        sessions[1].getValue("driver").jsonObject.getValue("code").jsonPrimitive.content shouldBe "ALE"
        val race1 = Json.parseToJsonElement(files.getValue("2-race-session-1/outback/sessions.json")).jsonArray.single().jsonObject
        race1.getValue("driverChanges").jsonArray.single().jsonObject.getValue("from").jsonPrimitive.content shouldBe "${BOX_WALL0 + 150_000}"

        val event = Json.parseToJsonElement(files.getValue("event.json")).jsonObject
        event.getValue("parts").jsonArray.map { it.jsonObject.getValue("folder").jsonPrimitive.content } shouldBe
            listOf("1-practice", "2-race-session-1", "3-race-session-2")
        event.getValue("included").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("outback")
        event.getValue("leftOut").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("gti")
        event.getValue("drivers").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content } shouldBe listOf("ALE", "SAM")
    }

    @Test
    fun `each lap with its driver, before and after a change, and its speeds`() = testApplication {
        app()
        val files = unzip(download(cookieOf("ann@example.com")).bodyAsBytes())
        val practice = csv(files.getValue("1-practice/outback/laps.csv"))
        practice.map { it.getValue("driver_code") }.toSet() shouldBe setOf("SAM", "ALE")
        practice.first { it.getValue("driver_code") == "ALE" }.getValue("driver_name") shouldBe "Alex, \"Al\" Rider"
        val race = csv(files.getValue("2-race-session-1/outback/laps.csv"))
        race.forEach { lap ->
            val driver = if (lap.getValue("end_ms").toLong() > BOX_WALL0 + 150_000) "ALE" else "SAM"
            lap.getValue("driver_code") shouldBe driver
        }
        race.map { it.getValue("driver_code") } shouldContainAll listOf("SAM", "ALE")
        race.map { it.getValue("lap") } shouldBe (1..race.size).map { it.toString() }
        // Box laps at 40 m/s: 144 km/h by GPS, top, lowest and on average.
        val whole = race.first { it.getValue("pit_in") == "false" && it.getValue("pit_out") == "false" }
        whole.getValue("top_kmh") shouldBe "144.0"
        whole.getValue("low_kmh") shouldBe "144.0"
        (whole.getValue("avg_kmh").toDouble() in 140.0..145.0) shouldBe true
        // Race session 2 goes on numbering from session 1's last lap.
        csv(files.getValue("3-race-session-2/outback/laps.csv")).first().getValue("lap") shouldBe "${race.size + 1}"
        val stints = csv(files.getValue("2-race-session-1/outback/stints.csv"))
        stints.map { it.getValue("driver_code") } shouldBe listOf("SAM", "ALE")
    }

    @Test
    fun `a master admin gets every car`() = testApplication {
        app()
        val files = unzip(download(cookieOf("sam@example.com")).bodyAsBytes())
        files.getValue("1-practice/gti/raw.jsonl") shouldBe stored.getValue(g)
        files.getValue("README.txt") shouldNotContain "Left out"
    }

    @Test
    fun `a merged session's practice row exports each lap under its member, and sessions json names the merge (M33)`() {
        val part = com.obd2dashboard.backend.events.Part("p1", com.obd2dashboard.backend.events.PartKind.PRACTICE, "Practice", java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(3600))
        fun lap(n: Int, session: String) = StandingLap(n, 70.0, emptyList(), false, false, n * 70_000L, (n + 1) * 70_000L, "retimed", session = session)
        val row = SessionResult("merge-1", "outback", null, 0, listOf(lap(1, "s-a"), lap(2, "s-b")))
        val results = EventResults(
            PublicEvent("e", "E", "2026-10-04", "box", "Box", "box", "Box", emptyList(), emptyList()),
            listOf(PartResults(PublicPart("p1", "practice", "Practice", 0, 3_600_000), listOf(row), emptyList(), emptyList())),
            emptyList(), emptyList(),
        )
        EventExport.laps(part, "outback", setOf("s-a", "s-b"), results, null, emptyMap()).map { it.session to it.lap } shouldBe listOf("s-a" to 1, "s-b" to 2)
        // Only the asked-for member's laps when the car's sessions there are fewer.
        EventExport.laps(part, "outback", setOf("s-b"), results, null, emptyMap()).map { it.session } shouldBe listOf("s-b")
        val record = com.obd2dashboard.backend.archive.SessionRecord("s-a", "outback", null, null, 0, emptyList(), true, null, 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH)
        EventExport.exportSession(record, emptyMap(), "merge-1").merged shouldBe "merge-1"
        EventExport.exportSession(record, emptyMap()).merged shouldBe null
    }

}
