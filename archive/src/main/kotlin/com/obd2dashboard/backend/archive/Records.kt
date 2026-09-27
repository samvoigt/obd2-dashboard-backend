package com.obd2dashboard.backend.archive

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the server checks about a record: **that it is a JSON object**, and
 * nothing more. Unknown types and fields are kept, never refused (contract
 * §3.1, §9); the server does not decide what a tablet may say.
 */
public object Records {
    /**
     * The line as a JSON object, or null if it is not one or is not UTF-8.
     *
     * Decoded strictly: `String(bytes)` would turn bad bytes into U+FFFD and
     * let them through.
     */
    public fun parseObject(line: ByteArray): JsonObject? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line))
                .toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        return try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The index of the first line in [lines] that is not a JSON object, or null if all are. */
    public fun firstNonObject(lines: LineBlock): Int? =
        (0 until lines.size).firstOrNull { parseObject(lines.line(it)) == null }
}

/**
 * The index fields of a session record (§3.2), read from line 0.
 *
 * **Absent means null**: the app writes with `explicitNulls = false`, so an
 * unknown VIN is a missing key, not `"vin":null`. Only what identifies the
 * session is required.
 */
public data class SessionHeader(
    val id: String,
    val v: Int,
    val started: String,
    val device: String?,
    val app: String?,
    val vin: String?,
    val protocol: String?,
    /** `tablet` (no car read, §20), `fake` (invented readings, §21), or absent: a car's session. */
    val source: String? = null,
) {
    // The VIN is personal data (decision 16); keep it out of logs and assertion messages.
    override fun toString(): String =
        "SessionHeader(id=$id, v=$v, started=$started, device=$device, app=$app, vin=${if (vin == null) "none" else "…"})"

    public companion object {
        public const val MIN_VERSION: Int = 3

        public fun parse(line0: ByteArray, expectedId: String): Parsed {
            val obj = Records.parseObject(line0) ?: return Parsed.Bad("the session record is not a JSON object")
            fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (string("type") != "session") return Parsed.Bad("line 0 is not a session record")
            val v = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: return Parsed.Bad("the session record has no format version")
            if (v < MIN_VERSION) return Parsed.Bad("format v$v cannot be uploaded; only v$MIN_VERSION and later")
            val id = string("id") ?: return Parsed.Bad("the session record has no id")
            if (!id.equals(expectedId, ignoreCase = true)) {
                return Parsed.Bad("the session record's id is not the one in the URL")
            }
            val started = string("started") ?: return Parsed.Bad("the session record has no start time")
            return Parsed.Ok(
                SessionHeader(
                    id = expectedId,
                    v = v,
                    started = started,
                    device = string("device"),
                    app = string("app"),
                    vin = string("vin"),
                    protocol = string("protocol"),
                    source = string("source"),
                ),
            )
        }
    }

    public sealed interface Parsed {
        public data class Ok(val header: SessionHeader) : Parsed
        public data class Bad(val reason: String) : Parsed
    }
}

/** Session ids are the tablet's UUIDs (§3.2); anything else is refused before it names an object. */
public object SessionIds {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    public fun isValid(id: String): Boolean = UUID.matches(id)

    /** One spelling per session, so an upper-case retry cannot open a second one. */
    public fun normalise(id: String): String = id.lowercase()
}
