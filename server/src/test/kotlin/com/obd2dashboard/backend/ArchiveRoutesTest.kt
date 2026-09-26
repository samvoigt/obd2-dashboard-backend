package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SegmentStore
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.obd2dashboard.backend.live.InMemoryLiveHub
import org.junit.Test

/** Contract §6, section by section, through the real routes and the in-memory stores. */
class ArchiveRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore())
    private val yaris = runBlocking { registry.addCar(Slug.parse("yaris"), "Yaris") }.token
    private val outback = runBlocking { registry.addCar(Slug.parse("outback"), "Outback") }.token
    private val index = InMemorySessionIndex()
    private val store = InMemorySegmentStore()

    private val id = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
    private val session: ByteArray =
        ArchiveRoutesTest::class.java.getResourceAsStream("/session-v3.jsonl")!!.use { it.readBytes() }
    private val lines: List<String> = session.decodeToString().split('\n').dropLast(1)
    private val sha = MessageDigest.getInstance("SHA-256").digest(session).joinToString("") { "%02x".format(it) }
    private val lenient = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.app(segments: SegmentStore = store) {
        application { module(registry, ArchiveService(index, segments), InMemoryLiveHub()) }
    }

    private fun gzip(bytes: ByteArray) =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private fun linesBody(from: Int, count: Int) = lines.subList(from, from + count).joinToString("") { "$it\n" }.toByteArray()

    private suspend fun HttpClient.open(token: String = yaris, sessionId: String = id, body: String = lines[0]) =
        put("/v1/sessions/$sessionId") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.chunk(
        from: Int,
        count: Int,
        token: String = yaris,
        body: ByteArray = gzip(linesBody(from, count)),
        encoding: String? = "gzip",
        recordCount: String? = count.toString(),
        firstIndex: String? = from.toString(),
    ) = post("/v1/sessions/$id/chunks") {
        bearerAuth(token)
        header(HttpHeaders.ContentType, "application/x-ndjson")
        encoding?.let { header(HttpHeaders.ContentEncoding, it) }
        firstIndex?.let { header("X-First-Index", it) }
        recordCount?.let { header("X-Record-Count", it) }
        setBody(body)
    }

    private suspend fun HttpClient.complete(lastIndex: Long = lines.size - 1L, count: Long = lines.size.toLong(), hash: String = sha, token: String = yaris) =
        post("/v1/sessions/$id/complete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"lastIndex": $lastIndex, "recordCount": $count, "sha256": "$hash"}""")
        }

    /** Every 4xx on the archive lane has {error, message, skipChunk} (§6.4). */
    private suspend fun HttpResponse.error(status: HttpStatusCode, code: String): String {
        this.status shouldBe status
        val body = lenient.decodeFromString<ApiError>(bodyAsText())
        body.error shouldBe code
        body.skipChunk shouldBe false
        return body.message
    }

    private suspend fun HttpResponse.acked(): Long {
        status shouldBe HttpStatusCode.OK
        return Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("ackedThrough").jsonPrimitive.content.toLong()
    }

    // §6.1

    @Test
    fun `PUT opens with 201, then 200, idempotently`() = testApplication {
        app()
        client.open().let { it.status shouldBe HttpStatusCode.Created; it.bodyAsText() shouldBe """{"ackedThrough":0}""" }
        client.open().status shouldBe HttpStatusCode.OK
        client.open(body = lines[0] + "\n").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `PUT of a different record for the same session is bad_record`() = testApplication {
        app()
        client.open()
        client.open(body = lines[0].replace("1.0 (42)", "1.1")).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "differs"
    }

    @Test
    fun `another car's token is wrong_car, with the contract's exact body`() = testApplication {
        app()
        client.open()
        val response = client.open(token = outback)
        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText() shouldBe
            """{"error":"wrong_car","message":"Session $id belongs to a different car.","skipChunk":false}"""
        client.chunk(1, 3, token = outback).error(HttpStatusCode.BadRequest, "wrong_car")
        client.complete(token = outback).error(HttpStatusCode.BadRequest, "wrong_car")
    }

    @Test
    fun `a v2 session record, or one for another id, is bad_record`() = testApplication {
        app()
        client.open(body = lines[0].replace("\"v\":3", "\"v\":2")).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "v2"
        client.open(sessionId = "00000000-0000-4000-8000-000000000000").error(HttpStatusCode.BadRequest, "bad_record")
    }

    @Test
    fun `a session record over 64 KiB is 413`() = testApplication {
        app()
        val huge = lines[0].replace("\"app\":\"1.0 (42)\"", "\"app\":\"" + "x".repeat(70_000) + "\"")
        client.open(body = huge).error(HttpStatusCode.PayloadTooLarge, "too_large")
    }

    @Test
    fun `a session id that is not a UUID is bad_record`() = testApplication {
        app()
        client.open(sessionId = "not-a-uuid").error(HttpStatusCode.BadRequest, "bad_record") shouldContain "UUID"
    }

    @Test
    fun `an upper-case id is the same session`() = testApplication {
        app()
        client.open(sessionId = id.uppercase()).status shouldBe HttpStatusCode.Created
        client.open(sessionId = id).status shouldBe HttpStatusCode.OK
    }

    // §6.2

    @Test
    fun `chunks are acked by index, overlaps deduplicated`() = testApplication {
        app()
        client.open()
        client.chunk(1, 10).acked() shouldBe 10
        client.chunk(1, 10).acked() shouldBe 10 // resent after a lost response
        client.chunk(8, 10).acked() shouldBe 17
    }

    @Test
    fun `a chunk past the end is 409 with missingFrom, and the error body too`() = testApplication {
        app()
        client.open()
        client.chunk(1, 5)
        val response = client.chunk(9, 3)
        response.status shouldBe HttpStatusCode.Conflict
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        body.getValue("missingFrom").jsonPrimitive.content shouldBe "6"
        body.getValue("error").jsonPrimitive.content shouldBe "missing"
        body.getValue("skipChunk").jsonPrimitive.content shouldBe "false"
    }

    @Test
    fun `a chunk on a session never opened is 404, so the tablet re-opens`() = testApplication {
        app()
        client.chunk(1, 3).error(HttpStatusCode.NotFound, "not_open")
    }

    @Test
    fun `an uncompressed chunk is accepted`() = testApplication {
        app()
        client.open()
        client.chunk(1, 4, body = linesBody(1, 4), encoding = null).acked() shouldBe 4
    }

    @Test
    fun `malformed chunks are bad_record, each saying why`() = testApplication {
        app()
        client.open()
        client.chunk(1, 3, recordCount = "4").error(HttpStatusCode.BadRequest, "bad_record") shouldContain "X-Record-Count"
        client.chunk(1, 3, firstIndex = null).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "X-First-Index"
        client.chunk(1, 3, firstIndex = "-1").error(HttpStatusCode.BadRequest, "bad_record")
        client.chunk(1, 3, body = gzip(linesBody(1, 3).dropLast(1).toByteArray())).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "end in"
        client.chunk(1, 2, body = gzip("{}\nnope\n".toByteArray())).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "line 2"
        client.chunk(1, 3, body = linesBody(1, 3)).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "gzip"
        client.chunk(1, 3, encoding = "br").error(HttpStatusCode.BadRequest, "bad_record") shouldContain "Content-Encoding"
        client.chunk(1, 0, body = gzip(ByteArray(0))).error(HttpStatusCode.BadRequest, "bad_record")
        index.get(id)!!.ackedThrough shouldBe 0
    }

    @Test
    fun `over 1 MiB uncompressed is 413`() = testApplication {
        app()
        client.open()
        val big = ("{\"type\":\"sample\",\"pad\":\"" + "x".repeat(1000) + "\"}\n").repeat(1100).toByteArray()
        client.chunk(1, 1100, body = gzip(big)).error(HttpStatusCode.PayloadTooLarge, "too_large")
    }

    // §6.3

    @Test
    fun `complete assembles the session byte for byte`() = testApplication {
        app()
        client.open()
        client.chunk(1, 20).acked() shouldBe 20
        client.chunk(21, lines.size - 21).acked() shouldBe lines.size - 1L
        client.complete().let { it.status shouldBe HttpStatusCode.OK; it.bodyAsText() shouldBe """{"complete":true}""" }
        store.objects.getValue(ArchiveService.sessionKey(id)).contentEquals(session) shouldBe true
        client.complete().status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `complete before every line is stored is 409 from the next line`() = testApplication {
        app()
        client.open()
        client.chunk(1, 10)
        Json.parseToJsonElement(client.complete().also { it.status shouldBe HttpStatusCode.Conflict }.bodyAsText())
            .jsonObject.getValue("missingFrom").jsonPrimitive.content shouldBe "11"
    }

    @Test
    fun `a malformed complete body is bad_record`() = testApplication {
        app()
        client.open()
        client.complete(hash = "abc").error(HttpStatusCode.BadRequest, "bad_record") shouldContain "sha256"
        client.post("/v1/sessions/$id/complete") { bearerAuth(yaris); setBody("{}") }.error(HttpStatusCode.BadRequest, "bad_record")
        client.complete(count = 5).error(HttpStatusCode.BadRequest, "bad_record") shouldContain "recordCount"
    }

    // §6.4

    @Test
    fun `no token is 401 on every archive route`() = testApplication {
        app()
        client.put("/v1/sessions/$id") { setBody(lines[0]) }.status shouldBe HttpStatusCode.Unauthorized
        client.post("/v1/sessions/$id/chunks") { setBody("x") }.status shouldBe HttpStatusCode.Unauthorized
        client.post("/v1/sessions/$id/complete") { setBody("{}") }.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `storage being unavailable is 503 with Retry-After, and nothing is acked`() = testApplication {
        val flaky = object : SegmentStore by store {
            var fail = false
            override suspend fun put(key: String, lines: ByteArray) {
                if (fail) throw IOException("storage unreachable")
                store.put(key, lines)
            }
        }
        app(flaky)
        client.open()
        flaky.fail = true
        val response = client.chunk(1, 5)
        response.status shouldBe HttpStatusCode.ServiceUnavailable
        response.headers[HttpHeaders.RetryAfter] shouldBe "30"
        index.get(id)!!.ackedThrough shouldBe 0
        flaky.fail = false
        client.chunk(1, 5).acked() shouldBe 5
    }
}
