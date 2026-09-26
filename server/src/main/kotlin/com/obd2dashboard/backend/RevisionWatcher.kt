package com.obd2dashboard.backend

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Drains this instance once its revision stops receiving traffic (M4.8a).
 *
 * **Why it exists:** Cloud Run moves only *new* connections to a new revision. An
 * open WebSocket stays on the old one, which is not sent SIGTERM while it holds a
 * connection, so without this a deploy would leave the tablet on the old revision
 * and every browser on the new one seeing "offline" for up to 55 minutes. Found
 * deploying mid-stream in M4.8.
 *
 * Every [period] it asks [isServing]; on `false` it calls [drain] (close tablets
 * with 1012 and end browser streams), again each period in case of stragglers.
 * **`null` (the check failed) never drains**: this improves on the 55-minute
 * backstop, and must never become a new way to cut connections.
 */
class RevisionWatcher(
    private val isServing: suspend () -> Boolean?,
    private val drain: suspend () -> Unit,
    private val period: Duration = 30.seconds,
    private val log: (String) -> Unit = {},
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            delay(period)
            if (check() == false) {
                log("This revision no longer has traffic: draining (1012) so tablets and browsers move to the new one.")
                drain()
            }
        }
    }

    internal suspend fun check(): Boolean? = try {
        isServing()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Revision check failed (${e.javaClass.simpleName}); not draining.")
        null
    }

    companion object {
        /**
         * Whether [revision] has traffic, from a Cloud Run Admin API v2 service
         * (`trafficStatuses`, `latestReadyRevision`); null if it cannot be told.
         * A "latest" allocation names no revision, so it means the latest ready one.
         */
        fun servingFrom(service: JsonObject, revision: String): Boolean? {
            val statuses = service["trafficStatuses"] as? JsonArray ?: return null
            val latest = (service["latestReadyRevision"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')
            return statuses.any { element ->
                val s = element as? JsonObject ?: return@any false
                val percent = (s["percent"] as? JsonPrimitive)?.intOrNull ?: 0
                val named = (s["revision"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/').orEmpty()
                val type = (s["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val target = if (named.isEmpty() && type.endsWith("LATEST")) latest else named
                percent > 0 && target == revision
            }
        }

        /**
         * The real check on Cloud Run: a token and the region from the metadata
         * server, then the service from the Admin API. Only built when Cloud Run's
         * own `K_REVISION` and `K_SERVICE` are set.
         */
        fun onCloudRun(project: String, drain: suspend () -> Unit, log: (String) -> Unit): RevisionWatcher? {
            val revision = System.getenv("K_REVISION") ?: return null
            val service = System.getenv("K_SERVICE") ?: return null
            val http = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(5)).build()
            suspend fun metadata(path: String): String = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://metadata.google.internal/computeMetadata/v1/$path"))
                    .header("Metadata-Flavor", "Google").timeout(JavaDuration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString(),
            ).await().also { check(it.statusCode() == 200) { "metadata $path: ${it.statusCode()}" } }.body()

            val isServing: suspend () -> Boolean? = {
                val region = metadata("instance/region").substringAfterLast('/')
                val token = Json.parseToJsonElement(metadata("instance/service-accounts/default/token"))
                    .jsonObject["access_token"]?.let { (it as JsonPrimitive).content } ?: error("no access token")
                val response = http.sendAsync(
                    HttpRequest.newBuilder(URI.create("https://run.googleapis.com/v2/projects/$project/locations/$region/services/$service"))
                        .header("Authorization", "Bearer $token").timeout(JavaDuration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).await()
                if (response.statusCode() != 200) {
                    log("Revision check: Cloud Run API answered ${response.statusCode()}")
                    null
                } else {
                    servingFrom(Json.parseToJsonElement(response.body()).jsonObject, revision)
                }
            }
            return RevisionWatcher(isServing, drain, log = log)
        }
    }
}
