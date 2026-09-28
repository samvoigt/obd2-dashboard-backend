package com.obd2dashboard.backend.replay

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class LiveOptions(
    /** 0 sends as fast as the socket takes it; 1 is real time. */
    val speed: Double = 1.0,
    /** Drop the socket (as a lost link does) every this many milliseconds of wall time; null never. */
    val dropEveryMillis: Long? = null,
    /** Multiplies the reconnect backoff, so tests need not sleep. */
    val waitScale: Double = 1.0,
    /** Plays a dashboard with a message widget: `displayed` after `received` (§5.4). False: received only. */
    val widget: Boolean = true,
    /**
     * Plays a tablet that takes courses from the server (M12.6, the proposal's §2):
     * lists `courses.1` in `hello`, and on each `courses` frame fetches
     * `GET /v1/courses` with its last `ETag`.
     */
    val courses: Boolean = false,
    /** Plays a tablet that shows `timing` (M17.4, §22.7): lists `timing.1` in `hello`, and logs each frame. */
    val timing: Boolean = false,
)

sealed interface LiveResult {
    data class Ended(val batches: Int, val reconnects: Int) : LiveResult
    data class Stopped(val reason: String) : LiveResult
}

/**
 * One session's live lane as the tablet sends it (contract §5.1–5.3): `hello`,
 * `session`, a `snapshot` of the state so far, then a `batch` every 200 ms of log
 * time, coalesced to the latest sample per signal with every other record in
 * full, then `end`. **It never replays what a drop missed**: after a reconnect it
 * resnapshots and carries on from where it is. **The token is never printed.**
 */
