package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The crew's message API (M5.5), and the whole path from the website to the tablet and back. */
class MessageRoutesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val yarisToken = runBlocking {
        registry.addCar(Slug.parse("outback"), "Outback")
        registry.setPasscode(Slug.parse("outback"), "outback-crew".toCharArray())
        registry.addCar(Slug.parse("yaris"), "Yaris").also { registry.setPasscode(Slug.parse("yaris"), "pit-lane".toCharArray()) }.token
    }
    private val messages = Messages(InMemoryMessageStore())

    private fun ApplicationTestBuilder.app() {
        application { module(registry, testArchive(), InMemoryLiveHub(), messages = messages, crewKey = testCrewKey()) }
    }

    private suspend fun ApplicationTestBuilder.cookie(car: String = "yaris", passcode: String = "pit-lane"): String =
        client.post("/api/cars/$car/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"passcode":"$passcode"}""")
        }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.send(body: String, cookie: String?) = client.post("/api/cars/yaris/messages") {
        cookie?.let { header(HttpHeaders.Cookie, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `without a login every message endpoint is 401, and another car's login does not count`() = testApplication {
        app()
        send("""{"text":"PIT NOW","preset":"pit"}""", null).status shouldBe HttpStatusCode.Unauthorized
        client.get("/api/cars/yaris/messages").status shouldBe HttpStatusCode.Unauthorized
        client.delete("/api/cars/yaris/messages/m_1").status shouldBe HttpStatusCode.Unauthorized
        val outback = cookie("outback", "outback-crew").replace("crew_outback", "crew_yaris")
        send("""{"text":"PIT NOW"}""", outback).status shouldBe HttpStatusCode.Unauthorized
        messages.active("yaris") shouldBe emptyList()
    }

    @Test
    fun `a crew send is created with its lifetime, and bad ones are refused`() = testApplication {
        app()
        val c = cookie()
        val created = send("""{"text":"  BOX THIS LAP ","preset":"box","ttlSeconds":120}""", c)
        created.status shouldBe HttpStatusCode.Created
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        body.getValue("text").jsonPrimitive.content shouldBe "BOX THIS LAP"
        body.getValue("state").jsonPrimitive.content shouldBe "queued"
        (body.getValue("expiresAt").jsonPrimitive.content.toLong() - body.getValue("sentAt").jsonPrimitive.content.toLong()) shouldBe 120_000
        send("""{"text":"PIT"}""", c).let { Json.parseToJsonElement(it.bodyAsText()).jsonObject }.let {
            (it.getValue("expiresAt").jsonPrimitive.content.toLong() - it.getValue("sentAt").jsonPrimitive.content.toLong()) shouldBe 1_800_000
        }
        for (bad in listOf("""{"text":""}""", """{"text":"${"x".repeat(41)}"}""", """{"text":"x","preset":"teleport"}""",
            """{"text":"x","ttlSeconds":59}""", """{"text":"x","ttlSeconds":1801}""")) {
            send(bad, c).let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldContain "bad_message"
            }
        }
        client.post("/api/cars/yaris/messages") {
            header(HttpHeaders.Cookie, c)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("text=PIT")
        }.status shouldBe HttpStatusCode.UnsupportedMediaType
    }

    @Test
    fun `clear and the recent list`() = testApplication {
        app()
        val c = cookie()
        val id = Json.parseToJsonElement(send("""{"text":"PUSH","preset":"push"}""", c).bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
        client.delete("/api/cars/yaris/messages/$id") { header(HttpHeaders.Cookie, c) }.let {
            it.status shouldBe HttpStatusCode.OK
            it.bodyAsText() shouldContain "\"state\":\"cleared\""
        }
        client.delete("/api/cars/yaris/messages/$id") { header(HttpHeaders.Cookie, c) }.status shouldBe HttpStatusCode.NotFound
        client.delete("/api/cars/yaris/messages/m_0000000000000000") { header(HttpHeaders.Cookie, c) }.status shouldBe HttpStatusCode.NotFound
        client.get("/api/cars/yaris/messages") { header(HttpHeaders.Cookie, c) }.bodyAsText() shouldContain id
    }

    // The whole path, on real Netty: an SSE stream must stream (M4.4).

    private class Stream(http: HttpClient, uri: String, cookie: String?) {
        private val raw = StringBuilder()
        val lines = LinkedBlockingQueue<String>()
        init {
            val request = HttpRequest.newBuilder(URI.create(uri)).apply { cookie?.let { header("Cookie", it) } }.build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
            thread(isDaemon = true) { runCatching { response.body().forEach { synchronized(raw) { raw.append(it).append('\n') }; lines += it } } }
        }
        fun text() = synchronized(raw) { raw.toString() }
        fun waitFor(what: String) {
            val until = System.currentTimeMillis() + 10_000
            while (!text().contains(what)) { check(System.currentTimeMillis() < until) { "no \"$what\" within 10 s" }; Thread.sleep(20) }
        }
    }

    @Test
    fun `from the crew's send to the tablet and back, and never on a public stream`() {
        val hub = InMemoryLiveHub()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            module(registry, ArchiveService(InMemorySessionIndex(), InMemorySegmentStore()), hub, LiveConfig(), Clock.systemUTC(),
                messages = messages, crewKey = testCrewKey())
        }.start()
        try {
            val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            val http = HttpClient.newHttpClient()
            val login = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/cars/yaris/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"passcode":"pit-lane"}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val cookie = login.headers().firstValue("set-cookie").get().substringBefore(';')

            // The tablet.
            val frames = LinkedBlockingQueue<String>()
            val ws = http.newWebSocketBuilder().header("Authorization", "Bearer $yarisToken").subprotocols(LIVE_PROTOCOL)
                .buildAsync(URI.create(base.replace("http", "ws") + "/v1/live"), object : WebSocket.Listener {
                    override fun onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                        frames += data.toString(); w.request(1); return CompletableFuture.completedFuture(null)
                    }
                }).get(10, TimeUnit.SECONDS)
            ws.sendText("""{"t":"hello","v":3}""", true).get()
            repeat(2) { frames.poll(10, TimeUnit.SECONDS)!! } // welcome, messages

            val crewStream = Stream(http, "$base/api/cars/yaris/live", cookie)
            val publicStream = Stream(http, "$base/api/cars/yaris/live", null)
            crewStream.waitFor("event: messages")

            // The crew sends; the tablet gets it, and reports.
            val sent = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/cars/yaris/messages")).header("Cookie", cookie)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"text":"PIT NOW","preset":"pit"}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            sent.statusCode() shouldBe 201
            val id = Json.parseToJsonElement(sent.body()).jsonObject.getValue("id").jsonPrimitive.content
            frames.poll(10, TimeUnit.SECONDS)!! shouldContain "\"t\":\"message\""
            ws.sendText("""{"t":"received","id":"$id"}""", true).get()
            crewStream.waitFor("\"state\":\"received\"")
            ws.sendText("""{"t":"displayed","id":"$id"}""", true).get()
            crewStream.waitFor("\"state\":\"displayed\"")

            // Meanwhile the public stream kept flowing (a status and a keep-alive would do), with no trace of it.
            Thread.sleep(300)
            publicStream.text() shouldContain "event: snapshot"
            publicStream.text() shouldNotContain "PIT NOW"
            publicStream.text() shouldNotContain id
            publicStream.text() shouldNotContain "event: message\n"
            crewStream.text() shouldContain "PIT NOW"
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get()
        } finally {
            server.stop(100, 1_000)
        }
    }
}
