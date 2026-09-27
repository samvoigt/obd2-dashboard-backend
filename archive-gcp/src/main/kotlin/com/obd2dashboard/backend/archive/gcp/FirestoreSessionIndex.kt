package com.obd2dashboard.backend.archive.gcp

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Transaction
import com.google.common.util.concurrent.MoreExecutors
import com.obd2dashboard.backend.archive.Segment
import com.obd2dashboard.backend.archive.SessionHeader
import com.obd2dashboard.backend.archive.SessionIndex
import com.obd2dashboard.backend.archive.LapInfo
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.archive.SignalInfo
import io.grpc.Status
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [SessionIndex] on Firestore: one document per session in `sessions`, keyed
 * by id.
 *
 * **Every conditional change is a transaction** that reads the document and
 * writes only if `ackedThrough` is what the caller expects, so two instances
 * handling one session cannot both advance it.
 */
public class FirestoreSessionIndex(private val db: Firestore) : SessionIndex {
    private val sessions = db.collection(COLLECTION)

    override suspend fun get(id: String): SessionRecord? = sessions.document(id).get().await().toRecord()

    override suspend fun create(record: SessionRecord): Boolean = try {
        sessions.document(record.id).create(record.toFields()).await()
        true
    } catch (e: Exception) {
        if (e.isAlreadyExists()) false else throw e
    }

    override suspend fun setLine0(
        id: String,
        header: SessionHeader,
        line0Sha256: String,
        segment: Segment,
        now: Instant,
    ): Boolean = conditional(id) { current ->
        if (current.ackedThrough != -1L) null
        else current.copy(header = header, line0Sha256 = line0Sha256, ackedThrough = 0, segments = listOf(segment), updated = now)
    }

    override suspend fun append(id: String, expectedAcked: Long, segment: Segment, now: Instant): Boolean =
        conditional(id) { current ->
            if (current.ackedThrough != expectedAcked || current.complete) null
            else current.copy(ackedThrough = segment.last, segments = current.segments + segment, updated = now)
        }

    override suspend fun complete(id: String, expectedAcked: Long, sha256: String, now: Instant): Boolean =
        conditional(id) { current ->
            if (current.ackedThrough != expectedAcked) null
            else current.copy(complete = true, sha256 = sha256, segments = emptyList(), updated = now)
        }

    override suspend fun resetToLine0(id: String, expectedAcked: Long, now: Instant): Boolean = conditional(id) { current ->
        if (current.ackedThrough != expectedAcked) null
        else current.copy(
            ackedThrough = 0,
            segments = current.segments.filter { it.first == 0L },
            hashResets = current.hashResets + 1,
            updated = now,
        )
    }

    override suspend fun setSummary(id: String, summary: SessionSummary): Boolean =
        conditional(id) { current -> current.copy(summary = summary) }

    override suspend fun listByCar(car: String): List<SessionRecord> =
        sessions.whereEqualTo(CAR, car).get().await().documents.mapNotNull { it.toRecord() }

    override suspend fun list(): List<SessionRecord> = sessions.get().await().documents.mapNotNull { it.toRecord() }

    override suspend fun delete(id: String): Boolean {
        val ref = sessions.document(id)
        return db.runTransaction { tx: Transaction ->
            if (tx.get(ref).get().exists()) {
                tx.delete(ref)
                true
            } else {
                false
            }
        }.await()
    }

    /** Reads, applies [change], and writes in one transaction; a null change means the condition failed. */
    private suspend fun conditional(id: String, change: (SessionRecord) -> SessionRecord?): Boolean {
        val ref = sessions.document(id)
        return db.runTransaction { tx: Transaction ->
            val current = tx.get(ref).get().toRecord() ?: return@runTransaction false
            val next = change(current) ?: return@runTransaction false
            tx.set(ref, next.toFields())
            true
        }.await()
    }

