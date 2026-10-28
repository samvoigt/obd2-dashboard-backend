package com.obd2dashboard.backend.archive.gcp

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Transaction
import com.google.common.util.concurrent.MoreExecutors
import com.obd2dashboard.backend.archive.MergeStore
import com.obd2dashboard.backend.archive.MergedSession
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [MergeStore] on Firestore (M33.2): `merges/{id}`. **A save is a transaction**
 * on the stored `revision` that also reads the car's merges (a handful), as
 * [FirestoreDriverStore] keeps a code unique, and writes only if no other
 * merge not split holds one of its members.
 */
public class FirestoreMergeStore(private val db: Firestore) : MergeStore {
    private val merges = db.collection(COLLECTION)

    override suspend fun save(merge: MergedSession, expected: Int, now: Instant): MergedSession? {
        if (!isDocumentId(merge.id)) return null
        val ref = merges.document(merge.id)
        return db.runTransaction { tx: Transaction ->
            val current = tx.get(ref).get()
            val revision = if (current.exists()) (current.get(REVISION) as? Number)?.toInt() ?: 0 else 0
            if (revision != expected) return@runTransaction null
            if (!merge.split) {
                val taken = tx.get(merges.whereEqualTo(CAR, merge.car)).get().documents
                    .map { mergeFrom(it.id, it.data) }
                    .any { it.id != merge.id && !it.split && it.members.any { m -> m in merge.members } }
                if (taken) return@runTransaction null
            }
            val saved = merge.copy(revision = expected + 1, updated = now)
            tx.set(ref, saved.toFields())
            saved
        }.await()
    }

    override suspend fun get(id: String): MergedSession? =
        if (!isDocumentId(id)) null else merges.document(id).get().await().takeIf { it.exists() }?.let { mergeFrom(it.id, it.data.orEmpty()) }

    override suspend fun ofCar(car: String): List<MergedSession> =
        merges.whereEqualTo(CAR, car).get().await().documents.map { mergeFrom(it.id, it.data) }.sortedWith(compareBy({ it.created }, { it.id }))

    override suspend fun delete(id: String): Boolean {
        if (!isDocumentId(id)) return false
        val ref = merges.document(id)
        return db.runTransaction { tx: Transaction ->
            if (tx.get(ref).get().exists()) { tx.delete(ref); true } else false
        }.await()
    }

    public companion object {
        public const val COLLECTION: String = "merges"
        private const val CAR = "car"
        private const val REVISION = "revision"

        public fun connect(projectId: String): FirestoreMergeStore {
            require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
            return FirestoreMergeStore(FirestoreOptions.newBuilder().setProjectId(projectId).setDatabaseId("(default)").build().service)
        }

        /** The merge's fields; its id is the document's. */
        internal fun MergedSession.toFields(): Map<String, Any> = listOfNotNull(
            CAR to car,
            "members" to members,
            name?.let { "name" to it },
            "created" to created.toTimestamp(),
            "updated" to updated.toTimestamp(),
            REVISION to revision.toLong(),
            "split" to split,
        ).toMap()

        internal fun mergeFrom(id: String, data: Map<String, Any?>): MergedSession = MergedSession(
            id = id,
            car = data[CAR] as? String ?: error("merge $id has no car"),
            members = (data["members"] as? List<*>).orEmpty().filterIsInstance<String>(),
            name = data["name"] as? String,
            created = (data["created"] as? Timestamp)?.toInstant() ?: error("merge $id has no created"),
            updated = (data["updated"] as? Timestamp)?.toInstant() ?: error("merge $id has no updated"),
            revision = (data[REVISION] as? Number)?.toInt() ?: 0,
            split = data["split"] as? Boolean ?: false,
        )

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
