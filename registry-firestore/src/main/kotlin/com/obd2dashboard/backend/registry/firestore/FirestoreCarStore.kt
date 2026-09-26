package com.obd2dashboard.backend.registry.firestore

import com.google.api.gax.rpc.AlreadyExistsException
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.obd2dashboard.backend.registry.Car
import com.obd2dashboard.backend.registry.CarStore
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import io.grpc.Status
import java.time.Instant

/**
 * [CarStore] on Firestore: one document per car in `cars`, keyed by slug.
 *
 * **Every "only if" is atomic on Firestore's side, never a read then a write**,
 * so two admin commands racing cannot both win: `create()` refuses an existing
 * document, `update()` a missing one, and `delete` reads and deletes in one
 * transaction, because a plain delete of a missing document succeeds, which
 * would break "no such car".
 */
public class FirestoreCarStore(private val db: Firestore) : CarStore {
    private val cars = db.collection(COLLECTION)

    override suspend fun get(slug: Slug): Car? = cars.document(slug.value).get().await().toCar()

    override suspend fun findByTokenHash(tokenHash: String): Car? {
        val docs = cars.whereEqualTo(TOKEN_HASH, tokenHash).limit(2).get().await().documents
        if (docs.size > 1) throw RegistryException.DuplicateToken(docs.map { Slug.parse(it.id) })
        return docs.singleOrNull()?.toCar()
    }

    override suspend fun list(): List<Car> = cars.get().await().documents.mapNotNull { it.toCar() }

    override suspend fun create(car: Car): Boolean = try {
        cars.document(car.slug.value).create(car.toFields(forUpdate = false)).await()
        true
    } catch (e: Exception) {
        if (e.isStatus(Status.Code.ALREADY_EXISTS)) false else throw e
    }

    override suspend fun update(car: Car): Boolean = try {
        cars.document(car.slug.value).update(car.toFields(forUpdate = true)).await()
        true
    } catch (e: Exception) {
        if (e.isStatus(Status.Code.NOT_FOUND)) false else throw e
    }

    /**
     * In a transaction, because the Java client keeps `Precondition.exists` to
     * itself and a plain delete of a missing document succeeds.
     */
    override suspend fun delete(slug: Slug): Boolean {
        val ref = cars.document(slug.value)
        return db.runTransaction { tx ->
            if (tx.get(ref).get().exists()) {
                tx.delete(ref)
                true
            } else {
                false
            }
        }.await()
    }

    public companion object {
        public const val COLLECTION: String = "cars"
        private const val NAME = "name"
        private const val TOKEN_HASH = "tokenHash"
        private const val TOKEN_HINT = "tokenHint"
        private const val TOKEN_ISSUED = "tokenIssued"
        private const val PASSCODE_HASH = "passcodeHash"
        private const val CREATED = "created"
        private const val UPDATED = "updated"

        /**
         * A client for [projectId]'s `(default)` database.
         *
         * **The project is always named**, never left to the environment: the
         * owner's `gcloud` default is an unrelated project, and a client that
         * guessed would write cars into it.
         */
        public fun connect(projectId: String): FirestoreCarStore {
            require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
            val db = FirestoreOptions.newBuilder()
                .setProjectId(projectId)
                .setDatabaseId("(default)")
                .build()
                .service
            return FirestoreCarStore(db)
        }

        internal fun Car.toFields(forUpdate: Boolean): Map<String, Any> = buildMap {
            put(NAME, name)
            put(TOKEN_HASH, tokenHash)
            put(TOKEN_HINT, tokenHint)
            put(TOKEN_ISSUED, tokenIssued.toTimestamp())
            // An update writes every field, so a cleared passcode must be deleted, not left behind.
            val passcode = passcodeHash
            if (passcode != null) {
                put(PASSCODE_HASH, passcode)
            } else if (forUpdate) {
                put(PASSCODE_HASH, FieldValue.delete())
            }
            put(CREATED, created.toTimestamp())
            put(UPDATED, updated.toTimestamp())
        }

        private fun DocumentSnapshot.toCar(): Car? {
            if (!exists()) return null
            fun string(field: String) = getString(field) ?: error("car \"$id\" has no $field")
            fun instant(field: String) =
                getTimestamp(field)?.toInstant() ?: error("car \"$id\" has no $field")
            return Car(
                slug = Slug.parse(id),
                name = string(NAME),
                tokenHash = string(TOKEN_HASH),
                tokenHint = string(TOKEN_HINT),
                tokenIssued = instant(TOKEN_ISSUED),
                passcodeHash = getString(PASSCODE_HASH),
                created = instant(CREATED),
                updated = instant(UPDATED),
            )
        }

        private fun Instant.toTimestamp(): Timestamp = Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)

        private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

        /** gax wraps gRPC failures in several ways; look through all of them for the status. */
        private fun Throwable.isStatus(code: Status.Code): Boolean {
            var t: Throwable? = this
            while (t != null) {
                when {
                    code == Status.Code.ALREADY_EXISTS && t is AlreadyExistsException -> return true
                    code == Status.Code.NOT_FOUND && t is NotFoundException -> return true
                    Status.fromThrowable(t).code == code -> return true
                }
                t = t.cause
            }
            return false
        }
    }
}
