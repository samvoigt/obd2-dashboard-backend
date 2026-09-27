package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.CarAdmin
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.CarStatus
import java.time.Duration
import java.time.Instant

/** The live session's id while the tablet is connected and in one. */
fun CarStatus.liveSession(): String? = sessionId.takeIf { connected && inSession }

/**
 * A session's state for the site and the admin page (M6.7, M7.3): `live` while
 * its tablet streams it, `complete`, `uploading` while its upload is active, and
 * `incomplete` once that has been quiet as long as [CarAdmin.UPLOAD_QUIET].
 */
fun sessionState(record: SessionRecord, live: String?, now: Instant): String = when {
    record.id == live -> "live"
    record.complete -> "complete"
    Duration.between(record.updated, now) >= CarAdmin.UPLOAD_QUIET -> "incomplete"
    else -> "uploading"
}

/** When the session began: its summary's, else its header's, else when its record was made. */
fun sessionStarted(record: SessionRecord): Instant =
    record.summary?.let { Instant.ofEpochMilli(it.started) }
        ?: record.header?.started?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: record.created
