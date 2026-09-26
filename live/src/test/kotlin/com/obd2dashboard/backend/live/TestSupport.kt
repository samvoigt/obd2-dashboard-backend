package com.obd2dashboard.backend.live

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A clock a test moves by hand. */
class MutableClock(var now: Instant = Instant.parse("2026-09-26T12:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(d: Duration) { now = now.plus(d) }
    fun advanceMillis(ms: Long) = advance(Duration.ofMillis(ms))
}

const val SESSION = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
const val OTHER_SESSION = "11111111-1111-4111-8111-111111111111"
const val VIN = "TSTVEHCLE00000001"

fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

fun sessionFrame(id: String = SESSION, vin: String? = VIN) = """{"t":"session","record":{"type":"session","v":3,"id":"$id","device":"dev","app":"1.0","started":"2026-09-26T12:00:00Z",${vin?.let { "\"vin\":\"$it\"," } ?: ""}"signals":[{"name":"engine.rpm","unit":"rpm","kind":"number"}],"seq":0,"at":100}}"""

fun sample(signal: String, value: Double, seq: Long) = """{"type":"sample","signal":"$signal","value":$value,"seq":$seq,"at":${1000 + seq},"wall":${1758719312000 + seq}}"""

fun batch(vararg records: String, session: String = SESSION) = """{"t":"batch","session":"$session","records":[${records.joinToString(",")}]}"""

fun snapshotFrame(vararg records: String, session: String = SESSION) = """{"t":"snapshot","session":"$session","records":[${records.joinToString(",")}]}"""

fun parse(text: String): TabletFrame = (TabletFrames.parse(text) as TabletFrames.Parsed.Ok).frame
