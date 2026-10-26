package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import java.time.Duration

/**
 * **A session following straight on carries its driver over** (M32, decision 48; Sam: "Same tablet, back to back
 * (10 min)"). When a session's line 0 arrives and nobody has said who drove it, it's given the driver the car's
 * previous session ended with, if that one was on the same device and last heard at most [gap] before this one was
 * first heard: a restart of the app, or the adapter reconnecting, mid-drive. After a longer break, nobody's set.
 * Never test data, on either side. Answers the driver carried, or null.
 */
suspend fun carryDriver(archive: ArchiveService, car: String, id: String, gap: Duration = DRIVE_GAP): String? {
    val record = archive.session(id) ?: return null
    if (record.heard().source == FAKE || record.driver != null || record.driverChanges.isNotEmpty()) return null
    val device = record.header?.device ?: return null
    val previous = archive.sessionsOf(car).filter { it.id != id && it.created < record.created }.maxByOrNull { it.created } ?: return null
    if (previous.heard().source == FAKE || previous.header?.device != device) return null
    if (Duration.between(previous.updated, record.created) > gap) return null
    val driver = previous.driverChanges.lastOrNull()?.let { it.driver ?: return null } ?: previous.driver ?: return null
    return driver.takeIf { archive.setDriverIfUnset(id, it) }
}
