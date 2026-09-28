package com.obd2dashboard.backend.timing

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.courses.Course
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import com.obd2dashboard.backend.courses.CourseShape
import com.obd2dashboard.backend.courses.CourseStore
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a lap that stands came from. */
@Serializable
public enum class LapSource { TABLET, RETIMED }

/** A lap of a run on one course, as it stands (M13.3). */
@Serializable
public data class RunLap(
    /** The session it ended in. */
    val session: String,
    val source: LapSource,
    val startAt: Double,
    val endAt: Double,
    /** Seconds, as the `lap` record has it (the tablet's own number, for a tablet lap). */
    val time: Double,
    val sectors: List<Double>,
    val pitIn: Boolean,
    val pitOut: Boolean,
    /** The tablet's lap number; null for a re-timed one. */
    val tabletLap: Int? = null,
    /** False for a tablet lap placed from its record's `at` (no `startAt`, `endAt`), so never checked. */
    val checked: Boolean = true,
    /** Set when re-timing disagrees with the tablet's lap by more than [Retiming.AGREE_MS]. */
    val flag: Disagreement? = null,
)

/** What re-timing found where the tablet timed a lap it disagrees with: its nearest lap, or none. */
@Serializable
public data class Disagreement(val startAt: Double? = null, val endAt: Double? = null, val time: Double? = null)

/** A run re-timed on one version of a course, stored per run and version (M13.3). */
@Serializable
public data class RunTiming(
    val rule: Int,
    val course: String,
    val courseVersion: Int,
    val layout: String,
    /** The run's sessions in order: the file is current only for exactly these. */
    val sessions: List<String>,
    /** The laps as they stand, in order. */
    val laps: List<RunLap>,
    /** Every lap re-timing found, whether it stands or not. */
    val retimed: List<RunLap>,
    /** Each session's `wall` less `at` ([SessionTrace.wallOffset]): where its laps go on its page. */
    val wallOffsets: Map<String, Long> = emptyMap(),
    /** Every time the run crossed the pit lane's entry or exit (M15.1), in order: its stops. */
    val pitCrossings: List<PitCrossing> = emptyList(),
) {
    val flagged: List<RunLap> get() = laps.filter { it.flag != null }
}

/**
 * **Re-timing** (contract §22.1, decision 31): the run's own fixes, by the
 * tablet's own rule, on the course's current version; and the laps that stand.
 * The tablet's laps on that version stand as sent, each checked against
 * re-timing; re-timed laps fill in where the tablet has none.
 */
public object Retiming {
    /** Bumped when the rule changes, so every stored re-timing is rebuilt. */
    public const val RULE_VERSION: Int = 2

    /** The two sides time the same fixes; the tablet on nanoseconds, the log in milliseconds (§22.3). */
    public const val AGREE_MS: Double = 2.0

    /** [run]'s sessions in order, each with its trace, re-timed on [course]; null if it can't be timed. */
    public fun retime(course: Course, run: List<Pair<String, SessionTrace>>): RunTiming? {
        val shape = (CourseRules.check(course.geojson) as? CourseCheck.Ok)?.shape ?: return null
        val ours = run.flatMap { (id, trace) -> trace.tabletLaps.filter { it.course == course.id }.map { id to it } }
        val layout = layoutOf(shape, ours.map { it.second })
        val rule = LapRule(shape, layout)
        if (!rule.canTime) return null
        val pits = PitLane(shape)
        val pitCrossings = mutableListOf<PitCrossing>()
        val retimed = run.flatMap { (id, trace) ->
            trace.allFixes.mapNotNull { fix ->
                pitCrossings += pits.offer(id, fix)
                rule.offer(fix)?.let { lap -> retimedLap(id, lap) }
            }
        }
        val current = ours
            .filter { (_, lap) -> lap.courseVersion == course.version && layoutMatches(shape, layout, lap.layout) }
            .mapNotNull { (id, lap) -> tabletLap(id, lap, retimed) }
        val standing = (current + retimed.filter { r -> current.none { it.covers(r) } }).sortedBy { it.endAt }
        val offsets = run.mapNotNull { (id, trace) -> trace.wallOffset?.let { id to it } }.toMap()
        return RunTiming(RULE_VERSION, course.id, course.version, layout, run.map { it.first }, standing, retimed, offsets, pitCrossings)
    }

