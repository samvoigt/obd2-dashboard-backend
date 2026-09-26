package com.obd2dashboard.backend.replay

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A session log as the tablet would upload it: format v3, whole lines only.
 *
 * Older logs (v1, v2) are **upgraded** by rewriting line 0 alone, as the replay's
 * own writing: it plays the tablet, which may encode its record however it likes.
 * Every other line is kept as bytes.
 */
class SessionFile(val lines: List<ByteArray>, val id: String) {
    val lastIndex: Long get() = lines.size - 1L

    /**
     * When the drive's records begin, in `at`: the first record **after** line 0.
     * Old logs put `at = 0` on the session record while the samples carry the
     * app's uptime (millions of ms), so pacing from line 0 would wait that long
     * before the first batch (found looking at the M4.7 page).
     */
    val firstRecordAt: Long? get() = at.drop(1).firstNotNullOfOrNull { it }

    /** `at` of each line, for chunking by log time; null where a line has none. */
    val at: List<Long?> by lazy { lines.map { (parse(it)?.get("at") as? JsonPrimitive)?.longOrNull } }

    /** SHA-256 over every line plus `\n`: the contract's hash (§6.3), computed independently of the server. */
    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { digest.update(it); digest.update('\n'.code.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun bytes(): ByteArray = lines.fold(java.io.ByteArrayOutputStream()) { out, l -> out.write(l); out.write('\n'.code); out }.toByteArray()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads [path] (`.jsonl` or `.jsonl.gz`), drops a cut-short last line as the tablet does, and upgrades to v3. */
        fun load(path: Path, idSeed: String, device: String, units: Map<String, String> = emptyMap()): SessionFile {
            val raw = Files.newInputStream(path).use { input ->
                if (path.fileName.toString().endsWith(".gz")) GZIPInputStream(input).readBytes() else input.readBytes()
            }
            val lines = splitWholeLines(raw)
            require(lines.isNotEmpty()) { "$path has no complete lines" }
            val header = parse(lines[0]) ?: error("$path: line 0 is not JSON")
            require(header["type"]?.jsonPrimitive?.contentOrNull == "session") { "$path: line 0 is not a session record" }
            val version = header["v"]?.jsonPrimitive?.intOrNull ?: 1
            if (version >= 3) {
                val id = header["id"]?.jsonPrimitive?.contentOrNull ?: error("$path: a v3 record has no id")
                return SessionFile(lines, id)
            }
            // A stable id per (source, token), so a rerun resumes the same session.
            val id = UUID.nameUUIDFromBytes((idSeed + ":" + sha(raw)).toByteArray()).toString()
            return SessionFile(listOf(upgrade(header, id, device, lines, units)) + lines.drop(1), id)
        }

        private fun upgrade(header: JsonObject, id: String, device: String, lines: List<ByteArray>, units: Map<String, String>): ByteArray {
            val signals = signalsOf(lines, units)
            val v3 = buildJsonObject {
                put("type", "session")
                put("v", 3)
                put("id", id)
                put("device", device)
                put("app", "replay")
                header["started"]?.let { put("started", it) }
                header["protocol"]?.let { put("protocol", it) }
                header["vin"]?.let { put("vin", it) }
                header["pids"]?.let { put("pids", it) }
                put("signals", signals)
                put("seq", 0)
                header["at"]?.let { put("at", it) }
                header["wall"]?.let { put("wall", it) }
            }
            return v3.toString().toByteArray()
        }

        /**
         * The signals the samples carry, each with the kind its fields show and
         * its unit from [units] (the contract's appendix, `--units-from`), or
         * empty where that does not know it.
         */
        private fun signalsOf(lines: List<ByteArray>, units: Map<String, String>): JsonArray {
            val kinds = sortedMapOf<String, String>()
            for (line in lines.drop(1)) {
                val obj = parse(line) ?: continue
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "sample") continue
                val name = obj["signal"]?.jsonPrimitive?.contentOrNull ?: continue
                kinds.getOrPut(name) {
                    when {
                        "value" in obj -> "number"
                        "code" in obj -> "state"
                        "flags" in obj -> "flags"
                        "flag" in obj -> "flag"
                        else -> "number"
                    }
                }
            }
            return buildJsonArray {
                kinds.forEach { (name, kind) -> add(buildJsonObject { put("name", name); put("unit", units[name].orEmpty()); put("kind", kind) }) }
            }
        }

        /** Complete lines only: a last line without `\n` was cut short, and the tablet drops it (§6.3). */
        private fun splitWholeLines(raw: ByteArray): List<ByteArray> {
            val lines = mutableListOf<ByteArray>()
            var start = 0
            for (i in raw.indices) {
                if (raw[i] == '\n'.code.toByte()) {
                    lines += raw.copyOfRange(start, i)
                    start = i + 1
                }
            }
            return lines
        }

        private fun parse(line: ByteArray): JsonObject? =
            runCatching { json.parseToJsonElement(line.decodeToString()).jsonObject }.getOrNull()

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
