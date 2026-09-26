package com.obd2dashboard.backend.replay

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ReplayOptions(
    /** Chunk by this much log time (`at`), as the tablet ships every 2 minutes; null to chunk by [chunkLines]. */
    val chunkMillis: Long? = 120_000,
    val chunkLines: Int = 2_000,
    /** 0 sends as fast as the server answers; 1 waits as long as the drive took. */
    val speed: Double = 0.0,
    /** Chance of discarding an answer and resending, as a response lost on a bad link. */
    val loseResponses: Double = 0.0,
    /** Chance of sending an acknowledged chunk again. */
    val duplicate: Double = 0.0,
    /** Stop after this many acknowledged chunks, keeping the position, as an app that was killed. */
    val stopAfter: Int? = null,
    /** The most a chunk may hold, uncompressed: the contract's 1 MiB (§6.2). Raised only to test the server's `413`. */
    val maxChunkBytes: Int = 1 shl 20,
    /** Multiplies every wait (Retry-After, backoff), so tests need not sleep. */
    val waitScale: Double = 1.0,
    val random: Random = Random.Default,
)

sealed interface ReplayResult {
    data class Completed(val id: String, val sha256: String, val lines: Int) : ReplayResult
    data class Stopped(val id: String, val ackedThrough: Long) : ReplayResult
    data class Failed(val id: String, val reason: String) : ReplayResult
}

/**
 * One session uploaded as the tablet uploads it (contract §6), with its
 * reactions to every status (§6.4) and faults on demand.
 *
 * The position is saved after every acknowledgement, in [workDir], so a later
 * run resumes where a stopped one left off, as the tablet resumes after a restart.
 * **The token is never printed**, logged or written anywhere.
 */
