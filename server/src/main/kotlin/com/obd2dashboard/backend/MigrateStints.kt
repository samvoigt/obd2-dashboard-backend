package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.DriverChange
import com.obd2dashboard.backend.archive.SessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex
import com.obd2dashboard.backend.archive.gcp.GcsSegmentStore
import com.obd2dashboard.backend.archive.gcp.FirestoreCourseStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.events.Stint
import com.obd2dashboard.backend.archive.gcp.FirestoreDriverStore
import com.obd2dashboard.backend.archive.gcp.FirestoreEventStore
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore
import com.obd2dashboard.backend.timing.CarRace
import com.obd2dashboard.backend.timing.RaceLap
import java.time.Instant
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * **Hand-edited stints into the sessions' drivers** (M32.6, decision 48), once.
 * For each race part's edited stints, each race session of the car gets the
 * driver in force at its first lap, and a driver change at each stint that
 * starts inside its laps. A stint that starts at a session's first lap with
 * the same driver as before (a split the edits made there) is written as a
 * change at that lap, so it stays split. Stints with no laps are dropped.
 * Pure, so it's tested without the cloud; [main] runs it.
 */
object StintMigration {
    /** What a session's drivers become. */
    data class SessionPlan(val id: String, val driver: String?, val changes: List<DriverChange>)

    /** A stint as the race shows it, for comparing before and after. */
    data class StintLaps(val driver: String?, val firstLap: Int, val lastLap: Int)

    /** Each lap's stint index by the edited marks: the last that began before the lap ended (the rule edited stints had). */
    private fun markOf(lap: RaceLap, marks: List<Stint>): Int = marks.indexOfLast { it.start < lap.end }.coerceAtLeast(0)

    /** Each lap's driver as the edited marks have it, by lap number. */
    fun before(laps: List<RaceLap>, marks: List<Stint>): Map<Int, String?> {
        val sorted = marks.sortedBy { it.start }
        return laps.associate { it.number to sorted.getOrNull(markOf(it, sorted))?.driver }
    }

    /** The stints the edited marks make over [laps], the empty ones left out. */
    fun beforeStints(laps: List<RaceLap>, marks: List<Stint>): List<StintLaps> {
        val sorted = marks.sortedBy { it.start }
        return laps.groupBy { markOf(it, sorted) }.toSortedMap().map { (i, mine) -> StintLaps(sorted[i].driver, mine.first().number, mine.last().number) }
    }

    /** [laps] (one car's, in one race part, in order) and the part's [marks] for that car: what each session's drivers become. */
    fun plan(laps: List<RaceLap>, marks: List<Stint>): List<SessionPlan> {
        val sorted = marks.sortedBy { it.start }
        val sessions = laps.map { it.session }.distinct()
        var before: Int? = null // the mark in force at the previous session's last lap
        return sessions.map { id ->
            val mine = laps.filter { it.session == id }
            val first = mine.first()
            val atStart = markOf(first, sorted)
            val changes = mutableListOf<DriverChange>()
            // A split at the session's first lap with the same driver as before: kept as a change there.
            if (before != null && atStart != before && sorted[atStart].driver == sorted[before].driver) changes += DriverChange(first.start, sorted[atStart].driver)
            // Each stint that starts inside the session's laps (after its first lap's start, before its last lap's end).
            changes += sorted.withIndex().filter { (i, m) -> m.start > first.start && m.start < mine.last().end && mine.any { markOf(it, sorted) == i } }
                .map { (_, m) -> DriverChange(m.start, m.driver) }
            before = markOf(mine.last(), sorted)
            SessionPlan(id, sorted.getOrNull(atStart)?.driver, changes.distinctBy { it.from }.sortedBy { it.from })
        }
    }

