package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.LiveUpdate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where the car stands, **for its page** (M17.5): public, only what the page
 * shows. Moments are on the server's clock (the tablet's plus its measured
 * offset), so the page counts on from them with its own offset, and a
 * snapshot taken later is still right. Published when it changes.
 */
class TimingPage(private val hub: LiveHub) {
    private val last = ConcurrentHashMap<String, JsonObject>()

    suspend fun standing(car: String, s: Standing?) {
        val json = s?.let { json(it, hub.status(car).clockOffset?.toMillis()) }
        if (json == last[car]) return // the same, or nothing either way
        if (json == null) last.remove(car) else last[car] = json
        hub.publish(car, LiveUpdate.Timing(json))
    }

    companion object {
        /** The page's view of [s]; [offset] is the server's clock less the tablet's, null if not measured yet. */
        fun json(s: Standing, offset: Long?): JsonObject = buildJsonObject {
            fun server(tablet: Long?) = if (tablet == null || offset == null) null else tablet + offset
            s.driver?.let { d ->
                put("driver", buildJsonObject {
                    d.name?.let { put("name", it) }
                    d.code?.let { put("code", it) }
                    server(d.stintStart)?.let { put("since", it) }
                })
            }
            s.race?.let { r ->
                put("race", buildJsonObject {
                    put("lap", r.lap)
                    // Absent while the car is in the pits.
                    server(r.leftPits)?.let { put("leftPits", it) }
                })
            }
            s.best?.let { b -> put("best", buildJsonObject { put("time", b.time); b.driver?.let { put("driver", it) } }) }
            s.bestSectors?.let { list ->
                put("bestSectors", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } })
                if (list.isNotEmpty() && list.all { it != null }) put("theoretical", Math.round(list.sumOf { it!! } * 1000) / 1000.0)
            }
        }
    }
}
