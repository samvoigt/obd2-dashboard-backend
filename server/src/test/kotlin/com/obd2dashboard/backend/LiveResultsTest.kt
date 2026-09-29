package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.timing.boxGeoJson
import com.obd2dashboard.backend.timing.boxLap
import com.obd2dashboard.backend.timing.boxLog
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The event page, live (M17.6): results counting sessions still being driven, marked, then complete ones in their place. */
class LiveResultsTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val json = Json { ignoreUnknownKeys = true }
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val outback = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }.token
    private val archive = ArchiveService(InMemorySessionIndex(), InMemorySegmentStore())
    private val courses = InMemoryCourseStore()
    private val events = testEvents()
    private val id = "5ace0000-1111-4111-8111-000000000176"
    private val now = Instant.now()

    private val lines: List<String> = boxLog(0, 160).toMutableList().let { out ->
        listOf(boxLap(1, 1), boxLap(2, 1)).forEachIndexed { i, lap -> out.add(out.indexOfFirst { it.contains("\"fixAt\":${12_500 + 70_000 * (i + 1) + 500}") }, lap) }
        out.mapIndexed { i, l -> l.replace("\"id\":\"s\"", "\"id\":\"$id\"").replace(Regex("\"seq\":\\d+"), "\"seq\":$i") }
    }

    init {
        runBlocking { courses.save("box", 0, "Box", boxGeoJson(), now) }
    }

    private fun event(kind: PartKind) = runBlocking {
        events.events.save(Event("day", "Day", "2026-09-28", "box", "box", listOf("outback"), listOf(Part("p", kind, "Part", now.minusSeconds(3600), now.plusSeconds(3600)))), 0, now)
    }

    private fun ApplicationTestBuilder.app() {
        application { module(registry, archive, InMemoryLiveHub(), LiveConfig(), Clock.systemUTC(), messages = Messages(InMemoryMessageStore()), courses = courses, events = events, crewKey = testCrewKey()) }
    }

    private suspend fun ApplicationTestBuilder.results(): EventResults = json.decodeFromString(client.get("/api/events/day").bodyAsText())

    /** Streams the session live, and waits until the results count [laps] of it. */
    private suspend fun ApplicationTestBuilder.stream(until: suspend (EventResults) -> Boolean, then: suspend () -> Unit) {
        createClient { install(WebSockets) }.webSocket("/v1/live", request = { bearerAuth(outback); header(HttpHeaders.SecWebSocketProtocol, LIVE_PROTOCOL) }) {
            send(Frame.Text("""{"t":"hello","v":3,"device":"tab-1","app":"1.0","wall":1758719312000}"""))
            send(Frame.Text("""{"t":"session","record":${lines[0]}}"""))
            send(Frame.Text("""{"t":"batch","session":"$id","records":[${lines.drop(1).joinToString(",")}]}"""))
            withTimeout(20_000) { while (!until(results())) delay(200) } // the hold may answer for up to 10 s
            then()
        }
    }

    private suspend fun ApplicationTestBuilder.complete() {
        client.put("/v1/sessions/$id") { bearerAuth(outback); contentType(ContentType.Application.Json); setBody(lines[0]) }
        client.post("/v1/sessions/$id/chunks") {
            bearerAuth(outback); header(HttpHeaders.ContentType, "application/x-ndjson")
            header("X-First-Index", "1"); header("X-Record-Count", (lines.size - 1).toString())
            setBody(lines.drop(1).joinToString("") { "$it\n" }.toByteArray())
        }.status shouldBe HttpStatusCode.OK
        val sha = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("") { "$it\n" }.toByteArray()).joinToString("") { "%02x".format(it) }
        client.post("/v1/sessions/$id/complete") {
            bearerAuth(outback); contentType(ContentType.Application.Json)
            setBody("""{"lastIndex": ${lines.size - 1}, "recordCount": ${lines.size}, "sha256": "$sha"}""")
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `practice counts a session being driven, marked live, then complete in its place`() = testApplication {
        event(PartKind.PRACTICE)
        app()
        stream({ r -> r.parts.single().sessions.singleOrNull()?.laps?.size == 2 }) {
            results().let { r ->
                r.parts.single().sessions.single().live shouldBe true
                r.practiceBests.single().lap.time shouldBe 70.0
            }
            complete()
            // The hold is emptied by nothing here: wait it out, as a viewer would.
            delay(10_500)
            results().parts.single().sessions.single().let { it.live shouldBe false; it.laps.size shouldBe 2 }
        }
    }

    @Test
    fun `the race counts the stint being driven, its laps marked live, and once complete counts them once`() = testApplication {
        event(PartKind.RACE)
        app()
        stream({ r -> r.race?.cars?.singleOrNull()?.laps?.size == 2 }) {
            results().race!!.cars.single().laps.map { it.live } shouldBe listOf(true, true)
            complete()
            delay(10_500)
            results().race!!.cars.single().laps.let { laps -> laps.size shouldBe 2; laps.map { it.live } shouldBe listOf(false, false) }
        }
    }

    @Test
    fun `before any session is complete, the flag is placed by the live lane's clock, and an edit shows at once`() = testApplication {
        event(PartKind.RACE)
        runBlocking { registry.setPasscode(Slug.parse("outback"), "outback-crew".toCharArray()); events.drivers.put(com.obd2dashboard.backend.events.Driver("d-sam", "Sam Voigt", "SAM")) }
        app()
        stream({ r -> r.race?.cars?.singleOrNull()?.laps?.size == 2 }) {
            // The green flag at 110 s into the drive, on the tablet's clock: in lap 2 (82.5 to 152.5 s). Its batch's
            // newest `wall` was 160.15 s in, received about now, so the tablet is `now - that` behind.
            val behind = System.currentTimeMillis() - (com.obd2dashboard.backend.timing.BOX_WALL0 + 160_150)
            val green = Instant.ofEpochMilli(com.obd2dashboard.backend.timing.BOX_WALL0 + 110_000 + behind)
            val e = events.events.get("day")!!
            events.events.save(e.copy(parts = e.parts.map { it.copy(green = green) }), e.revision, now)
            withTimeout(20_000) { while (results().race!!.cars.single().greenLap != 2) delay(200) }
            // The crew sets who drove: the results say so at once, not after the hold.
            val cookie = client.post("/api/cars/outback/login") { contentType(ContentType.Application.Json); setBody("""{"passcode":"outback-crew"}""") }
                .headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            results().parts.single().sessions.single().driver shouldBe null
            client.put("/api/cars/outback/sessions/$id/driver") {
                header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Origin, "http://localhost"); header(HttpHeaders.Cookie, cookie)
                contentType(ContentType.Application.Json); setBody("""{"driver":"d-sam"}""")
            }.status shouldBe HttpStatusCode.OK
            results().parts.single().sessions.single().driver!!.code shouldBe "SAM"
        }
    }

    @Test
    fun `results are held 10 s, and emptied by an edit`(): Unit = runBlocking {
        var t = 0L
        val clock = object : Clock() {
            override fun instant() = Instant.ofEpochMilli(t)
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val hold = ResultsHold(clock)
        val e = Event("day", "Day", "2026-09-28", "box", "box", emptyList(), emptyList(), revision = 1)
        var computed = 0
        suspend fun get(ev: Event = e) = hold.get(ev) { computed++; EventResults(PublicEvent(ev.id, "", "", "", "", "", "", emptyList(), emptyList()), emptyList(), emptyList(), emptyList()) }
        get(); get()
        computed shouldBe 1
        t = 9_999; get(); computed shouldBe 1
        t = 10_000; get(); computed shouldBe 2
        get(e.copy(revision = 2)); computed shouldBe 3 // another revision: never the old one's
        hold.clear(); get(); computed shouldBe 4
    }
}
