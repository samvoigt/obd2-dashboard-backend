package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Action
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.LapSpeeds
import com.obd2dashboard.backend.archive.MergedLog
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.archive.membersOf
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.timing.CarRace
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A part of an event in its download (M29): where its folder is, its window, flags, sessions and stints. */
@Serializable
data class ExportPart(
    val id: String,
    val kind: String,
    val name: String,
    val folder: String,
    /** The server's time, epoch milliseconds. */
    val start: Long,
    val end: Long,
    /** The race only: as entered, the server's time. */
    val green: Long? = null,
    val flag: Long? = null,
    /** Each car's sessions in it, in the order first heard. */
    val sessions: Map<String, List<String>>,
    /** Sessions put in it, or taken out, by hand. */
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

/** `event.json` (M29): the event as stored, its drivers named. */
@Serializable
data class ExportEvent(
    val id: String,
    val name: String,
    val date: String,
    val endDate: String? = null,
    val course: String,
    val courseName: String,
    val layout: String,
    val layoutName: String,
    val cars: List<CarRef>,
    val parts: List<ExportPart>,
    val drivers: List<DriverView>,
    /** The cars whose folders are in this download, and those left out (not yours to edit). */
    val included: List<String>,
    val leftOut: List<String>,
    val exportedAt: String,
)

/** A driver change within a session (M27), on its tablet's `wall`. */
@Serializable
data class ExportDriverChange(val from: Long, val driver: DriverView? = null)

/** A session in `sessions.json` (M29): its header, VIN included, and everything the site knows of it. */
@Serializable
data class ExportSession(
    val id: String,
    val car: String,
    /** From its header: when the tablet started it, which tablet and app, the car's VIN and protocol. */
    val started: String? = null,
    val device: String? = null,
    val app: String? = null,
    val vin: String? = null,
    val protocol: String? = null,
    val source: String? = null,
    /** When the server first and last heard it. */
    val heardFrom: String,
    val heardTo: String,
    /** Who drove it from its start, and each change after (M27). */
    val driver: DriverView? = null,
    val driverChanges: List<ExportDriverChange> = emptyList(),
    val name: String? = null,
    /** False while it's still being driven or uploaded: its log is what's stored so far. */
    val complete: Boolean,
    val lines: Long,
    val sha256: String? = null,
    /** The server's clock less the tablet's (M18.1), milliseconds; null if not measured. */
    val clockOffsetMs: Long? = null,
    /** The merged session it's part of (M33.6), shown as one on the site; null if none. */
    val merged: String? = null,
)

/** A lap in `laps.csv` (M29). */
data class ExportLap(
    val session: String,
    /** Through the race in a race part; as the tablet numbered it in a practice. */
    val lap: Int,
    val start: Long,
    val end: Long,
    val time: Double,
    val sectors: List<Double>,
    val pitIn: Boolean,
    val pitOut: Boolean,
    val source: String,
    val driver: DriverView?,
    /** The race only. */
    val stint: Int? = null,
    /** Set where the lap was timed on another layout than the event's. */
    val otherLayout: String? = null,
    val live: Boolean = false,
)

/**
 * **An event's data as one zip** (M29, Sam 2026-10-20): a folder per part, and
 * in each a folder per car, its sessions merged into one raw log (every line as
 * the tablet sent it, VIN included), with who drove when, the laps and the
 * stints beside it. Pure but for the reading, so tested without a server.
 */
object EventExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Each part's folder: in time order, numbered, named after the part. */
    fun folders(event: Event): Map<String, String> =
        event.parts.sortedBy { it.start }.mapIndexed { i, p -> p.id to "${i + 1}-${slug(p.name).ifEmpty { p.id }}" }.toMap()

    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * Writes the zip to [out]: [cars] get their folders; [leftOut] are named in the README. [inParts] is
     * `EventRules.sessionsIn`'s answer; [read] streams a session's stored lines.
     */
    suspend fun write(
        out: OutputStream,
        event: Event,
        results: EventResults,
        records: Map<String, SessionRecord>,
        inParts: Map<String, List<String>>,
        drivers: Map<String, Driver>,
        cars: List<String>,
        leftOut: List<String>,
        now: Instant,
        read: suspend (SessionRecord, suspend (InputStream) -> Unit) -> Unit,
    ) {
        val zip = ZipOutputStream(out)
        val folders = folders(event)
        fun entry(name: String, write: (OutputStream) -> Unit) {
            zip.putNextEntry(ZipEntry("${event.id}/$name"))
            write(Unclosed(zip))
            zip.closeEntry()
        }
        fun text(name: String, body: String) = entry(name) { it.write(body.toByteArray()) }

        val carsIn = cars.associateWith { car -> event.parts.filter { p -> inParts[p.id].orEmpty().any { records[it]?.car == car } }.map { it.id } }
        text("README.txt", readme(event, results, folders, carsIn, leftOut, now))
        text("event.json", json.encodeToString(ExportEvent.serializer(), exportEvent(event, results, records, inParts, drivers, folders, cars, leftOut, now)))
        text("results.json", json.encodeToString(EventResults.serializer(), results))
        val race = results.race
        for (part in event.parts.sortedBy { it.start }) {
            for (car in cars) {
                val sessions = inParts[part.id].orEmpty().mapNotNull { records[it] }.filter { it.car == car }.sortedBy { it.created }
                if (sessions.isEmpty()) continue
                val dir = "${folders.getValue(part.id)}/$car"
                val speeds = LapSpeeds()
                var current: String? = null
                zip.putNextEntry(ZipEntry("${event.id}/$dir/raw.jsonl"))
                MergedLog.write(sessions, read, Unclosed(zip)) { r, line ->
                    if (r.id != current) { current = r.id; speeds.session() }
                    speeds.line(line)
                }
                zip.closeEntry()
                text("$dir/sessions.json", json.encodeToString(sessionsSerializer, sessions.map { exportSession(it, drivers, results.merged[it.id]) }))
                val carRace = race?.cars?.firstOrNull { it.car == car }
                val offset = race?.tabletOffset?.get(car)
                val laps = laps(part, car, sessions.map { it.id }.toSet(), results, carRace, drivers)
                val offsets = sessions.associate { it.id to (it.clockOffsetMs ?: offset) }
                text("$dir/laps.csv", lapsCsv(laps, offsets, speeds::of))
                if (part.kind == PartKind.RACE && carRace != null) text("$dir/stints.csv", stintsCsv(carRace, laps, drivers, offset))
            }
        }
        zip.finish()
        zip.flush()
    }

    private val sessionsSerializer = kotlinx.serialization.builtins.ListSerializer(ExportSession.serializer())

    /** Nothing written into an entry may close the zip under it. */
    private class Unclosed(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    private fun driver(id: String?, drivers: Map<String, Driver>): DriverView? =
        id?.let { drivers[it]?.view() ?: DriverView(it, "", "") }

    fun exportEvent(
        event: Event, results: EventResults, records: Map<String, SessionRecord>, inParts: Map<String, List<String>>,
        drivers: Map<String, Driver>, folders: Map<String, String>, cars: List<String>, leftOut: List<String>, now: Instant,
    ): ExportEvent {
        val named = mutableSetOf<String>()
        fun named(id: String?) = driver(id, drivers)?.also { named += it.id }
        val parts = event.parts.sortedBy { it.start }.map { p ->
            ExportPart(
                p.id, p.kind.name.lowercase(), p.name, folders.getValue(p.id), p.start.toEpochMilli(), p.end.toEpochMilli(),
                p.green?.toEpochMilli(), p.flag?.toEpochMilli(),
                inParts[p.id].orEmpty().mapNotNull { records[it] }.groupBy { it.car }.mapValues { (_, r) -> r.sortedBy { it.created }.map { it.id } },
                p.added, p.removed,
            )
        }
        records.values.filter { r -> inParts.values.any { r.id in it } }.forEach { r -> named(r.driver); r.driverChanges.forEach { named(it.driver) } }
        results.race?.cars.orEmpty().forEach { c -> c.stints.forEach { named(it.driver) } }
        val e = results.event
        return ExportEvent(
            event.id, event.name, event.date, event.endDate, e.course, e.courseName, e.layout, e.layoutName, e.cars, parts,
            named.mapNotNull { driver(it, drivers) }.sortedBy { it.code }, cars, leftOut, now.toString(),
        )
    }

    fun exportSession(r: SessionRecord, drivers: Map<String, Driver>, merged: String? = null): ExportSession = ExportSession(
        r.id, r.car, r.header?.started, r.header?.device, r.header?.app, r.header?.vin, r.header?.protocol, r.header?.source ?: r.summary?.source,
        r.created.toString(), r.updated.toString(),
        driver(r.driver, drivers), r.driverChanges.map { ExportDriverChange(it.from, driver(it.driver, drivers)) },
        r.name, r.complete, r.ackedThrough + 1, r.sha256, r.clockOffsetMs, merged,
    )

    /**
     * A car's laps in [part]: in a race part, the race's laps (numbered through it) in [sessions], each its
     * stint's driver; in a practice, each session's laps, each its driver's (M27).
     */
    fun laps(part: Part, car: String, sessions: Set<String>, results: EventResults, race: CarRace?, drivers: Map<String, Driver>): List<ExportLap> =
        if (part.kind == PartKind.RACE) {
            race?.laps.orEmpty().filter { it.session in sessions }.map { l ->
                ExportLap(
                    l.session, l.number, l.start, l.end, l.time, l.sectors, l.pitIn, l.pitOut, l.source,
                    driver(race!!.stints.firstOrNull { it.number == l.stint }?.driver, drivers), stint = l.stint, live = l.live,
                )
            }
        } else {
            // A merged session's row (M33.6) has the merge's id; each lap names its member, which the log and offsets know.
            results.parts.firstOrNull { it.part.id == part.id }?.sessions.orEmpty().filter { it.car == car }.flatMap { row ->
                row.laps.filter { (it.session ?: row.id) in sessions }.map { l ->
                    ExportLap(l.session ?: row.id, l.lap, l.start, l.end, l.time, l.sectors, l.pitIn, l.pitOut, l.source, row.driver, otherLayout = row.otherLayout, live = row.live)
                }
            }.sortedBy { it.end }
        }

    /** `laps.csv`: a row a lap, times of day from the tablet's clock and its offset, speeds in km/h. */
    fun lapsCsv(laps: List<ExportLap>, offsets: Map<String, Long?>, speeds: (Long, Long) -> LapSpeeds.Speed?): String {
        val sectors = laps.maxOfOrNull { it.sectors.size } ?: 0
        val head = listOf("session", "lap", "stint", "driver_code", "driver_name", "start_ms", "end_ms", "start_time", "end_time", "time_s") +
            (1..sectors).map { "sector_${it}_s" } +
            listOf("pit_in", "pit_out", "source", "top_kmh", "low_kmh", "avg_kmh", "other_layout", "live")
        return csv(head, laps.map { l ->
            val offset = offsets[l.session] ?: 0
            val s = speeds(l.start, l.end)
            listOf(
                l.session, l.lap.toString(), l.stint?.toString().orEmpty(), l.driver?.code.orEmpty(), l.driver?.name.orEmpty(),
                l.start.toString(), l.end.toString(), Instant.ofEpochMilli(l.start + offset).toString(), Instant.ofEpochMilli(l.end + offset).toString(),
                seconds(l.time),
            ) + (0 until sectors).map { l.sectors.getOrNull(it)?.let(::seconds).orEmpty() } + listOf(
                l.pitIn.toString(), l.pitOut.toString(), l.source,
                s?.max?.let(::kmh).orEmpty(), s?.min?.let(::kmh).orEmpty(), s?.avg?.let(::kmh).orEmpty(),
                l.otherLayout.orEmpty(), l.live.toString(),
            )
        })
    }

    /** `stints.csv`, a race part's: each of the car's stints whose laps are in it. */
    fun stintsCsv(race: CarRace, laps: List<ExportLap>, drivers: Map<String, Driver>, offset: Long?): String {
        val numbers = laps.mapNotNull { it.stint }.toSet()
        val head = listOf("stint", "driver_code", "driver_name", "start_ms", "start_time", "first_lap", "last_lap", "laps", "time_s", "best_s")
        return csv(head, race.stints.filter { it.number in numbers }.map { s ->
            val d = driver(s.driver, drivers)
            listOf(
                s.number.toString(), d?.code.orEmpty(), d?.name.orEmpty(), s.start.toString(), Instant.ofEpochMilli(s.start + (offset ?: 0)).toString(),
                s.firstLap?.toString().orEmpty(), s.lastLap?.toString().orEmpty(), s.laps.toString(), seconds(s.seconds), s.best?.let(::seconds).orEmpty(),
            )
        })
    }

    private fun seconds(s: Double) = "%.3f".format(java.util.Locale.ROOT, s)
    private fun kmh(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)

    fun csv(head: List<String>, rows: List<List<String>>): String = buildString {
        for (row in listOf(head) + rows) append(row.joinToString(",") { cell(it) }).append("\r\n")
    }

    private fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s

    fun readme(event: Event, results: EventResults, folders: Map<String, String>, carsIn: Map<String, List<String>>, leftOut: List<String>, now: Instant): String = buildString {
        val name = { slug: String -> results.event.cars.firstOrNull { it.slug == slug }?.name ?: slug }
        appendLine("${event.name}, ${event.date}${event.endDate?.let { " to $it" } ?: ""}: its data, downloaded $now.")
        appendLine()
        appendLine("Parts, each a folder:")
        for (p in event.parts.sortedBy { it.start }) {
            val here = carsIn.filterValues { p.id in it }.keys.map(name)
            appendLine("  ${folders.getValue(p.id)}/  ${p.name} (${p.kind.name.lowercase()}), ${p.start} to ${p.end}: ${if (here.isEmpty()) "no sessions" else here.joinToString(", ")}")
        }
        if (leftOut.isNotEmpty()) {
            appendLine()
            appendLine("Left out, as you can't edit them: ${leftOut.joinToString(", ") { name(it) }}. Their laps are still in results.json.")
        }
        appendLine()
        appendLine(
            """
            In each part, a folder per car:
              raw.jsonl      The car's sessions in this part, one after another, in the order the server first
                             heard them. Every line is exactly as the tablet sent it (its VIN is in each session's
                             first line). Each session starts with its own "session" line, which carries its id;
                             "seq" counts from 0 again after it. "wall" is the tablet's clock, milliseconds.
              sessions.json  Each session: its header (VIN included), when the server heard it, who drove it from
                             its start and each driver change after, its name, and whether it's complete.
              laps.csv       Every lap: in a race part numbered through the race, with its stint; in a practice as
                             the tablet numbered them. Times of day are the tablet's clock, corrected by how far the
                             server measured it out. Speeds in km/h from the raw log, as the session's page has
                             them: top and lowest of the samples, average over the GPS positions; blank where the
                             samples don't cover the lap.
              stints.csv     Race parts only: each stint, its driver, laps and time. A stint is one driver's run,
                             from the sessions' drivers and driver changes (sessions.json).

            event.json       The event: its parts and their windows, flags, sessions by car, and the drivers named.
            results.json     The event's results, as its page shows them.
            """.trimIndent(),
        )
    }
}

/** `GET /api/admin/events/{id}/download` (M29): the event's data, for whoever may edit one of its cars; one at a time. */
fun Route.eventDownloadRoutes(stores: EventStores, archive: ArchiveService, service: EventResultsService) {
    val busy = AtomicBoolean(false)
    get("/api/admin/events/{id}/download") {
        val who = call.signedIn(change = false) ?: return@get
        val event = stores.events.get(call.parameters["id"].orEmpty())
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No such event."))
        val gate = call.application.gate
        // A car's raw logs hold its VIN: only its editors' (M23), as one session's download.
        val (cars, leftOut) = event.cars.partition { gate.may(who, Action.EDIT, Thing(Kind.CAR, it)) }
        if (cars.isEmpty()) return@get call.respond(HttpStatusCode.Forbidden, ApiError("not_allowed", "You can't edit any of this event's cars."))
        if (!busy.compareAndSet(false, true)) {
            return@get call.respond(HttpStatusCode.TooManyRequests, ApiError("busy", "Another download is running; try again in a minute."))
        }
        try {
            // Signed in, so nothing is hidden (M25).
            val results = service.results(event, Hidden.NOTHING)
            val records = archive.sessionsOfCars(event.cars).filter { it.ackedThrough >= 0 }.associateBy { it.id }
            val inParts = EventRules.sessionsIn(event, records.values.map { it.heard() }, stores.merges.membersOf(event.cars))
            val drivers = stores.drivers.list().associateBy { it.id }
            adminLog.info("event downloaded: {}, cars {} by {}", event.id, cars, who.email)
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${event.id}-data.zip\"")
            call.respondOutputStream(ContentType.Application.Zip) {
                withContext(Dispatchers.IO) {
                    EventExport.write(this@respondOutputStream, event, results, records, inParts, drivers, cars, leftOut, Instant.now()) { r, reader ->
                        archive.read(r, reader)
                    }
                }
            }
        } finally {
            busy.set(false)
        }
    }
}
