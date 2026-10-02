package com.obd2dashboard.backend.archive.gcp

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Transaction
import com.google.common.util.concurrent.MoreExecutors
import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.AccessStore
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.admin.UserStore
import com.obd2dashboard.backend.admin.normalEmail
import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [UserStore] on Firestore (M23): `users/{email}`, the email lower case.
 * Adding is a transaction, so a user is invited once.
 */
public class FirestoreUserStore(private val db: Firestore) : UserStore {
    private val users = db.collection(COLLECTION)

    override suspend fun list(): List<User> =
        users.get().await().documents.map { userFrom(it.id, it.data.orEmpty()) }.sortedBy { it.email }

    override suspend fun get(email: String): User? {
        val id = email.normalEmail()
        if (!isDocumentId(id)) return null
        return users.document(id).get().await().takeIf { it.exists() }?.let { userFrom(it.id, it.data.orEmpty()) }
    }

    override suspend fun add(user: User): Boolean {
        val id = user.email.normalEmail()
        if (!isDocumentId(id)) return false
        val ref = users.document(id)
        return db.runTransaction { tx: Transaction ->
            if (tx.get(ref).get().exists()) return@runTransaction false
            tx.set(ref, user.toFields())
            true
        }.await()
    }

    override suspend fun remove(email: String): Boolean {
        val id = email.normalEmail()
        if (!isDocumentId(id)) return false
        val ref = users.document(id)
        return db.runTransaction { tx: Transaction ->
            if (tx.get(ref).get().exists()) { tx.delete(ref); true } else false
        }.await()
    }

    public companion object {
        public const val COLLECTION: String = "users"
        private const val INVITED_BY = "invitedBy"
        private const val INVITED = "invited"

        public fun connect(projectId: String): FirestoreUserStore = FirestoreUserStore(usersFirestore(projectId))

        /** The user's fields; their email is the document's id. */
        internal fun User.toFields(): Map<String, Any> = mapOf(
            INVITED_BY to invitedBy.normalEmail(),
            INVITED to Timestamp.ofTimeSecondsAndNanos(invited.epochSecond, invited.nano),
        )

        internal fun userFrom(id: String, data: Map<String, Any?>): User = User(
            email = id,
            invitedBy = data[INVITED_BY] as? String ?: "",
            invited = (data[INVITED] as? Timestamp)?.let { Instant.ofEpochSecond(it.seconds, it.nanos.toLong()) } ?: Instant.EPOCH,
        )
    }
}

/**
 * [AccessStore] on Firestore (M23): `access/{kind}:{id}`, as [Thing.key] has
 * it (`car:outback`). A colon is allowed in a document id, so the key is used
 * as it is. The editors are a list of strings, never a list of lists.
 */
public class FirestoreAccessStore(private val db: Firestore) : AccessStore {
    private val records = db.collection(COLLECTION)

    override suspend fun get(thing: Thing): Access? {
        if (!isDocumentId(thing.key)) return null
        return records.document(thing.key).get().await().takeIf { it.exists() }?.let { accessFrom(it.data.orEmpty()) }
    }

    override suspend fun all(): Map<Thing, Access> =
        records.get().await().documents.mapNotNull { doc -> Thing.parse(doc.id)?.let { it to accessFrom(doc.data.orEmpty()) } }.toMap()

    override suspend fun set(thing: Thing, access: Access) {
        require(isDocumentId(thing.key)) { "no access record can be kept for ${thing.key}" }
        records.document(thing.key).set(access.toFields()).await()
    }

    override suspend fun remove(thing: Thing) {
        if (isDocumentId(thing.key)) records.document(thing.key).delete().await()
    }

    public companion object {
        public const val COLLECTION: String = "access"
        private const val CREATOR = "creator"
        private const val EDITORS = "editors"

        public fun connect(projectId: String): FirestoreAccessStore = FirestoreAccessStore(usersFirestore(projectId))

        internal fun Access.toFields(): Map<String, Any> = mapOf(
            CREATOR to creator.normalEmail(),
            EDITORS to editors.map { it.normalEmail() }.distinct().sorted(),
        )

        internal fun accessFrom(data: Map<String, Any?>): Access = Access(
            creator = data[CREATOR] as? String ?: "",
            editors = (data[EDITORS] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
        )
    }
}

private fun usersFirestore(projectId: String): Firestore {
    require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
    return FirestoreOptions.newBuilder().setProjectId(projectId).setDatabaseId("(default)").build().service
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
