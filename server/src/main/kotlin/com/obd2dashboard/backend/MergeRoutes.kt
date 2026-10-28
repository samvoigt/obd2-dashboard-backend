package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.MergeRules
import com.obd2dashboard.backend.archive.MergeStore
import com.obd2dashboard.backend.archive.MergedSession
import com.obd2dashboard.backend.archive.SessionIds
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.SessionSummary
import com.obd2dashboard.backend.archive.holding
import com.obd2dashboard.backend.archive.membersOf
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.SlugCheck
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * **Merged sessions as the server sees them** (M33, decision 49): each of a
 * car's sessions as [MergeRules] judges it, worked out where only the server
 * can (live or not, the event part it's placed in, the merge it's in).
 */
class SessionMerges(
    private val archive: ArchiveService,
    private val stores: EventStores,
    private val hub: LiveHub,
    private val clock: Clock,
) {
    val store: MergeStore get() = stores.merges

    /** A session's start and end on the tablet's clock, as the list shows them: the summary's once complete. */
    suspend fun span(record: SessionRecord): Pair<Long, Long> {
        val summary = if (record.complete) record.summary?.takeIf { it.version == SessionSummary.VERSION } ?: archive.summary(record.id) else null
        val started = summary?.started ?: sessionStarted(record).toEpochMilli()
        return started to (summary?.ended ?: maxOf(record.updated.toEpochMilli(), started))
    }

    /** Each of [records] (one car's) by id, with the name of the event part it's in, merges placed as one. */
    suspend fun partsOf(car: String, records: List<SessionRecord>): Map<String, String> {
        val heard = records.map { it.heard() }
        val merged = stores.merges.membersOf(listOf(car))
        return stores.events.list().filter { car in it.cars }.flatMap { e ->
            EventRules.sessionsIn(e, heard, merged).flatMap { (partId, ids) -> ids.map { it to e.parts.first { p -> p.id == partId }.name } }
        }.toMap()
    }

    /** [ids] as the rules see them; null if one isn't a session of [car]. */
    suspend fun candidates(car: String, ids: List<String>): List<MergeRules.Candidate>? {
        val records = archive.sessionsOf(car)
        val byId = records.associateBy { it.id }
        val parts = partsOf(car, records)
        val live = hub.status(car).liveSession()
        val now = clock.instant()
        val merges = stores.merges.ofCar(car).filter { !it.split }
        return ids.map { id ->
            val record = byId[id] ?: return null
            val (started, ended) = span(record)
            MergeRules.Candidate(
                id, car, sessionState(record, live, now) == "complete", started, ended,
                part = parts[id], mergedIn = merges.firstOrNull { id in it.members }?.id,
            )
        }
    }

    /** The merge [id], not split, if any. */
    suspend fun merge(id: String): MergedSession? = store.get(id)?.takeIf { !it.split }

    /** The merge, not split, holding session [id] of [car], if any. */
    suspend fun holding(car: String, id: String): MergedSession? = store.holding(car, id)
}

@Serializable
data class MergeRequest(
    /** A new merge's sessions. */
    val members: List<String> = emptyList(),
    /** Extending a merge: its id, and the sessions to add. */
    val into: String? = null,
    val add: List<String> = emptyList(),
)

@Serializable
data class MergeView(val id: String, val car: String, val members: List<String>, val name: String? = null, val split: Boolean = false)

fun MergedSession.view(): MergeView = MergeView(id, car, members, name, split)

@Serializable
data class MergeProblem(val message: String, val sessions: List<String> = emptyList())

/** A merge refused: every reason, each with the sessions it's about, so the list can mark them. */
@Serializable
data class MergeRefused(val error: String, val message: String, val problems: List<MergeProblem>)

/**
 * Merging and unmerging (M33.3), a car's editors' (`Action.EDIT`, as setting a
 * driver): `POST /api/admin/cars/{slug}/merges` (`{members}`, or `{into, add}`
 * to extend) and `POST /api/admin/merges/{id}/split`. Each change clears the
 * results' hold ([onChanged]).
 */
fun Route.mergeRoutes(merges: SessionMerges, clock: Clock, onChanged: () -> Unit = {}) {
    post("/api/admin/cars/{slug}/merges") {
        call.signedIn(change = true) ?: return@post
        val slug = (Slug.check(call.parameters["slug"].orEmpty()) as? SlugCheck.Ok)?.slug
            ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such car."))
        val car = slug.value
        val who = call.may(Action.EDIT, Thing(Kind.CAR, car)) ?: return@post
        val request = runCatching { call.receive<MergeRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid", "Send the sessions to merge."))
        val into = request.into?.let { id ->
            merges.merge(id.lowercase())?.takeIf { it.car == car }
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such merged session for this car."))
        }
        val asked = (into?.members.orEmpty() + if (into != null) request.add else request.members)
        if (asked.any { !SessionIds.isValid(it) }) return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        val ids = asked.map { SessionIds.normalise(it) }.distinct()
        val candidates = merges.candidates(car, ids)
            ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such session for this car."))
        val problems = MergeRules.check(candidates, into?.id)
        if (problems.isNotEmpty()) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                MergeRefused("refused", problems.joinToString(" ") { it.message }, problems.map { MergeProblem(it.message, it.sessions) }),
            )
        }
        val ordered = candidates.sortedBy { it.started }.map { it.id }
        val now = clock.instant()
        val saved = if (into != null) {
            merges.store.save(into.copy(members = ordered), into.revision, now)
        } else {
            merges.store.save(MergedSession(UUID.randomUUID().toString(), car, ordered, created = now, updated = now), 0, now)
        } ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("conflict", "These sessions changed just now: reload and try again."))
        adminLog.info("sessions merged: {} of {} as {} by {}", ordered, car, saved.id, who.email)
        onChanged()
        call.respond(if (into != null) HttpStatusCode.OK else HttpStatusCode.Created, saved.view())
    }

    post("/api/admin/merges/{id}/split") {
        call.signedIn(change = true) ?: return@post
        val merge = merges.merge(call.parameters["id"].orEmpty().lowercase())
            ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such merged session."))
        val who = call.may(Action.EDIT, Thing(Kind.CAR, merge.car)) ?: return@post
        val saved = merges.store.save(merge.copy(split = true), merge.revision, clock.instant())
            ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("conflict", "It changed just now: reload and try again."))
        adminLog.info("sessions unmerged: {} of {} ({}) by {}", merge.id, merge.car, merge.members, who.email)
        onChanged()
        call.respond(saved.view())
    }
}
