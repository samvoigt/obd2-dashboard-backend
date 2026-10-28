package com.obd2dashboard.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemoryMergeStore
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.slf4j.LoggerFactory

/** Merging, extending, unmerging, naming and deleting merged sessions (M33.3). */
class MergeRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val merges = InMemoryMergeStore()
    private val stores = EventStores(InMemoryDriverStore(), InMemoryEventStore(), merges)
    private val archive = ArchiveService(sessions, InMemorySegmentStore())
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        identity = IdentityVerifier { if (it == "good") SignIn.Allowed("sam@example.com") else SignIn.Refused(Refusal.BadSignature) },
    )
    private val log = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = (LoggerFactory.getLogger("admin") as Logger).apply { addAppender(log) }
    private val json = Json { ignoreUnknownKeys = true }
    private val t0 = Instant.parse("2026-10-17T13:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)
    private fun id(n: Int) = "5ace0000-1111-4111-8111-0000000033%02d".format(n)

    /** Practice: 1 (0–20 min), 2 (20–40), 7 (40–59) back to back; Practice 2: 3 (61–200) and 6 (140–260, overlapping 3 by an hour); 4 uploading; 5 yaris's. */
    init {
        runBlocking {
            registry.addCar(Slug.parse("outback"), "Outback")
            registry.addCar(Slug.parse("yaris"), "Yaris")
            fun session(n: Int, from: Long, to: Long, car: String = "outback", complete: Boolean = true) = runBlocking {
                sessions.create(SessionRecord(id(n), car, SessionHeader(id(n), 3, at(from).toString(), "tab", null, null, null, null), null, 3, emptyList(), complete, null, 0, at(from), at(to),
                    summary = if (complete) SessionSummary(SessionSummary.VERSION, at(from).toEpochMilli(), at(to).toEpochMilli(), 4, emptyList(), track = null, layout = null, laps = 0, bestLap = null, faults = emptyList(), gaps = 0, missed = 0, unreadable = 0, device = "tab") else null))
            }
            session(1, 0, 20); session(2, 20, 40); session(7, 40, 59); session(3, 61, 200); session(4, 300, 310, complete = false); session(5, 0, 40, car = "yaris")
            session(6, 140, 260)
            stores.events.save(Event("weekend", "The weekend", "2026-10-17", "nhms", "road", listOf("outback"), listOf(
                Part("p1", PartKind.PRACTICE, "Practice", at(0), at(60)),
                Part("p2", PartKind.PRACTICE, "Practice 2", at(60), at(240)),
            )), 0, t0)
        }
    }

    @After
    fun detach() {
        logger.detachAppender(log)
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, archive, InMemoryLiveHub(), messages = testMessages(), crewKey = testCrewKey(), admin = config, courses = testCourses(), events = stores)
        }
    }

    private suspend fun ApplicationTestBuilder.admin(): String =
        client.post("/api/admin/login") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("""{"credential":"good"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, cookie: String?, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        return when (method) {
            "POST" -> client.post(path, build)
            "PUT" -> client.put(path, build)
            else -> client.delete(path, build)
        }
    }

    private suspend fun ApplicationTestBuilder.merge(cookie: String?, vararg n: Int) =
        send("POST", "/api/admin/cars/outback/merges", cookie, """{"members":[${n.joinToString(",") { "\"${id(it)}\"" }}]}""")

    /** Each part's session ids, as the public results say. */
    private suspend fun ApplicationTestBuilder.parts(): Map<String, List<String>> =
        json.decodeFromString<EventResults>(client.get("/api/events/weekend").bodyAsText()).parts.associate { p -> p.part.id to p.sessions.map { it.id } }

    @Test
    fun `merge, extend and unmerge, each clearing the results' hold`() = testApplication {
        app()
        val a = admin()
        parts() shouldBe mapOf("p1" to listOf(id(1), id(2), id(7)), "p2" to listOf(id(3), id(6))) // now held for 10 s
        val made = merge(a, 2, 1)
        made.status shouldBe HttpStatusCode.Created
        val view = json.decodeFromString<MergeView>(made.bodyAsText())
        view.members shouldBe listOf(id(1), id(2)) // time order, whatever the request's
        log.list.map { it.formattedMessage } shouldContain "sessions merged: [${id(1)}, ${id(2)}] of outback as ${view.id} by sam@example.com"
        // Seen at once, though held: one row for the merge (M33.6).
        parts() shouldBe mapOf("p1" to listOf(view.id, id(7)), "p2" to listOf(id(3), id(6)))
        val extended = send("POST", "/api/admin/cars/outback/merges", a, """{"into":"${view.id}","add":["${id(7)}"]}""")
        extended.status shouldBe HttpStatusCode.OK
        json.decodeFromString<MergeView>(extended.bodyAsText()).let { it.id shouldBe view.id; it.members shouldBe listOf(id(1), id(2), id(7)) }
        // Extending into another part is refused as merging is.
        send("POST", "/api/admin/cars/outback/merges", a, """{"into":"${view.id}","add":["${id(3)}"]}""").status shouldBe HttpStatusCode.Conflict
        parts()["p1"] shouldBe listOf(view.id)
        send("POST", "/api/admin/merges/${view.id}/split", a).status shouldBe HttpStatusCode.OK
        runBlocking { merges.get(view.id)!!.split } shouldBe true
        parts()["p1"] shouldBe listOf(id(1), id(2), id(7))
        // Its sessions are free again; the split one can't be split twice.
        merge(a, 1, 2).status shouldBe HttpStatusCode.Created
        send("POST", "/api/admin/merges/${view.id}/split", a).status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `refusals say why, naming the sessions`() = testApplication {
        app()
        val a = admin()
        merge(a, 1).status shouldBe HttpStatusCode.Conflict
        val uploading = merge(a, 3, 4)
        uploading.status shouldBe HttpStatusCode.Conflict
        json.decodeFromString<MergeRefused>(uploading.bodyAsText()).problems.first().let {
            it.message shouldBe "One of them is still live or uploading."
            it.sessions shouldBe listOf(id(4))
        }
        json.decodeFromString<MergeRefused>(merge(a, 2, 3).bodyAsText()).message shouldBe "These are in different parts of the event: Practice and Practice 2."
        json.decodeFromString<MergeRefused>(merge(a, 3, 6).bodyAsText()).message shouldBe "Two of them overlap by 1 h 00 min: they can't be one drive."
        merge(a, 1, 5).status shouldBe HttpStatusCode.NotFound // another car's session
        merge(a, 1, 2).status shouldBe HttpStatusCode.Created
        json.decodeFromString<MergeRefused>(merge(a, 2, 3).bodyAsText()).problems.map { it.message } shouldContain "One of them is in a merged session already."
    }

    @Test
    fun `not signed in, or from another site, nothing changes`() = testApplication {
        app()
        merge(null, 1, 2).status shouldBe HttpStatusCode.Unauthorized
        val a = admin()
        client.post("/api/admin/cars/outback/merges") {
            header(HttpHeaders.Host, "localhost"); header(HttpHeaders.Origin, "https://evil.example"); header(HttpHeaders.Cookie, a)
            contentType(ContentType.Application.Json); setBody("""{"members":["${id(1)}","${id(2)}"]}""")
        }.status shouldBe HttpStatusCode.Forbidden
        runBlocking { merges.ofCar("outback") } shouldBe emptyList()
    }

    @Test
    fun `a merged session is named by its id, and its drivers aren't`() = testApplication {
        app()
        val a = admin()
        val view = json.decodeFromString<MergeView>(merge(a, 1, 2).bodyAsText())
        send("PUT", "/api/admin/sessions/${view.id}/name", a, """{"name":"Saturday"}""").status shouldBe HttpStatusCode.OK
        runBlocking { merges.get(view.id)!!.name } shouldBe "Saturday"
        send("PUT", "/api/admin/sessions/${view.id}/driver", a, """{"driver":null}""").status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `deleting a merged session deletes every member, a member alone is refused`() = testApplication {
        app()
        val a = admin()
        val view = json.decodeFromString<MergeView>(merge(a, 1, 2).bodyAsText())
        parts() shouldBe mapOf("p1" to listOf(view.id, id(7)), "p2" to listOf(id(3), id(6))) // now held for 10 s
        send("DELETE", "/api/admin/sessions/${id(1)}", a).let {
            it.status shouldBe HttpStatusCode.Conflict
            it.bodyAsText().contains("part of a merged session") shouldBe true
        }
        runBlocking { archive.session(id(1)) }?.id shouldBe id(1)
        send("DELETE", "/api/admin/sessions/${view.id}", a).status shouldBe HttpStatusCode.NoContent
        runBlocking { listOf(archive.session(id(1)), archive.session(id(2)), merges.get(view.id)) } shouldBe listOf(null, null, null)
        log.list.map { it.formattedMessage } shouldContain "merged session deleted: ${view.id} of outback ([${id(1)}, ${id(2)}]) by sam@example.com"
        // Held for 10 s, yet the next read sees the change: the delete cleared the hold.
        parts() shouldBe mapOf("p1" to listOf(id(7)), "p2" to listOf(id(3), id(6)))
    }
}
