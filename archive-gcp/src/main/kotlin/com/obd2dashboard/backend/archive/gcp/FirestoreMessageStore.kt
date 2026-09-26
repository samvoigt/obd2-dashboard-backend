package com.obd2dashboard.backend.archive.gcp

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.Transaction
import com.google.common.util.concurrent.MoreExecutors
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.MessageStore
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [MessageStore] on Firestore: one document per message in `messages`, kept
 * after it ends as a record of what the crew told the driver.
 *
 * **`update` is a transaction**, so a report on one revision and a clear on the
 * next during a deploy's changeover cannot undo each other (M5.1).
 */
public class FirestoreMessageStore(private val db: Firestore) : MessageStore {
    private val messages = db.collection(COLLECTION)

    override suspend fun create(message: Message) {
        messages.document(message.id).create(message.toFields()).await()
    }

    override suspend fun update(id: String, change: (Message) -> Message?): Message? {
        val ref = messages.document(id)
        return db.runTransaction { tx: Transaction ->
            val current = tx.get(ref).get().toMessage() ?: return@runTransaction null
            val next = change(current) ?: return@runTransaction current
            tx.set(ref, next.toFields())
            next
        }.await()
    }

    override suspend fun get(id: String): Message? = messages.document(id).get().await().toMessage()

    override suspend fun active(car: String): List<Message> =
        messages.whereEqualTo(CAR, car)
            .whereIn(STATE, MessageState.entries.filter { it.active }.map { it.wire })
            .get().await().documents.mapNotNull { it.toMessage() }

    override suspend fun recent(car: String, limit: Int): List<Message> =
        messages.whereEqualTo(CAR, car).orderBy(SENT_AT, Query.Direction.DESCENDING).limit(limit)
            .get().await().documents.mapNotNull { it.toMessage() }

    /** In batches of [DELETE_BATCH], within Firestore's 500 writes per batch. */
    override suspend fun deleteCar(car: String): Int {
        var deleted = 0
        while (true) {
            val page = messages.whereEqualTo(CAR, car).limit(DELETE_BATCH).get().await().documents
            if (page.isEmpty()) return deleted
            db.batch().apply { page.forEach { delete(it.reference) } }.commit().await()
            deleted += page.size
        }
    }

    public companion object {
        public const val COLLECTION: String = "messages"
        private const val CAR = "car"
        private const val STATE = "state"
        private const val SENT_AT = "sentAt"
        private const val DELETE_BATCH = 400

        public fun connect(projectId: String): FirestoreMessageStore {
            require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
            return FirestoreMessageStore(
                FirestoreOptions.newBuilder().setProjectId(projectId).setDatabaseId("(default)").build().service,
            )
        }

        /** The document's fields; absent ones stay absent. The id is the document's. */
        internal fun Message.toFields(): Map<String, Any> = buildMap {
            put(CAR, car)
            put("text", text)
            preset?.let { put("preset", it) }
            put(SENT_AT, sentAt.toTimestamp())
            put("expiresAt", expiresAt.toTimestamp())
            put(STATE, state.wire)
            receivedAt?.let { put("receivedAt", it.toTimestamp()) }
            displayedAt?.let { put("displayedAt", it.toTimestamp()) }
            endedAt?.let { put("endedAt", it.toTimestamp()) }
            replacedBy?.let { put("replacedBy", it) }
        }

        internal fun messageFrom(id: String, data: Map<String, Any?>): Message {
            fun instant(key: String) = (data[key] as? Timestamp)?.toInstant()
            val state = MessageState.entries.firstOrNull { it.wire == data[STATE] } ?: error("message $id has no known state")
            return Message(
                id = id,
                car = data[CAR] as? String ?: error("message $id has no car"),
                text = data["text"] as? String ?: error("message $id has no text"),
                preset = data["preset"] as? String,
                sentAt = instant(SENT_AT) ?: error("message $id has no sentAt"),
                expiresAt = instant("expiresAt") ?: error("message $id has no expiresAt"),
                state = state,
                receivedAt = instant("receivedAt"),
                displayedAt = instant("displayedAt"),
                endedAt = instant("endedAt"),
                replacedBy = data["replacedBy"] as? String,
            )
        }

        private fun DocumentSnapshot.toMessage(): Message? = if (exists()) messageFrom(id, data.orEmpty()) else null

        private fun Instant.toTimestamp(): Timestamp = Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)

        private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

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
