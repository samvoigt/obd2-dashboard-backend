package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.timing.Retimer
import com.obd2dashboard.backend.timing.RunTiming
import com.obd2dashboard.backend.timing.SessionAt
import com.obd2dashboard.backend.timing.runOf
import com.obd2dashboard.backend.timing.runsAt
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** A course save's re-timing, as the admin page shows it (M13.4). */
@Serializable
data class RetimingProgress(
    val version: Int,
    val runs: Int,
    val sessions: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
)

/**
 * **When re-timing runs** (M13.4): a session's run once it's prepared, and
 * every run a course touches when the course is saved, in the background.
 * One run at a time, whatever asked: the instance is one (decision 20), and a
 * re-timing reads whole logs.
 */
class RetimingJobs(
    private val registry: CarRegistry,
    private val archive: ArchiveService,
    private val courses: CourseStore,
    private val scope: CoroutineScope,
) {
    private val retimer = Retimer(archive, courses)
    private val lock = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, RetimingProgress>()

    /** The latest save's re-timing of course [id], or null if none since the server started. */
    fun progress(id: String): RetimingProgress? = progress[id]

    /** After session [id] of [car] is prepared: its run, on every course it touches. */
    suspend fun sessionPrepared(car: String, id: String) {
        val current = courses.current()
        if (current.isEmpty()) return
        val (run, at) = runOf(sessionsOf(car), id, current) ?: return
        for (course in at) retime(run, course)
    }

    /** A course was saved: re-time every run it touches, in the background, in place of any earlier save's job. */
    fun courseSaved(course: Course): Job {
        jobs.remove(course.id)?.cancel()
        val job = scope.launch {
            val runs = registry.list().flatMap { runsAt(sessionsOf(it.slug.value), course) }
            var p = RetimingProgress(course.version, runs.size, runs.sumOf { it.size })
            progress[course.id] = p
            for (run in runs) {
                p = try {
                    retime(run, course.id)
                    p.copy(done = p.done + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("re-timing {} on {} failed; the rest carry on", run, course.id, e)
                    p.copy(done = p.done + 1, failed = p.failed + 1)
                }
                progress[course.id] = p
            }
            progress[course.id] = p.copy(finished = true)
            log.info("re-timed {} runs at {} v{}, {} failed", runs.size, course.id, course.version, p.failed)
        }
        jobs[course.id] = job
        job.invokeOnCompletion { jobs.remove(course.id, job) }
        return job
    }

    private suspend fun retime(run: List<String>, course: String): RunTiming? =
        lock.withLock { retimer.timing(run, course) }?.also { timing ->
            for (lap in timing.flagged) {
                log.warn(
                    "lap disagrees: {} lap {} in session {} on {} v{}: tablet {}–{}, re-timed {}–{}",
                    timing.layout, lap.tabletLap, lap.session, timing.course, timing.courseVersion,
                    lap.startAt, lap.endAt, lap.flag?.startAt, lap.flag?.endAt,
                )
            }
        }

    /** A car's complete sessions with their summaries (an older summary rebuilt on the way, once). */
    private suspend fun sessionsOf(car: String): List<SessionAt> =
        archive.sessionsOf(car).filter { it.complete }.mapNotNull { r -> archive.summary(r.id)?.let { SessionAt(r.id, car, it) } }

    private companion object {
        val log = LoggerFactory.getLogger("retiming")
    }
}