    public companion object {
        public const val COLLECTION: String = "sessions"
        private const val CAR = "car"

        public fun connect(projectId: String): FirestoreSessionIndex {
            require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
            return FirestoreSessionIndex(
                FirestoreOptions.newBuilder().setProjectId(projectId).setDatabaseId("(default)").build().service,
            )
        }

        /** The document's fields. Header fields are flat and absent when null; the id is the document's. */
        internal fun SessionRecord.toFields(): Map<String, Any> = buildMap {
            put(CAR, car)
            header?.let { h ->
                put("v", h.v.toLong())
                put("started", h.started)
                h.device?.let { put("device", it) }
                h.app?.let { put("app", it) }
                h.vin?.let { put("vin", it) }
                h.protocol?.let { put("protocol", it) }
                h.source?.let { put("source", it) }
            }
            line0Sha256?.let { put("line0Sha256", it) }
            put("ackedThrough", ackedThrough)
            put(
                "segments",
                segments.map { s ->
                    buildMap<String, Any> {
                        put("first", s.first)
                        put("last", s.last)
                        put("key", s.key)
                        s.firstSeq?.let { put("firstSeq", it) }
                        s.lastSeq?.let { put("lastSeq", it) }
                    }
                },
            )
            put("complete", complete)
            sha256?.let { put("sha256", it) }
            put("hashResets", hashResets.toLong())
            put("created", created.toTimestamp())
            put("updated", updated.toTimestamp())
            // Part of the record, so every whole-document write keeps it (M7.1).
            summary?.let { put("summary", it.toFields()) }
        }

        private fun SessionSummary.toFields(): Map<String, Any> = buildMap {
            put("version", version.toLong())
            put("started", started)
            put("ended", ended)
            put("lines", lines)
            put("signals", signals.map { mapOf("name" to it.name, "unit" to it.unit, "kind" to it.kind) })
            source?.let { put("source", it) }
            track?.let { put("track", it) }
            layout?.let { put("layout", it) }
            put("laps", laps.toLong())
            bestLap?.let { put("bestLap", it.toFields()) }
            put("faults", faults)
            put("gaps", gaps.toLong())
            put("missed", missed)
            put("unreadable", unreadable.toLong())
        }

        private fun LapInfo.toFields(): Map<String, Any> = buildMap {
            track?.let { put("track", it) }
            layout?.let { put("layout", it) }
            put("lap", lap.toLong())
            put("time", time)
            put("pitIn", pitIn)
            put("pitOut", pitOut)
            wall?.let { put("wall", it) }
        }

        @Suppress("UNCHECKED_CAST")
        private fun summaryFrom(data: Map<String, Any?>): SessionSummary {
            fun long(key: String) = (data[key] as? Number)?.toLong() ?: 0L
            val best = data["bestLap"] as? Map<String, Any?>
            return SessionSummary(
                version = long("version").toInt(),
                started = long("started"),
                ended = long("ended"),
                lines = long("lines"),
                signals = (data["signals"] as? List<Map<String, Any?>>).orEmpty().map {
                    SignalInfo(it["name"] as String, it["unit"] as? String ?: "", it["kind"] as? String ?: "")
                },
                source = data["source"] as? String,
                track = data["track"] as? String,
                layout = data["layout"] as? String,
                laps = long("laps").toInt(),
                bestLap = best?.let {
                    LapInfo(
                        track = it["track"] as? String,
                        layout = it["layout"] as? String,
                        lap = (it["lap"] as Number).toInt(),
                        time = (it["time"] as Number).toDouble(),
                        pitIn = it["pitIn"] as? Boolean ?: false,
                        pitOut = it["pitOut"] as? Boolean ?: false,
                        wall = (it["wall"] as? Number)?.toLong(),
                    )
                },
                faults = (data["faults"] as? List<String>).orEmpty(),
                gaps = long("gaps").toInt(),
                missed = long("missed"),
                unreadable = long("unreadable").toInt(),
            )
        }

        internal fun recordFrom(id: String, data: Map<String, Any?>): SessionRecord {
            fun long(key: String) = (data[key] as? Number)?.toLong() ?: error("session $id has no $key")
            fun string(key: String) = data[key] as? String
            fun instant(key: String) = (data[key] as? Timestamp)?.toInstant() ?: error("session $id has no $key")
            val line0Sha = string("line0Sha256")
            val header = line0Sha?.let {
                SessionHeader(
                    id = id,
                    v = long("v").toInt(),
                    started = string("started") ?: error("session $id has no started"),
                    device = string("device"),
                    app = string("app"),
                    vin = string("vin"),
                    protocol = string("protocol"),
                    source = string("source"),
                )
            }
            @Suppress("UNCHECKED_CAST")
            val segments = (data["segments"] as? List<Map<String, Any?>>).orEmpty().map { s ->
                Segment(
                    first = (s["first"] as Number).toLong(),
                    last = (s["last"] as Number).toLong(),
                    key = s["key"] as String,
                    firstSeq = (s["firstSeq"] as? Number)?.toLong(),
                    lastSeq = (s["lastSeq"] as? Number)?.toLong(),
                )
            }
            return SessionRecord(
                id = id,
                car = string(CAR) ?: error("session $id has no car"),
                header = header,
                line0Sha256 = line0Sha,
                ackedThrough = long("ackedThrough"),
                segments = segments,
                complete = data["complete"] as? Boolean ?: false,
                sha256 = string("sha256"),
                hashResets = (data["hashResets"] as? Number)?.toInt() ?: 0,
                created = instant("created"),
                updated = instant("updated"),
                summary = (data["summary"] as? Map<*, *>)?.let { m -> summaryFrom(m.entries.associate { (k, v) -> k.toString() to v }) },
            )
        }

        private fun DocumentSnapshot.toRecord(): SessionRecord? = if (exists()) recordFrom(id, data.orEmpty()) else null

        private fun Instant.toTimestamp(): Timestamp = Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)

        private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

        /** gax wraps gRPC failures in several ways; look through all of them. */
        private fun Throwable.isAlreadyExists(): Boolean = generateSequence(this) { it.cause }.any {
            it is AlreadyExistsException || Status.fromThrowable(it).code == Status.Code.ALREADY_EXISTS
        }

        private suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
            ApiFutures.addCallback(
                this,
                object : ApiFutureCallback<T> {
                    override fun onSuccess(result: T) = cont.resume(result)
                    override fun onFailure(t: Throwable) = cont.resumeWithException((t as? ExecutionException)?.cause ?: t)
                },
                MoreExecutors.directExecutor(),
            )
            cont.invokeOnCancellation { cancel(true) }
        }
    }
}