class LiveReplayer(
    private val server: URI,
    private val token: String,
    private val options: LiveOptions = LiveOptions(),
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val log: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun replay(file: SessionFile): LiveResult = coroutineScope { run(file, this) }

    /**
     * The crew's message on "screen", as the app's `CrewMessages` keeps it
     * (contract §5.4): one at a time, a newer one replacing it; `received` once
     * per id on arrival, `displayed` once per id while a widget draws it; the
     * earlier deadline kept on a duplicate; taken down by `clear`, by a sync that
     * leaves it out, or when its time runs out. Kept across reconnects, as a
     * tablet keeps its screen.
     */
    private inner class Screen {
        private var shown: String? = null
        private var deadline = 0L
        private val received = HashSet<String>()
        private val displayed = HashSet<String>()

        /** Replies to send, for a message frame or a sync item. */
        @Synchronized
        fun arrive(id: String, text: String, preset: String?, ttlMs: Long): List<String> {
            val at = System.nanoTime() + ttlMs * 1_000_000
            val replies = mutableListOf<String>()
            if (shown == id) {
                deadline = minOf(deadline, at) // a resend never extends a message
            } else if (ttlMs > 0) {
                shown = id
                deadline = at
                log("message $id \"$text\"${preset?.let { " ($it)" } ?: ""}, ${ttlMs / 1000} s left")
            }
            if (received.add(id)) replies += """{"t":"received","id":"$id"}"""
            if (options.widget && shown == id && displayed.add(id)) replies += """{"t":"displayed","id":"$id"}"""
            return replies
        }

        @Synchronized
        fun clear(id: String) {
            if (shown == id) { shown = null; log("taken down: $id (clear)") }
        }

        /** A sync is the whole truth: whatever it leaves out comes down. */
        @Synchronized
        fun sync(ids: Set<String>) {
            shown?.let { if (it !in ids) { shown = null; log("taken down: $it (not in the sync)") } }
        }

        @Synchronized
        fun expire() {
            shown?.let { if (System.nanoTime() >= deadline) { shown = null; log("taken down: $it (its time ran out)") } }
        }
    }

    private val screen = Screen()

    private suspend fun run(file: SessionFile, scope: CoroutineScope): LiveResult {
        val header = json.parseToJsonElement(file.lines[0].decodeToString()).jsonObject
        val records = file.lines.drop(1).map { runCatching { json.parseToJsonElement(it.decodeToString()).jsonObject }.getOrNull() }
        val windows = windows(file, records)
        val state = State()
        var socket = connect(header, state, scope) ?: return LiveResult.Stopped("could not connect")
        var reconnects = 0
        var drops = 0 // consecutive, for the backoff; reset once a batch goes through
        var lastCleanClose = 0L
        var lastDrop = System.currentTimeMillis()
        val started = System.nanoTime()
        val firstAt = file.firstRecordAt ?: 0L
        var sent = 0

        var i = 0
        while (i < windows.size) {
            val window = windows[i]
            if (options.speed > 0) {
                val due = ((window.at - firstAt) / options.speed).toLong()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (due > elapsed) delay(due - elapsed)
            }
            options.dropEveryMillis?.let { every ->
                if (System.currentTimeMillis() - lastDrop >= every) {
                    log("dropping the socket")
                    socket.abort()
                    lastDrop = System.currentTimeMillis()
                }
            }
            window.records.forEach(state::absorb)
            screen.expire()
            val closed = if (socket.closed.isCompleted) socket.closed.await() else null
            if (closed != null) {
                when (val next = afterClose(closed, lastCleanClose, drops)) {
                    is After.Stop -> return LiveResult.Stopped(next.reason)
                    is After.Reconnect -> {
                        if (closed.clean) lastCleanClose = System.currentTimeMillis()
                        if (next.waitMillis > 0) drops++
                        if (next.waitMillis > 0) delay((next.waitMillis * options.waitScale).toLong())
                        log("reconnecting after ${closed.describe()}${if (next.waitMillis > 0) ", waited ${next.waitMillis} ms" else ", at once"}")
                        socket = connect(header, state, scope) ?: return LiveResult.Stopped("could not reconnect")
                        reconnects++
                    }
                }
            }
            if (!socket.send(batchFrame(file.id, coalesce(window.records)))) continue // closed mid-send: handled next window
            drops = 0
            sent++
            i++
        }
        socket.send(buildJsonObject { put("t", "end"); put("session", file.id) }.toString())
        socket.close()
        return LiveResult.Ended(sent, reconnects)
    }

    private sealed interface After {
        data class Reconnect(val waitMillis: Long) : After
        data class Stop(val reason: String) : After
    }

    /**
     * §5.3: a clean `1001`/`1012` reconnects at once, unless another came within
     * 10 s; any other drop backs off. `auth`, `superseded` and
     * `unsupported_version` stop for good.
     */
    private fun afterClose(closed: Closed, lastCleanClose: Long, drops: Int): After {
        closed.fatalError?.let { return After.Stop("the server said $it") }
        if (closed.clean && System.currentTimeMillis() - lastCleanClose > 10_000) return After.Reconnect(0)
        return After.Reconnect(BACKOFF[minOf(drops, BACKOFF.lastIndex)])
    }

    /** The courses' `ETag` last fetched, as a tablet caches it. */
    @Volatile private var coursesEtag: String? = null

    /** `GET /v1/courses` as a tablet would on a `courses` frame: with its last `ETag`, logging what came. */
    private suspend fun fetchCourses() {
        val request = java.net.http.HttpRequest.newBuilder(URI.create("$server/v1/courses"))
            .header("Authorization", "Bearer $token")
            .apply { coursesEtag?.let { header("If-None-Match", it) } }
            .GET().build()
        val response = runCatching { http.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()).await() }
            .getOrElse { log("courses: fetch failed (${it.javaClass.simpleName})"); return }
        when (response.statusCode()) {
            304 -> log("courses: unchanged")
            200 -> {
                coursesEtag = response.headers().firstValue("ETag").orElse(null)
                val courses = runCatching { (json.parseToJsonElement(response.body()).jsonObject["courses"] as JsonArray).map { it.jsonObject } }.getOrDefault(emptyList())
                log("courses: " + courses.joinToString(", ") { "${it.string("id")} v${(it["version"] as? JsonPrimitive)?.contentOrNull}" }.ifEmpty { "none" })
            }
            else -> log("courses: HTTP ${response.statusCode()}")
        }
    }

    private suspend fun connect(header: JsonObject, state: State, scope: CoroutineScope): Socket? {
        repeat(20) { attempt ->
            val socket = runCatching {
                val listener = Socket(scope)
                val ws = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer $token")
                    .subprotocols(PROTOCOL)
                    .buildAsync(URI.create(server.toString().replaceFirst("http", "ws") + "/v1/live"), listener)
                    .await()
                listener.also { it.ws = ws }
            }.getOrElse {
                log("connect failed: ${it.javaClass.simpleName}")
                delay((BACKOFF[minOf(attempt, BACKOFF.lastIndex)] * options.waitScale).toLong())
                return@repeat
            }
            socket.send(
                buildJsonObject {
                    put("t", "hello"); put("v", 3); put("device", "replay"); put("app", "replay"); put("wall", System.currentTimeMillis())
                    if (options.courses || options.timing) {
                        put("features", buildJsonArray { if (options.courses) add("courses.1"); if (options.timing) add("timing.1") })
                    }
                }.toString(),
            )
            socket.send(buildJsonObject { put("t", "session"); put("record", header) }.toString())
            socket.send(buildJsonObject { put("t", "snapshot"); put("session", header["id"]!!); put("records", buildJsonArray { state.snapshot().forEach { add(it) } }) }.toString())
            return socket
        }
        return null
    }

    /** The session's current state, for a snapshot (§5.2). */
    private class State {
        private var signals: JsonObject? = null
        private var fault: JsonObject? = null
        private val stopped = LinkedHashMap<String, JsonObject>()
        private val latest = LinkedHashMap<String, JsonObject>()

        fun absorb(record: JsonObject) {
            when (record.string("type")) {
                "sample" -> record.string("signal")?.let { latest[it] = record }
                "stopped" -> record.string("signal")?.let { stopped[it] = record }
                "fault" -> fault = record
                "signals" -> signals = record
            }
        }

        fun snapshot(): List<JsonObject> = (listOfNotNull(signals, fault) + stopped.values + latest.values)
            .sortedBy { (it["seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0 }
    }

    private data class Window(val at: Long, val records: List<JsonObject>)

    /** The log in 200 ms windows of `at`; a line without `at` takes the one before it. */
    private fun windows(file: SessionFile, records: List<JsonObject?>): List<Window> {
        val out = mutableListOf<Window>()
        var start: Long? = null
        var lastAt = file.firstRecordAt ?: 0L
        var current = mutableListOf<JsonObject>()
        records.forEachIndexed { i, record ->
            val at = file.at[i + 1] ?: lastAt
            lastAt = at
            if (start == null) start = at
            if (at - start >= WINDOW_MILLIS) {
                if (current.isNotEmpty()) out += Window(start, current)
                current = mutableListOf()
                start = at
            }
            record?.let { current += it }
        }
        if (current.isNotEmpty()) out += Window(start ?: 0, current)
        return out
    }

    /** §5.2: the latest sample per signal, and every other record in full, in order. */
    private fun coalesce(records: List<JsonObject>): List<JsonObject> {
        val lastSampleIndex = HashMap<String, Int>()
        records.forEachIndexed { i, r -> if (r.string("type") == "sample") r.string("signal")?.let { lastSampleIndex[it] = i } }
        return records.filterIndexed { i, r ->
            r.string("type") != "sample" || r.string("signal")?.let { lastSampleIndex[it] == i } != false
        }
    }

    private fun batchFrame(id: String, records: List<JsonObject>): String =
        buildJsonObject { put("t", "batch"); put("session", id); put("records", buildJsonArray { records.forEach { add(it) } }) }.toString()

    data class Closed(val code: Int, val fatalError: String?) {
        val clean: Boolean get() = code == 1001 || code == 1012
        fun describe(): String = "close $code${fatalError?.let { " ($it)" } ?: ""}"
    }

    /** A socket and what the server said to it. */
    private inner class Socket(private val scope: CoroutineScope) : WebSocket.Listener {
        lateinit var ws: WebSocket
        val closed = CompletableDeferred<Closed>()
        private var fatal: String? = null
        private val text = StringBuilder()
        /** The JDK's WebSocket allows one send in flight: batches and message replies take turns. */
        private val sending = Mutex()

        /**
         * Sends, but never waits long: a socket the server has just closed can
         * leave the future unfinished, and the lane must never hang on it.
         */
        suspend fun send(frame: String): Boolean =
            if (closed.isCompleted) {
                false
            } else {
                runCatching { sending.withLock { withTimeoutOrNull(5_000) { ws.sendText(frame, true).await() } } != null }.getOrElse { false }
            }

        fun abort() = ws.abort().also { closed.complete(Closed(1006, null)) }

        /** Closes if still open, never waiting more than 2 s (found hanging when the server had just sent 1001). */
        suspend fun close() {
            if (closed.isCompleted) return
            val done = runCatching { withTimeoutOrNull(2_000) { ws.sendClose(WebSocket.NORMAL_CLOSURE, "session ended").await() } }.getOrNull()
            if (done == null) ws.abort()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            text.append(data)
            if (last) {
                val frame = runCatching { Json.parseToJsonElement(text.toString()).jsonObject }.getOrNull()
                text.clear()
                when (frame?.string("t")) {
                    "error" -> {
                        val code = frame.string("code")
                        log("server error: $code")
                        if (code in FATAL) fatal = code
                    }
                    "message" -> reply(arrive(frame))
                    "messages" -> {
                        val items = (frame["active"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        screen.sync(items.mapNotNull { it.string("id") }.toSet())
                        reply(items.flatMap { arrive(it) })
                    }
                    "clear" -> frame.string("id")?.let(screen::clear)
                    "courses" -> if (options.courses) scope.launch { fetchCourses() }
                    "timing" -> if (options.timing) log("timing: " + timingLine(frame))
                }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        private fun arrive(m: JsonObject): List<String> {
            val id = m.string("id") ?: return emptyList()
            val ttl = (m["ttlMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return emptyList()
            return screen.arrive(id, m.string("text").orEmpty(), m.string("preset"), ttl)
        }

        private fun reply(frames: List<String>) {
            if (frames.isNotEmpty()) scope.launch { frames.forEach { send(it) } }
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closed.complete(Closed(statusCode, fatal))
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.complete(Closed(1006, fatal))
        }
    }

    companion object {
        const val PROTOCOL = "obd2-telemetry.v1"
        const val WINDOW_MILLIS = 200L
        private val FATAL = setOf("auth", "superseded", "unsupported_version")

        /** §5.3: 1, 2, 5, then every 10 s. */
        private val BACKOFF = longArrayOf(1_000, 2_000, 5_000, 10_000)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /**
         * Signal units from the contract's appendix (the one source this side can
         * read before a real v3 log exists): rows of `| \`name\` | Quantity | unit | value |`
         * after "## Appendix", with `—` meaning none.
         */
        fun unitsFrom(contract: Path): Map<String, String> {
            val lines = Files.readAllLines(contract)
            val start = lines.indexOfFirst { it.startsWith("## Appendix") }
            if (start < 0) return emptyMap()
            val row = Regex("^\\|\\s*`([^`]+)`\\s*\\|[^|]*\\|\\s*([^|]*?)\\s*\\|")
            return lines.drop(start + 1).mapNotNull { row.find(it) }
                .associate { m -> m.groupValues[1] to m.groupValues[2].let { if (it == "—" || it == "-") "" else it } }
        }
    }
}

/** A `timing` frame in a line (M17.4): the race's lap, the driver and stint, the best. */
internal fun timingLine(frame: JsonObject): String {
    fun JsonObject.obj(k: String) = this[k] as? JsonObject
    fun JsonObject.raw(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    val parts = listOfNotNull(
        frame.obj("course")?.let { "course ${it.raw("id")} v${it.raw("version")} ${it.raw("layout")}" },
        frame.obj("race")?.let { "race lap ${it.raw("lap")}" + (it.raw("sinceStopAgeMs")?.let { a -> ", ${a.toLong() / 1000} s since the stop" } ?: ", in the pits") },
        frame.obj("driver")?.let { "driver ${it.raw("code") ?: "not set"}" + (it.raw("stintAgeMs")?.let { a -> ", stint ${a.toLong() / 1000} s" } ?: "") },
        frame.obj("best")?.let { "best ${it.raw("time")} (${it.raw("driver") ?: "?"}, lap ${it.raw("lap") ?: "re-timed"})" },
        (frame["bestSectors"] as? JsonArray)?.let { "sectors " + it.joinToString("/") { s -> (s as? JsonPrimitive)?.contentOrNull ?: "—" } },
    )
    return parts.joinToString("; ").ifEmpty { "nothing yet" }
}

