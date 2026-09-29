package com.obd2dashboard.backend.archive.gcp

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Transaction
import com.google.common.util.concurrent.MoreExecutors
import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.CourseStore
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * [CourseStore] on Firestore (M12.3): `courses/{id}` holds the latest version's
 * number, name and time; `courses/{id}/versions/{n}` holds every version.
 *
 * **The GeoJSON is stored as its JSON text**: Firestore can't hold arrays
 * inside arrays, which GeoJSON coordinates are. **A save is a transaction**
 * over the course document, so two editors can't both make version N+1.
 */
public class FirestoreCourseStore(private val db: Firestore) : CourseStore {
    private val courses = db.collection(COLLECTION)

    override suspend fun current(): List<Course> =
        courses.get().await().documents.mapNotNull { doc ->
            val latest = (doc.get(VERSION) as? Number)?.toInt() ?: return@mapNotNull null
            get(doc.id, latest)
        }.sortedBy { it.id }

    override suspend fun get(id: String, version: Int?): Course? {
        val n = version ?: (courses.document(id).get().await().get(VERSION) as? Number)?.toInt() ?: return null
        val doc = courses.document(id).collection(VERSIONS).document(n.toString()).get().await()
        return if (doc.exists()) courseFrom(id, doc.data.orEmpty()) else null
    }

    override suspend fun versions(id: String): List<Course> =
        courses.document(id).collection(VERSIONS).get().await().documents
            .map { courseFrom(id, it.data.orEmpty()) }.sortedBy { it.version }

    override suspend fun save(id: String, expected: Int, name: String, geojson: JsonObject, now: Instant): Course? {
        val ref = courses.document(id)
        return db.runTransaction { tx: Transaction ->
            val latest = (tx.get(ref).get().get(VERSION) as? Number)?.toInt() ?: 0
            if (latest != expected) return@runTransaction null
            val course = Course(id, name, latest + 1, geojson, now)
            tx.set(ref, mapOf(VERSION to course.version.toLong(), NAME to name, SAVED to now.toTimestamp()))
            tx.set(ref.collection(VERSIONS).document(course.version.toString()), course.toFields())
            course
        }.await()
    }

    override suspend fun retimed(): Map<String, Int> =
        courses.get().await().documents.mapNotNull { doc -> (doc.get(RETIMED) as? Number)?.let { doc.id to it.toInt() } }.toMap()

    /** Only while [version] is still the latest: a save since has started its own re-timing. */
    override suspend fun setRetimed(id: String, version: Int) {
        val ref = courses.document(id)
        db.runTransaction { tx: Transaction ->
            if ((tx.get(ref).get().get(VERSION) as? Number)?.toInt() == version) tx.update(ref, RETIMED, version.toLong())
        }.await()
    }

    /** The versions in batches of [DELETE_BATCH], within Firestore's 500 writes a batch, then the course. */
    override suspend fun delete(id: String): Boolean {
        val ref = courses.document(id)
        if (!ref.get().await().exists()) return false
        while (true) {
            val page = ref.collection(VERSIONS).limit(DELETE_BATCH).get().await().documents
            if (page.isEmpty()) break
            db.batch().apply { page.forEach { delete(it.reference) } }.commit().await()
        }
        ref.delete().await()
        return true
    }

    public companion object {
        public const val COLLECTION: String = "courses"
        private const val RETIMED = "retimed"
        private const val VERSIONS = "versions"
        private const val VERSION = "version"
        private const val NAME = "name"
        private const val SAVED = "saved"
        private const val DELETE_BATCH = 400

        public fun connect(projectId: String): FirestoreCourseStore {
            require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
            return FirestoreCourseStore(
                FirestoreOptions.newBuilder().setProjectId(projectId).setDatabaseId("(default)").build().service,
            )
        }

        /** A version's fields: the GeoJSON as text. The course's id is its parent document's. */
        internal fun Course.toFields(): Map<String, Any> = mapOf(
            VERSION to version.toLong(),
            NAME to name,
            "geojson" to geojson.toString(),
            SAVED to saved.toTimestamp(),
        )

        internal fun courseFrom(id: String, data: Map<String, Any?>): Course = Course(
            id = id,
            name = data[NAME] as? String ?: error("course $id has a version with no name"),
            version = (data[VERSION] as? Number)?.toInt() ?: error("course $id has a version with no number"),
            geojson = Json.parseToJsonElement(data["geojson"] as? String ?: error("course $id has a version with no GeoJSON")).jsonObject,
            saved = (data[SAVED] as? Timestamp)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) }
                ?: error("course $id has a version with no time"),
        )

        private fun Instant.toTimestamp(): Timestamp = Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)

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
