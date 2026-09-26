package com.obd2dashboard.backend.registry.firestore

import com.google.api.core.ApiFuture
import com.google.api.core.ApiFutureCallback
import com.google.api.core.ApiFutures
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until the future completes. Cancelling the coroutine cancels the
 * future. The callback runs on whichever thread completes the future, which is
 * fine because it only resumes the coroutine.
 */
internal suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    ApiFutures.addCallback(
        this,
        object : ApiFutureCallback<T> {
            override fun onSuccess(result: T) = cont.resume(result)
            override fun onFailure(t: Throwable) =
                cont.resumeWithException((t as? ExecutionException)?.cause ?: t)
        },
        MoreExecutors.directExecutor(),
    )
    cont.invokeOnCancellation { cancel(true) }
}
