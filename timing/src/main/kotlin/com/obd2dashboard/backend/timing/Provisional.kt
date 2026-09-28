package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.courses.Course

/** A session streamed live and not complete yet (M17.2): its records so far. */
public class LiveSession(public val id: String, public val car: String, public val trace: SessionTrace = SessionTrace())

/**
 * **Provisional runs** (M17.2): sessions still being streamed, or ended and
 * not yet uploaded whole, joined into runs of the app as complete ones are
 * (§22.8) and re-timed the same way. Never stored: a session's run is
 * re-timed whole once it completes. Pure.
 */
public object Provisional {
    /** [sessions]' runs on [course], each as [Retiming.retime] has it; a session with no records yet is left out. */
    public fun runs(course: Course, sessions: List<LiveSession>): List<RunTiming> {
        val byId = sessions.associateBy { it.id }
        val parts = sessions.mapNotNull { s ->
            val t = s.trace
            RunPart(s.id, s.car, t.device, t.firstWall ?: return@mapNotNull null, t.lastWall ?: return@mapNotNull null, t.firstAt, t.lastAt)
        }
        return runs(parts).mapNotNull { run -> Retiming.retime(course, run.map { it.id to byId.getValue(it.id).trace }) }
    }
}