class Replayer(
    private val server: URI,
    private val token: String,
    private val workDir: Path,
    private val options: ReplayOptions = ReplayOptions(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
    private val log: (String) -> Unit = {},
) {
    @Serializable
    private data class State(val id: String, val ackedThrough: Long, val chunks: Int)

    suspend fun replay(file: SessionFile): ReplayResult {
        Files.createDirectories(workDir)
        val stateFile = workDir.resolve("${file.id}.state.json")
        workDir.resolve("${file.id}.v3.jsonl").let { if (!Files.exists(it)) Files.write(it, file.bytes()) }
        var state = loadState(stateFile) ?: State(file.id, 0, 0)
        val base = "$server/v1/sessions/${file.id}"
        val started = System.nanoTime()
        val firstAt = file.at.firstNotNullOfOrNull { it }

        open(base, file)?.let { return ReplayResult.Failed(file.id, it) }
        var position = state.ackedThrough + 1
        var lineLimit = options.chunkLines
        var chunksThisRun = 0

        while (true) {
            while (position <= file.lastIndex) {
                val end = chunkEnd(file, position.toInt(), lineLimit)
                waitForLogTime(file, end, firstAt, started)
                log("chunk $position..$end")
                val response = sendChunk(base, file, position.toInt(), end) ?: return failed(file, "no answer")
                when (response.statusCode()) {
                    200 -> {
                        val acked = longField(response.body(), "ackedThrough") ?: return failed(file, "no ackedThrough")
                        if (options.random.nextDouble() < options.duplicate) {
                            val again = sendChunk(base, file, position.toInt(), end)
                            val againAcked = again?.let { longField(it.body(), "ackedThrough") }
                            if (again?.statusCode() != 200 || againAcked == null || againAcked < acked) {
                                return failed(file, "a duplicate chunk was answered ${again?.statusCode()} ${again?.body()}")
                            }
                        }
                        position = acked + 1
                        lineLimit = options.chunkLines
                        chunksThisRun++
                        state = State(file.id, acked, state.chunks + 1)
                        saveState(stateFile, state)
                        if (options.stopAfter != null && chunksThisRun >= options.stopAfter) {
                            log("stopped after $chunksThisRun chunks at line $acked")
                            return ReplayResult.Stopped(file.id, acked)
                        }
                    }
                    409 -> {
                        position = longField(response.body(), "missingFrom") ?: return failed(file, "409 without missingFrom")
                        log("409: resending from $position")
                    }
                    413 -> {
                        lineLimit = maxOf(1, (end - position.toInt() + 1) / 2)
                        log("413: halving to $lineLimit lines")
                    }
                    404 -> {
                        log("404: re-opening")
                        open(base, file)?.let { return ReplayResult.Failed(file.id, it) }
                    }
                    else -> handleOther(response)?.let { return ReplayResult.Failed(file.id, it) }
                }
            }
            val done = send(
                HttpRequest.newBuilder(URI.create("$base/complete"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        """{"lastIndex":${file.lastIndex},"recordCount":${file.lines.size},"sha256":"${file.sha256()}"}""",
                    )),
            ) ?: return failed(file, "no answer to complete")
            when (done.statusCode()) {
                200 -> {
                    log("complete: ${file.lines.size} lines, sha256 ${file.sha256()}")
                    return ReplayResult.Completed(file.id, file.sha256(), file.lines.size)
                }
                409 -> position = longField(done.body(), "missingFrom") ?: return failed(file, "409 without missingFrom")
                404 -> open(base, file)?.let { return ReplayResult.Failed(file.id, it) }
                else -> handleOther(done)?.let { return ReplayResult.Failed(file.id, it) }
            }
        }
    }

    /** `PUT`s line 0 until it is accepted; returns why not, if it never is. */
    private suspend fun open(base: String, file: SessionFile): String? {
        repeat(MAX_ATTEMPTS) {
            val response = send(
                HttpRequest.newBuilder(URI.create(base))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(file.lines[0])),
            ) ?: return "no answer to PUT"
            when (response.statusCode()) {
                200, 201 -> return null
                else -> handleOther(response)?.let { return it }
            }
        }
        return "PUT never accepted"
    }

    /**
     * §6.4 for everything but 200/404/409/413: `401`/`403` and `400` stop; `429`/`503`
     * wait `Retry-After`; other `5xx` back off. Returns why it stopped, or null to retry.
     */
    private suspend fun handleOther(response: HttpResponse<String>): String? = when (val code = response.statusCode()) {
        401, 403 -> "the token was refused ($code); it may have been rotated"
        400 -> "refused: ${response.body()}"
        429, 503 -> {
            val seconds = response.headers().firstValue("Retry-After").map { it.toLongOrNull() }.orElse(null) ?: 5
            log("$code: waiting ${seconds}s")
            pause(seconds * 1000)
            null
        }
        in 500..599 -> {
            log("$code: backing off")
            pause(2_000)
            null
        }
        else -> "unexpected $code: ${response.body()}"
    }

    private suspend fun sendChunk(base: String, file: SessionFile, from: Int, end: Int): HttpResponse<String>? {
        val body = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gz -> for (i in from..end) { gz.write(file.lines[i]); gz.write('\n'.code) } }
        }.toByteArray()
        return send(
            HttpRequest.newBuilder(URI.create("$base/chunks"))
                .header("Content-Type", "application/x-ndjson")
                .header("Content-Encoding", "gzip")
                .header("X-First-Index", from.toString())
                .header("X-Record-Count", (end - from + 1).toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)),
        )
    }

    /** Sends, and with [ReplayOptions.loseResponses] discards the answer and sends again, as a lost response does. */
    private suspend fun send(request: HttpRequest.Builder): HttpResponse<String>? {
        val built = request.header("Authorization", "Bearer $token").timeout(Duration.ofSeconds(60)).build()
        repeat(MAX_ATTEMPTS) {
            val response = runCatching { http.sendAsync(built, HttpResponse.BodyHandlers.ofString()).await() }
                .getOrElse { e -> log("network: ${e.javaClass.simpleName}; retrying"); pause(2_000); return@repeat }
            if (options.random.nextDouble() < options.loseResponses) {
                log("(losing the answer to ${built.method()} ${built.uri().path.substringAfterLast('/')})")
                return@repeat
            }
            return response
        }
        return null
    }

    /** The last line of a chunk from [from]: by log time, [limit] lines, and 1 MiB, whichever comes first. */
    private fun chunkEnd(file: SessionFile, from: Int, limit: Int): Int {
        val startAt = file.at[from]
        var end = from
        var bytes = file.lines[from].size + 1
        while (end + 1 <= file.lastIndex && end + 1 - from < limit) {
            val nextBytes = bytes + file.lines[end + 1].size + 1
            if (nextBytes > options.maxChunkBytes) break
            val nextAt = file.at[end + 1]
            if (options.chunkMillis != null && startAt != null && nextAt != null && nextAt - startAt >= options.chunkMillis) break
            end++
            bytes = nextBytes
        }
        return end
    }

    private suspend fun waitForLogTime(file: SessionFile, end: Int, firstAt: Long?, startedNanos: Long) {
        if (options.speed <= 0.0 || firstAt == null) return
        val at = file.at[end] ?: return
        val dueMillis = ((at - firstAt) / options.speed).toLong()
        val elapsed = (System.nanoTime() - startedNanos) / 1_000_000
        if (dueMillis > elapsed) delay(dueMillis - elapsed)
    }

    private suspend fun pause(millis: Long) = delay((millis * options.waitScale).toLong())

    private fun failed(file: SessionFile, reason: String) = ReplayResult.Failed(file.id, reason).also { log("failed: $reason") }

    private suspend fun loadState(file: Path): State? = withContext(Dispatchers.IO) {
        if (Files.exists(file)) Json.decodeFromString<State>(Files.readString(file)) else null
    }

    private suspend fun saveState(file: Path, state: State) = withContext(Dispatchers.IO) {
        Files.writeString(file, Json.encodeToString(State.serializer(), state))
    }

    private companion object {
        const val MAX_ATTEMPTS = 20

        fun longField(body: String, name: String): Long? =
            runCatching { Json.parseToJsonElement(body).jsonObject[name]?.jsonPrimitive?.longOrNull }.getOrNull()
    }
}