    /** The layout the tablet's laps name, by `id` or (before courses came from the website) by name; else the default. */
    private fun layoutOf(shape: CourseShape, laps: List<TabletLap>): String {
        val named = laps.asReversed().firstNotNullOfOrNull { lap -> shape.layouts.firstOrNull { it.id == lap.layout || it.name == lap.layout } }
        return (named ?: shape.defaultLayout).id
    }

    private fun layoutMatches(shape: CourseShape, layout: String, named: String?): Boolean =
        shape.layouts.any { it.id == layout && (it.id == named || it.name == named) }

    private fun retimedLap(session: String, lap: TimedLap) =
        RunLap(session, LapSource.RETIMED, lap.startAt, lap.endAt, lap.time, lap.sectors, lap.pitIn, lap.pitOut)

    private fun tabletLap(session: String, lap: TabletLap, retimed: List<RunLap>): RunLap? {
        val checked = lap.startAt != null && lap.endAt != null
        val endAt = lap.endAt?.toDouble() ?: lap.at?.toDouble() ?: return null
        val startAt = lap.startAt?.toDouble() ?: (endAt - lap.time * 1000)
        val sent = RunLap(session, LapSource.TABLET, startAt, endAt, lap.time, lap.sectors, lap.pitIn, lap.pitOut, tabletLap = lap.lap, checked = checked)
        if (!checked) return sent
        if (retimed.any { abs(it.startAt - startAt) <= AGREE_MS && abs(it.endAt - endAt) <= AGREE_MS }) return sent
        val nearest = retimed.filter { it.covers(sent) }.minByOrNull { abs(it.endAt - endAt) + abs(it.startAt - startAt) }
        return sent.copy(flag = Disagreement(nearest?.startAt, nearest?.endAt, nearest?.time))
    }

    /**
     * The same lap, near enough: they share more than half the shorter one. Laps
     * are back to back, so a millisecond's disagreement at a crossing (or a lap
     * placed from its record's `at`) never reaches the laps either side.
     */
    private fun RunLap.covers(o: RunLap) =
        minOf(endAt, o.endAt) - maxOf(startAt, o.startAt) > minOf(endAt - startAt, o.endAt - o.startAt) / 2
}

/**
 * Re-times runs and keeps the result beside the run's first session,
 * `timing-v{rule}-{course}-{version}.json.gz`, read back while the run and
 * the course's version are unchanged (M13.3).
 */
public class Retimer(
    private val archive: ArchiveService,
    private val courses: CourseStore,
    /** Told of each re-timing built, not of one read back: where a disagreement is reported, once. */
    private val built: (RunTiming) -> Unit = {},
) {
    /** [run]'s timing on course [courseId]'s current version, stored or built; null if it can't be timed. */
    public suspend fun timing(run: List<String>, courseId: String): RunTiming? {
        val first = run.firstOrNull() ?: return null
        val course = courses.get(courseId) ?: return null
        val name = fileName(course.id, course.version)
        archive.derived(first, name)
            ?.let { runCatching { json.decodeFromString<RunTiming>(it.decodeToString()) }.getOrNull() }
            ?.takeIf { it.sessions == run }
            ?.let { return it }
        val traces = run.map { id ->
            val record = archive.session(id) ?: return null
            id to SessionTrace().also { trace -> archive.read(record) { trace.read(it) } }
        }
        val timing = Retiming.retime(course, traces) ?: return null
        archive.putDerived(first, name, json.encodeToString(RunTiming.serializer(), timing).encodeToByteArray())
        built(timing)
        // Only the newest of this course stays: older versions, and any older rule's.
        archive.derivedNames(first, "timing-").filter { it != name && ofCourse(course.id).matches(it) }.forEach { archive.deleteDerived(first, it) }
        return timing
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        public fun fileName(course: String, version: Int): String = "timing-v${Retiming.RULE_VERSION}-$course-$version.json.gz"
    }
}

/** The names of a course's stored re-timings, every rule and version; never another course's (`nhms` isn't `nhms-oval`). */
private fun ofCourse(courseId: String) = Regex("timing-v\\d+-${Regex.escape(courseId)}-\\d+\\.json\\.gz")

/** Deletes every stored re-timing on course [courseId] among sessions [ids] (the course removed, M13.6); how many. */
public suspend fun ArchiveService.removeTimings(ids: Collection<String>, courseId: String): Int {
    val ofCourse = ofCourse(courseId)
    var removed = 0
    for (id in ids) {
        for (name in derivedNames(id, "timing-").filter { ofCourse.matches(it) }) {
            deleteDerived(id, name)
            removed++
        }
    }
    return removed
}