    /** The stints a race shows over [laps] (the race's own numbering), the empty ones left out. */
    fun stintsOf(race: CarRace, laps: List<RaceLap>): List<StintLaps> {
        val numbers = laps.map { it.number }.toSet()
        return race.stints.filter { it.laps > 0 && it.firstLap in numbers }.map { StintLaps(it.driver, it.firstLap!!, it.lastLap!!) }
    }

    /** Each lap's driver as [race] has it, by lap number. */
    fun after(race: CarRace, laps: List<RaceLap>): Map<Int, String?> {
        val numbers = laps.map { it.number }.toSet()
        return race.laps.filter { it.number in numbers }.associate { it.number to race.stints.getOrNull(it.stint - 1)?.driver }
    }
}

/** [base], but with the planned sessions' drivers in place: what the race would be, read without writing anything. */
class PlannedIndex(private val base: SessionIndex, private val planned: Map<String, StintMigration.SessionPlan>) : SessionIndex by base {
    private fun SessionRecord.planned() = planned[id]?.let { copy(driver = it.driver, driverChanges = it.changes) } ?: this
    override suspend fun get(id: String): SessionRecord? = base.get(id)?.planned()
    override suspend fun listByCar(car: String): List<SessionRecord> = base.listByCar(car).map { it.planned() }
}

/**
 * `scripts/migrate-stints.sh [--apply | --clear]`, Sam's to run on production (M32.7):
 * - no argument: a dry run, printing each race's laps and time per driver and its stints before and after, and
 *   what it would write;
 * - `--apply`: writes the sessions' drivers and changes, only if every race's before and after match;
 * - `--clear`: empties every race's edited stints, only once the stored drivers already give the same stints.
 */
