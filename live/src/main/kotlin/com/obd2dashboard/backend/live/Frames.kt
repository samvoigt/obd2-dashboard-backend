package com.obd2dashboard.backend.live

import com.obd2dashboard.backend.archive.SessionIds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A frame from the tablet (contract §5.2). Records are §3's, as JSON objects, **with `vin` removed**. */
public sealed interface TabletFrame {
    public data class Hello(val v: Int, val device: String?, val app: String?, val wall: Long?) : TabletFrame
    public data class Session(val id: String, val record: JsonObject) : TabletFrame
    public data class Snapshot(val session: String, val records: List<JsonObject>) : TabletFrame
    public data class Batch(val session: String, val records: List<JsonObject>) : TabletFrame
    public data class End(val session: String, val lastSeq: Long?) : TabletFrame
    public data class Received(val id: String) : TabletFrame
    public data class Displayed(val id: String) : TabletFrame

    /** A `t` this server does not know: ignored, as §5.2 asks of both sides. */
    public data class Unknown(val t: String) : TabletFrame
}

public object TabletFrames {
    /** §5.2: live frames are at most 64 KB. */
    public const val MAX_FRAME_BYTES: Int = 64 * 1024
    public const val MIN_VERSION: Int = 3

    public sealed interface Parsed {
        public data class Ok(val frame: TabletFrame) : Parsed
        public data class Bad(val reason: String) : Parsed
    }

    public fun parse(text: String): Parsed {
        if (text.toByteArray().size > MAX_FRAME_BYTES) return Parsed.Bad("frames are at most $MAX_FRAME_BYTES bytes")
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return Parsed.Bad("a frame is one JSON object")
        val t = obj.string("t") ?: return Parsed.Bad("a frame has a string \"t\"")
        return when (t) {
            "hello" -> {
                val v = obj.int("v") ?: return Parsed.Bad("hello has no format version")
                if (v < MIN_VERSION) return Parsed.Bad("format v$v is too old; v$MIN_VERSION or later")
                Parsed.Ok(TabletFrame.Hello(v, obj.string("device"), obj.string("app"), obj.long("wall")))
            }
            "session" -> {
                val record = obj["record"] as? JsonObject ?: return Parsed.Bad("session has no record")
                if (record.string("type") != "session") return Parsed.Bad("session's record is not a session record")
                val v = record.int("v") ?: return Parsed.Bad("the session record has no format version")
                if (v < MIN_VERSION) return Parsed.Bad("format v$v is too old; v$MIN_VERSION or later")
                val id = record.string("id")?.takeIf(SessionIds::isValid) ?: return Parsed.Bad("the session id is not a UUID")
                Parsed.Ok(TabletFrame.Session(SessionIds.normalise(id), withoutVin(record)))
            }
            "snapshot", "batch" -> {
                val session = obj.string("session")?.takeIf(SessionIds::isValid)
                    ?: return Parsed.Bad("$t has no valid session id")
                val records = (obj["records"] as? JsonArray ?: return Parsed.Bad("$t has no records"))
                    .map { it as? JsonObject ?: return Parsed.Bad("$t's records must be objects") }
                    .map(::withoutVin)
                val id = SessionIds.normalise(session)
                Parsed.Ok(if (t == "snapshot") TabletFrame.Snapshot(id, records) else TabletFrame.Batch(id, records))
            }
            "end" -> {
                val session = obj.string("session")?.takeIf(SessionIds::isValid) ?: return Parsed.Bad("end has no valid session id")
                Parsed.Ok(TabletFrame.End(SessionIds.normalise(session), obj.long("lastSeq")))
            }
            "received" -> obj.string("id")?.let { Parsed.Ok(TabletFrame.Received(it)) } ?: Parsed.Bad("received has no id")
            "displayed" -> obj.string("id")?.let { Parsed.Ok(TabletFrame.Displayed(it)) } ?: Parsed.Bad("displayed has no id")
            else -> Parsed.Ok(TabletFrame.Unknown(t))
        }
    }

    /**
     * The VIN identifies a vehicle and is never shown (decision 16). It is
     * removed on arrival, from **every** record: the contract lets fields appear
     * without a version bump (§3.1), and one carrying `vin` must not leak by default.
     */
    public fun withoutVin(record: JsonObject): JsonObject =
        if ("vin" in record) JsonObject(record - "vin") else record
}

/** Frames from the server to the tablet (§5.2). */
public object ServerFrames {
    public fun welcome(serverWall: Long): String = buildJsonObject {
        put("t", "welcome")
        put("serverWall", serverWall)
    }.toString()

    public fun error(code: ErrorCode, message: String): String = buildJsonObject {
        put("t", "error")
        put("code", code.wire)
        put("message", message)
        put("fatal", code.fatal)
    }.toString()
}

/** §5.2's error codes, and whether each closes the socket. */
public enum class ErrorCode(public val wire: String, public val fatal: Boolean) {
    Auth("auth", true),
    Superseded("superseded", true),
    BadMessage("bad_message", false),
    UnsupportedVersion("unsupported_version", true),
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
