package com.obd2dashboard.backend.archive

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * **A merged session** (M33, decision 49): several of a car's stored sessions
 * the site shows as one. The members stay as stored; this record is all a
 * merge is. Its [id] is a UUID, so a merged session's page has the same URL
 * shape as any session's. [split] once unmerged: kept so its URL can move to
 * the first member, and its members free for another merge.
 */
public data class MergedSession(
    val id: String,
    val car: String,
    /** Session ids, in time order. */
    val members: List<String>,
    /** Null: the first member's name, or none. */
    val name: String? = null,
    val created: Instant,
    val updated: Instant,
    val revision: Int = 0,
    val split: Boolean = false,
)

/**
 * Where merges are kept (M33.2). **A session is in at most one merge that
 * isn't split**: a save that would put a member in a second is refused.
 */
public interface MergeStore {
    /**
     * Saves [merge] if its stored revision is [expected] (0: not stored yet)
     * and none of its members is in another of the car's merges, unless that
     * one is split; answers it at revision `expected + 1`, or null.
     */
    public suspend fun save(merge: MergedSession, expected: Int, now: Instant): MergedSession?

    public suspend fun get(id: String): MergedSession?

    /** The car's merges, split ones too. */
    public suspend fun ofCar(car: String): List<MergedSession>

    public suspend fun delete(id: String): Boolean
}

/** The merge, not split, that holds session [id], if any. */
public suspend fun MergeStore.holding(car: String, id: String): MergedSession? = ofCar(car).firstOrNull { !it.split && id in it.members }

/** For tests and the dev server. */
public class InMemoryMergeStore : MergeStore {
    private val merges = ConcurrentHashMap<String, MergedSession>()

    override suspend fun save(merge: MergedSession, expected: Int, now: Instant): MergedSession? = synchronized(merges) {
        if ((merges[merge.id]?.revision ?: 0) != expected) return null
        val taken = merges.values.any { it.id != merge.id && it.car == merge.car && !it.split && it.members.any { m -> m in merge.members } }
        if (taken && !merge.split) return null
        merge.copy(revision = expected + 1, updated = now).also { merges[merge.id] = it }
    }

    override suspend fun get(id: String): MergedSession? = merges[id]

    override suspend fun ofCar(car: String): List<MergedSession> = merges.values.filter { it.car == car }.sortedWith(compareBy({ it.created }, { it.id }))

    override suspend fun delete(id: String): Boolean = merges.remove(id) != null
}

/**
 * **What may be merged** (M33.2, decision 49), pure: one car; two or more
 * sessions; each complete; in time order without overlapping by more than
 * [OVERLAP_MS] (M33.1 measured none: back-to-back sessions follow on 0.2 s
 * or more); none in another merge; all in the same event part, or in none.
 */
public object MergeRules {
    /** How far a session may start before the one before it ended: the two clocks behind `started` and `ended`. */
    public val OVERLAP_MS: Long = Duration.ofSeconds(5).toMillis()

    /** A session as the rules see it. [part]: its event part's name, or null; [mergedIn]: the merge it's in, or null. */
    public data class Candidate(
        val id: String,
        val car: String,
        val complete: Boolean,
        val started: Long,
        val ended: Long,
        val part: String? = null,
        val mergedIn: String? = null,
    )

    /** A reason not to merge, and the sessions it's about. */
    public data class Problem(val message: String, val sessions: List<String> = emptyList())

    /** Why [sessions] can't be merged (into [into], when extending it), or nothing. */
    public fun check(sessions: List<Candidate>, into: String? = null): List<Problem> = checkDistinct(sessions.distinctBy { it.id }, into)

    private fun checkDistinct(sessions: List<Candidate>, into: String?): List<Problem> {
        val problems = mutableListOf<Problem>()
        if (sessions.size < 2) problems += Problem("Pick at least two sessions to merge.")
        if (sessions.map { it.car }.distinct().size > 1) problems += Problem("These are different cars' sessions.", sessions.map { it.id })
        sessions.filter { !it.complete }.takeIf { it.isNotEmpty() }?.let {
            problems += Problem(if (it.size == 1) "One of them is still live or uploading." else "${it.size} of them are still live or uploading.", it.map { s -> s.id })
        }
        // Extending a merge brings its own members; any other merge's is refused.
        sessions.filter { it.mergedIn != null && it.mergedIn != into }.takeIf { it.isNotEmpty() }?.let {
            problems += Problem(
                if (into == null) "One of them is in a merged session already." else "Two merged sessions can't be merged: unmerge one first.",
                it.map { s -> s.id },
            )
        }
        val parts = sessions.map { it.part }.distinct()
        if (parts.size > 1) {
            val named = parts.map { it ?: "no part" }
            problems += Problem("These are in different parts of the event: ${named.dropLast(1).joinToString(", ")} and ${named.last()}.", sessions.map { it.id })
        }
        sessions.sortedBy { it.started }.zipWithNext().forEach { (a, b) ->
            val overlap = a.ended - b.started
            if (overlap > OVERLAP_MS) problems += Problem("Two of them overlap by ${duration(overlap)}: they can't be one drive.", listOf(a.id, b.id))
        }
        return problems
    }

    private fun duration(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000} s"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "%d h %02d min".format(ms / 3_600_000, ms % 3_600_000 / 60_000)
    }
}