fun main(args: Array<String>) = runBlocking {
    val mode = args.firstOrNull() ?: "--dry-run"
    if (mode !in setOf("--dry-run", "--apply", "--clear")) { System.err.println("usage: migrate-stints.sh [--apply | --clear]"); exitProcess(2) }
    val project = System.getenv("GCP_PROJECT")?.takeIf { it.isNotBlank() } ?: error("GCP_PROJECT is not set")
    val bucket = System.getenv("SESSIONS_BUCKET")?.takeIf { it.isNotBlank() } ?: error("SESSIONS_BUCKET is not set")
    val registry = CarRegistry(FirestoreCarStore.connect(project))
    val index = FirestoreSessionIndex.connect(project)
    val store = GcsSegmentStore.connect(project, bucket)
    val courses = FirestoreCourseStore.connect(project)
    val stores = EventStores(FirestoreDriverStore.connect(project), FirestoreEventStore.connect(project))
    val scope = CoroutineScope(Dispatchers.Default)
    fun service(archive: ArchiveService) = EventResultsService(stores, courses, registry, archive, RetimingJobs(registry, archive, courses, scope))
    val archive = ArchiveService(index, store)
    val now = service(archive)
    val codes = stores.drivers.list().associate { it.id to it.code }
    fun name(id: String?) = id?.let { codes[it] ?: it } ?: "nobody"

    val edited = stores.events.list().filter { e -> e.parts.any { it.kind == PartKind.RACE && it.stints.isNotEmpty() } }
    if (edited.isEmpty()) { println("No race has edited stints: nothing to do."); return@runBlocking }
    var allMatch = true
    val writes = mutableListOf<StintMigration.SessionPlan>()
    for (event in edited) {
        val records = archive.sessionsOfCars(event.cars).associateBy { it.id }
        val inParts = EventRules.sessionsIn(event, records.values.map { it.heard() })
        val race = now.eventResults(event).race ?: continue
        // Plan every car's race sessions, part by part.
        val plans = mutableMapOf<String, StintMigration.SessionPlan>()
        val checks = mutableListOf<Triple<Pair<String, String>, List<RaceLap>, List<Stint>>>()
        for (part in event.races) for ((car, marks) in part.stints) {
            val laps = race.cars.firstOrNull { it.car == car }?.laps.orEmpty().filter { it.session in inParts[part.id].orEmpty() }
            if (laps.isEmpty()) continue
            StintMigration.plan(laps, marks).forEach { plans[it.id] = it }
            checks += Triple(part.id to car, laps, marks)
        }
        // After: the race worked out by this server's rule from the planned drivers (or, for --clear, the stored ones).
        val afterIndex = if (mode == "--clear") index else PlannedIndex(index, plans)
        val after = service(ArchiveService(afterIndex, store)).eventResults(event).race
        println("== ${event.name} (${event.id})")
        for ((key, laps, marks) in checks) {
            val (part, car) = key
            val carAfter = after?.cars?.firstOrNull { it.car == car }
            val beforeDrivers = StintMigration.before(laps, marks)
            val afterDrivers = carAfter?.let { StintMigration.after(it, laps) }.orEmpty()
            val beforeStints = StintMigration.beforeStints(laps, marks)
            val afterStints = carAfter?.let { StintMigration.stintsOf(it, laps) }.orEmpty()
            val match = beforeDrivers == afterDrivers && beforeStints == afterStints
            allMatch = allMatch && match
            println("-- ${event.parts.first { it.id == part }.name}, $car: ${if (match) "MATCH" else "DIFFERS"}")
            fun perDriver(drivers: Map<Int, String?>) = laps.groupBy { drivers[it.number] }.entries.sortedBy { it.key.orEmpty() }
                .joinToString("  ") { (d, ls) -> "${name(d)} ${ls.size} laps ${raceClockText(ls.sumOf { it.time })}" }
            println("   before: ${perDriver(beforeDrivers)}")
            println("   after:  ${perDriver(afterDrivers)}")
            println("   stints before: ${beforeStints.joinToString(" · ") { "${name(it.driver)} ${it.firstLap}–${it.lastLap}" }}")
            println("   stints after:  ${afterStints.joinToString(" · ") { "${name(it.driver)} ${it.firstLap}–${it.lastLap}" }}")
            if (!match) laps.filter { beforeDrivers[it.number] != afterDrivers[it.number] }.take(5).forEach {
                println("   lap ${it.number}: before ${name(beforeDrivers[it.number])}, after ${name(afterDrivers[it.number])}")
            }
        }
        if (mode != "--clear") for (p in plans.values.sortedBy { records[it.id]?.created }) {
            val r = records[p.id]
            val now0 = "${name(r?.driver)}${r?.driverChanges.orEmpty().joinToString("") { ", then ${name(it.driver)} from ${it.from}" }}"
            val then = "${name(p.driver)}${p.changes.joinToString("") { ", then ${name(it.driver)} from ${it.from}" }}"
            println("   session ${p.id.take(8)}: $now0  ->  $then")
            writes += p
        }
    }
    when (mode) {
        "--dry-run" -> println(if (allMatch) "\nEvery race matches. Run with --apply to write the sessions' drivers." else "\nSome race differs: nothing would be written.")
        "--apply" -> {
            if (!allMatch) { println("\nSome race differs: nothing written."); exitProcess(1) }
            for (p in writes) check(index.setDriver(p.id, p.driver) && index.setDriverChanges(p.id, p.changes)) { "session ${p.id} is gone" }
            println("\nWrote ${writes.size} session(s)' drivers. Deploy, check the race, then run --clear.")
        }
        "--clear" -> {
            if (!allMatch) { println("\nThe stored drivers don't give the same stints yet (run --apply first, and deploy): nothing cleared."); exitProcess(1) }
            for (event in edited) {
                val e: Event = stores.events.get(event.id) ?: continue
                stores.events.save(e.copy(parts = e.parts.map { it.copy(stints = emptyMap()) }), e.revision, Instant.now())
                    ?: error("${e.id} changed while clearing: run --clear again")
                println("Cleared ${e.id}'s edited stints.")
            }
        }
    }
    exitProcess(0)
}

private fun raceClockText(seconds: Double): String {
    val s = Math.round(seconds)
    return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}
