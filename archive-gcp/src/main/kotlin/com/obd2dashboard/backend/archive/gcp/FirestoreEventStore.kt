package com.obd2dashboard.backend.archive.gcp

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Transaction
import com.google.common.util.concurrent.MoreExecutors
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.DriverStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventStore
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [DriverStore] on Firestore (M14.2): `drivers/{id}`. **A code stays unique**:
 * a save is a transaction that reads every driver (there are a handful) and
 * writes only if no other has the code.
 */
public class FirestoreDriverStore(private val db: Firestore) : DriverStore {
    private val drivers = db.collection(COLLECTION)

    override suspend fun list(): List<Driver> =
        drivers.get().await().documents.map { driverFrom(it.id, it.data.orEmpty()) }.sortedBy { it.name }

    override suspend fun get(id: String): Driver? =
        if (!isDocumentId(id)) null else drivers.document(id).get().await().takeIf { it.exists() }?.let { driverFrom(it.id, it.data.orEmpty()) }

    override suspend fun put(driver: Driver): Driver? = db.runTransaction { tx: Transaction ->
        val taken = tx.get(drivers).get().documents.any { it.id != driver.id && it.getString(CODE) == driver.code }
        if (taken) return@runTransaction null
        tx.set(drivers.document(driver.id), driver.toFields())
        driver
    }.await()

    override suspend fun delete(id: String): Boolean {
        if (!isDocumentId(id)) return false
        val ref = drivers.document(id)
        return db.runTransaction { tx: Transaction ->
            if (tx.get(ref).get().exists()) { tx.delete(ref); true } else false
        }.await()
    }

    public companion object {
        public const val COLLECTION: String = "drivers"
        private const val NAME = "name"
        private const val CODE = "code"

        public fun connect(projectId: String): FirestoreDriverStore = FirestoreDriverStore(firestore(projectId))

        internal fun Driver.toFields(): Map<String, Any> = mapOf(NAME to name, CODE to code)

        internal fun driverFrom(id: String, data: Map<String, Any?>): Driver = Driver(
            id = id,
            name = data[NAME] as? String ?: error("driver $id has no name"),
            code = data[CODE] as? String ?: error("driver $id has no code"),
        )
    }
}

/**
 * [EventStore] on Firestore (M14.2): `events/{id}`, its parts a list of maps
 * inside it. **A save is a transaction** on the stored `revision`, so two
 * editors can't both win.
 */
public class FirestoreEventStore(private val db: Firestore) : EventStore {
    private val events = db.collection(COLLECTION)

    override suspend fun list(): List<Event> =
        events.get().await().documents.map { eventFrom(it.id, it.data.orEmpty()) }
            .sortedWith(compareByDescending<Event> { it.date }.thenBy { it.name })

    override suspend fun get(id: String): Event? =
        if (!isDocumentId(id)) null else events.document(id).get().await().takeIf { it.exists() }?.let { eventFrom(it.id, it.data.orEmpty()) }

    override suspend fun save(event: Event, expected: Int, now: Instant): Event? {
        val ref = events.document(event.id)
        return db.runTransaction { tx: Transaction ->
            val current = tx.get(ref).get()
            val revision = if (current.exists()) (current.get(REVISION) as? Number)?.toInt() ?: 0 else 0
            if (revision != expected) return@runTransaction null
            val saved = event.copy(revision = expected + 1, updated = now)
            tx.set(ref, saved.toFields())
            saved
        }.await()
    }

    override suspend fun delete(id: String): Boolean {
        if (!isDocumentId(id)) return false
        val ref = events.document(id)
        return db.runTransaction { tx: Transaction ->
            if (tx.get(ref).get().exists()) { tx.delete(ref); true } else false
        }.await()
    }

    public companion object {
        public const val COLLECTION: String = "events"
        private const val REVISION = "revision"

        public fun connect(projectId: String): FirestoreEventStore = FirestoreEventStore(firestore(projectId))

        /** The event's fields; its id is the document's. */
        internal fun Event.toFields(): Map<String, Any> = mapOf(
            "name" to name,
            "date" to date,
            "course" to course,
            "layout" to layout,
            "cars" to cars,
            "parts" to parts.map { p ->
                mapOf(
                    "id" to p.id,
                    "kind" to p.kind.name.lowercase(),
                    "name" to p.name,
                    "start" to p.start.toTimestamp(),
                    "end" to p.end.toTimestamp(),
                    "added" to p.added,
                    "removed" to p.removed,
                )
            },
            REVISION to revision.toLong(),
            "updated" to updated.toTimestamp(),
        )

        internal fun eventFrom(id: String, data: Map<String, Any?>): Event {
            fun string(key: String) = data[key] as? String ?: error("event $id has no $key")
            return Event(
                id = id,
                name = string("name"),
                date = string("date"),
                course = string("course"),
                layout = string("layout"),
                cars = strings(data["cars"]),
                parts = (data["parts"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { p ->
                    Part(
                        id = p["id"] as? String ?: error("event $id has a part with no id"),
                        kind = PartKind.valueOf((p["kind"] as? String ?: error("event $id has a part with no kind")).uppercase()),
                        name = p["name"] as? String ?: "",
                        start = (p["start"] as? Timestamp)?.toInstant() ?: error("event $id has a part with no start"),
                        end = (p["end"] as? Timestamp)?.toInstant() ?: error("event $id has a part with no end"),
                        added = strings(p["added"]),
                        removed = strings(p["removed"]),
                    )
                },
                revision = (data[REVISION] as? Number)?.toInt() ?: 0,
                updated = (data["updated"] as? Timestamp)?.toInstant() ?: Instant.EPOCH,
            )
        }

        private fun strings(value: Any?): List<String> = (value as? List<*>).orEmpty().filterIsInstance<String>()
    }
}

/**
 * Whether Firestore can hold [id] as a document's name: not empty, not `.` or
 * `..`, no `/`. Anything else, from a request, is simply not found (M14.6: an
 * empty driver id reached Firestore, which threw, and the server answered 500).
 */
internal fun isDocumentId(id: String): Boolean = id.isNotEmpty() && id != "." && id != ".." && '/' !in id

private fun firestore(projectId: String): Firestore {
    require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
    return FirestoreOptions.newBuilder().setProjectId(projectId).setDatabaseId("(default)").build().service
}

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
