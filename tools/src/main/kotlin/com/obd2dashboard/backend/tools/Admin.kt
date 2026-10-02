package com.obd2dashboard.backend.tools

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.AccessStore
import com.obd2dashboard.backend.admin.CarAdmin
import com.obd2dashboard.backend.admin.InMemoryAccessStore
import com.obd2dashboard.backend.admin.InMemoryUserStore
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.admin.UserStore
import com.obd2dashboard.backend.admin.normalEmail
import com.obd2dashboard.backend.admin.CarHasSessions
import com.obd2dashboard.backend.admin.NoSuchSession
import com.obd2dashboard.backend.admin.SessionBusy
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.SessionIndex
import com.obd2dashboard.backend.archive.gcp.FirestoreAccessStore
import com.obd2dashboard.backend.archive.gcp.FirestoreCourseStore
import com.obd2dashboard.backend.archive.gcp.FirestoreDriverStore
import com.obd2dashboard.backend.archive.gcp.FirestoreEventStore
import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.DriverStore
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.EventRules
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import com.obd2dashboard.backend.registry.SlugCheck
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import com.obd2dashboard.backend.events.EventStore
import com.obd2dashboard.backend.archive.gcp.FirestoreMessageStore
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex
import com.obd2dashboard.backend.archive.gcp.FirestoreUserStore
import com.obd2dashboard.backend.archive.gcp.GcsSegmentStore
import com.obd2dashboard.backend.courses.CourseCheck
import com.obd2dashboard.backend.courses.CourseRules
import com.obd2dashboard.backend.timing.removeTimings
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.live.MessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.IssuedToken
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * The owner's tool for cars and sessions: run as the owner, against Firestore
 * and Cloud Storage directly, so the server has no admin endpoint (M2 plan). Run
 * it through `scripts/admin.sh`, which supplies the project and bucket.
 */
fun main(args: Array<String>) {
    Admin(
        toolsFor = { project, bucket ->
            val index = FirestoreSessionIndex.connect(project)
            Tools(
                registry = CarRegistry(FirestoreCarStore.connect(project)),
                sessions = index,
                archive = ArchiveService(index, GcsSegmentStore.connect(project, bucket)),
                messages = FirestoreMessageStore.connect(project),
                courses = FirestoreCourseStore.connect(project),
                drivers = FirestoreDriverStore.connect(project),
                events = FirestoreEventStore.connect(project),
                users = FirestoreUserStore.connect(project),
                access = FirestoreAccessStore.connect(project),
            )
        },
        io = ConsoleIo,
    ).main(args)
    // The Firestore client's channel would otherwise keep the JVM alive.
    exitProcess(0)
}

/** The root command. Subcommands find their services in the context it sets. */
class Admin(
    private val toolsFor: (project: String, bucket: String) -> Tools,
    private val io: AdminIo,
) : CliktCommand(name = "admin") {
    private val project by option("--project", help = "Google Cloud project; admin.sh sets it").required()
    private val bucket by option("--bucket", help = "the sessions bucket; admin.sh sets it").required()

    init {
        subcommands(
            AddCar(io), RotateToken(io), SetToken(io), SetPasscode(io), Rename(), ListCars(), RemoveCar(io),
            ListSessions(), ShowSession(), DeleteSession(io), ImportCourse(), RemoveCourse(io), ListDrivers(), ListEvents(), AddDriver(), RemoveDriver(io), ImportEvent(), RemoveEvent(io),
            ListUsers(), AddUser(), RemoveUser(io), Share(), Unshare(),
        )
    }

    override fun help(context: Context) = "Manage registered cars (tokens, passcodes, names) and their sessions."

    override fun run() {
        currentContext.findOrSetObject { toolsFor(project, bucket) }
    }
}

/** What subcommands share, set by [Admin.run]. */
class Tools(
    val registry: CarRegistry,
    val sessions: SessionIndex,
    val archive: ArchiveService,
    val messages: MessageStore,
    val courses: CourseStore,
    val drivers: DriverStore,
    val events: EventStore,
    /** Invited users (M23). */
    val users: UserStore = InMemoryUserStore(),
    /** Each thing's creator and editors (M23). */
    val access: AccessStore = InMemoryAccessStore(),
) {
    /** The rules shared with the admin page (M6.1). */
    val admin: CarAdmin = CarAdmin(registry, archive, Messages(messages))
}

/**
 * A subcommand that talks to the registry. A refusal from the registry is
 * shown as it is (its messages are written for a person) and exits 1.
 */
abstract class RegistryCommand(name: String, private val helpText: String) : CliktCommand(name = name) {
    protected val tools: Tools by requireObject<Tools>()

    override fun help(context: Context) = helpText

    final override fun run() = runBlocking {
        try {
            execute(tools.registry)
        } catch (e: RegistryException) {
            throw CliktError(e.message)
        }
    }

    abstract suspend fun execute(registry: CarRegistry)

    protected fun slugOf(raw: String): Slug = Slug.parse(raw)

    protected fun showToken(issued: IssuedToken, what: String) {
        echo("$what ${issued.car.slug} (${issued.car.name}).")
        echo()
        echo("  ${issued.token}")
        echo()
        echo("This token is shown once and cannot be retrieved. Put it in the tablet's")
        echo("Cars page now, or keep it somewhere safe.")
    }
}

class AddCar(private val io: AdminIo) : RegistryCommand("add-car", "Register a car and print its token, once.") {
    private val slug by argument(help = "Permanent URL name: a-z, 0-9 and -, 2 to 32 characters")
    private val name by option("--name", help = "Display name; can be changed later").required()
    private val chooseToken by option(
        "--choose-token",
        help = "type a token of your own (twice, not echoed) instead of getting a generated one",
    ).flag()
    private val tokenFile by option(
        "--token-file",
        help = "choose the token by reading it from a file only you can read (chmod 600)",
    ).path(mustExist = true)

    override suspend fun execute(registry: CarRegistry) {
        if (!chooseToken && tokenFile == null) return showToken(registry.addCar(slugOf(slug), name), "Added")
        // Read and check the chosen token first, so a typo costs nothing.
        val car = slugOf(slug)
        val token = readChosenToken(io, tokenFile, "Token for $car: ")
        tools.admin.addCar(car, name, token) // shared with the admin page (M6.4)
        echo("Added $car (${name.trim()}), with your token (ends …${Tokens.hint(token)}).")
        echo("Put the same token in the tablet's Cars page.")
    }
}

class RotateToken(private val io: AdminIo) :
    RegistryCommand("rotate-token", "Replace a car's token. The old one stops working at once.") {
    private val slug by argument()

    override suspend fun execute(registry: CarRegistry) {
        val car = slugOf(slug)
        registry.get(car) ?: throw RegistryException.NoSuchCar(car)
        val answer = io.readLine("Rotate the token for $car? Its tablet stops sending until given the new one. [y/N] ")
        if (answer?.trim()?.lowercase() != "y") throw CliktError("Not rotated.")
        showToken(registry.rotateToken(car), "New token for")
    }
}

class SetToken(private val io: AdminIo) :
    RegistryCommand("set-token", "Set a car's token to one you choose. The old one stops working at once.") {
    private val slug by argument()
    private val tokenFile by option(
        "--token-file",
        help = "read it from a file only you can read (chmod 600); else typed twice",
    ).path(mustExist = true)

    override suspend fun execute(registry: CarRegistry) {
        val car = slugOf(slug)
        registry.get(car) ?: throw RegistryException.NoSuchCar(car)
        val token = readChosenToken(io, tokenFile, "New token for $car: ")
        registry.setToken(car, token)
        echo("Token set for $car (ends …${Tokens.hint(token)}). Its tablet needs the same one on its Cars page.")
    }
}

/**
 * A token the owner chose (decision 24): from a `chmod 600` file, or typed twice
 * without echo. Checked here, so a bad one is refused before anything changes.
 */
private fun readChosenToken(io: AdminIo, file: java.nio.file.Path?, prompt: String): String {
    val token = if (file != null) {
        refuseIfShared(file)
        Files.readString(file).trimEnd('\n', '\r')
    } else {
        val first = io.readSecret(prompt)
        val second = io.readSecret("Again: ")
        try {
            if (!first.contentEquals(second)) throw CliktError("The two tokens differ. Nothing changed.")
            String(first)
        } finally {
            first.fill('\u0000')
            second.fill('\u0000')
        }
    }
    Tokens.problemWith(token)?.let { throw CliktError("${it.replaceFirstChar(Char::uppercase)}. Nothing changed.") }
    return token
}

private fun refuseIfShared(file: java.nio.file.Path) {
    val perms = Files.getPosixFilePermissions(file)
    if (perms.any { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }) {
        throw CliktError("$file can be read by others; chmod 600 it first. Nothing changed.")
    }
}

class SetPasscode(private val io: AdminIo) :
    RegistryCommand("set-passcode", "Set a car's crew passcode, for sending messages.") {
    private val slug by argument()
    private val passcodeFile by option(
        "--passcode-file",
        help = "read it from a file only you can read (chmod 600), for scripted checks; else typed twice",
    ).path(mustExist = true)

    override suspend fun execute(registry: CarRegistry) {
        val car = slugOf(slug)
        registry.get(car) ?: throw RegistryException.NoSuchCar(car)
        passcodeFile?.let { file ->
            refuseIfShared(file)
            val passcode = Files.readString(file).trimEnd('\n', '\r').toCharArray()
            try {
                registry.setPasscode(car, passcode)
            } finally {
                passcode.fill('\u0000')
            }
            echo("Passcode set for $car.")
            return
        }
        val first = io.readSecret("New passcode for $car: ")
        val second = io.readSecret("Again: ")
        try {
            if (!first.contentEquals(second)) throw CliktError("The two passcodes differ. Nothing changed.")
            registry.setPasscode(car, first)
        } finally {
            first.fill('\u0000')
            second.fill('\u0000')
        }
        echo("Passcode set for $car.")
    }
}

class Rename : RegistryCommand("rename", "Change a car's display name. Its slug never changes.") {
    private val slug by argument()
    private val name by option("--name").required()

    override suspend fun execute(registry: CarRegistry) {
        registry.rename(slugOf(slug), name)
        echo("Renamed ${slugOf(slug)}.")
    }
}

class ListCars : RegistryCommand("list", "List cars. Never shows a token or a hash.") {
    private val issued = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(ZoneOffset.UTC)

    override suspend fun execute(registry: CarRegistry) {
        val cars = registry.list()
        if (cars.isEmpty()) {
            echo("No cars registered.")
            return
        }
        val access = tools.access.all()
        val rows = listOf(listOf("SLUG", "NAME", "TOKEN", "ISSUED", "PASSCODE", "CREATOR")) + cars.map {
            listOf(
                it.slug.value,
                it.name,
                "…${it.tokenHint}",
                issued.format(it.tokenIssued),
                if (it.passcodeHash != null) "set" else "not set",
                creatorOf(access[Thing(Kind.CAR, it.slug.value)]),
            )
        }
        val widths = rows.first().indices.map { col -> rows.maxOf { it[col].length } }
        for (row in rows) echo(row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd())
    }
}

class RemoveCar(private val io: AdminIo) :
    RegistryCommand("remove-car", "Remove a car. Its token stops working at once.") {
    private val slug by argument()

    override suspend fun execute(registry: CarRegistry) {
        val car = slugOf(slug)
        try {
            tools.admin.checkRemovable(car) // before asking, so a refusal costs no typing
            val typed = io.readLine("Type the slug again to remove $car: ")
            if (typed?.trim() != car.value) throw CliktError("Not removed.")
            val messages = tools.admin.removeCar(car)
            tools.access.remove(Thing(Kind.CAR, car.value))
            echo("Removed $car, and its $messages message(s).")
        } catch (e: CarHasSessions) {
            throw CliktError("$car has ${e.count} session(s). Delete them first (admin.sh sessions $car, then delete-session).")
        }
    }
}

class ListSessions : RegistryCommand("sessions", "List sessions, all or one car's. Never shows a VIN.") {
    private val car by argument(help = "a car's slug; all cars if omitted").optional()

    override suspend fun execute(registry: CarRegistry) {
        val sessions = (car?.let { tools.sessions.listByCar(slugOf(it).value) } ?: tools.sessions.list())
            .sortedByDescending { it.header?.started.orEmpty() }
        if (sessions.isEmpty()) {
            echo("No sessions.")
            return
        }
        val rows = listOf(listOf("ID", "CAR", "STARTED", "LINES", "STATE", "NAME")) + sessions.map {
            listOf(
                it.id,
                it.car,
                it.header?.started ?: "(no record yet)",
                (it.ackedThrough + 1).toString(),
                if (it.complete) "complete" else "uploading",
                it.name.orEmpty(),
            )
        }
        val widths = rows.first().indices.map { col -> rows.maxOf { it[col].length } }
        for (row in rows) echo(row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd())
    }
}

class ShowSession : RegistryCommand("session", "Everything the index holds about one session, its VIN included.") {
    private val id by argument()

    override suspend fun execute(registry: CarRegistry) {
        val s = tools.sessions.get(id.lowercase()) ?: throw CliktError("No session $id.")
        val h = s.header
        echo("id          ${s.id}")
        s.name?.let { echo("name        $it") }
        s.clockOffsetMs?.let { echo("clock       the tablet ${"%.1f".format(it / 1000.0)} s behind the server (measured live)") }
        echo("car         ${s.car}")
        echo("started     ${h?.started ?: "(no session record yet)"}")
        echo("format      ${h?.v?.let { "v$it" } ?: "-"}")
        echo("device      ${h?.device ?: "-"}")
        echo("app         ${h?.app ?: "-"}")
        echo("protocol    ${h?.protocol ?: "-"}")
        echo("vin         ${h?.vin ?: "(not given)"}")
        echo("lines       ${s.ackedThrough + 1}")
        echo("state       ${if (s.complete) "complete" else "uploading, ${s.segments.size} segment(s)"}")
        s.sha256?.let { echo("sha256      $it") }
        if (s.hashResets > 0) echo("resets      ${s.hashResets} (hash mismatches)")
        echo("created     ${s.created}")
        echo("updated     ${s.updated}")
    }
}

class DeleteSession(private val io: AdminIo) :
    RegistryCommand("delete-session", "Delete a session's data and its record. It cannot be undone.") {
    private val id by argument()

    override suspend fun execute(registry: CarRegistry) {
        // The rule shared with the admin page (M6.7). The tool can't see whether the
        // tablet is live, so it relies on the upload check alone.
        val session = try {
            tools.admin.checkDeletable(id.lowercase())
        } catch (_: NoSuchSession) {
            throw CliktError("No session $id.")
        } catch (_: SessionBusy) {
            throw CliktError("Session $id is still uploading. Try again once it has been quiet for 5 minutes.")
        }
        val typed = io.readLine("Type the session id again to delete it (car ${session.car}): ")
        if (typed?.trim()?.lowercase() != session.id) throw CliktError("Not deleted.")
        tools.admin.deleteSession(session.id)
        echo("Deleted ${session.id}.")
    }
}

/**
 * A course from a GeoJSON file (M12.3), saved as the next version: how NHMS is
 * seeded (`courses/seed/nhms.geojson`). The website's editor is the usual way.
 */
class ImportCourse : CliktCommand(name = "import-course") {
    private val tools: Tools by requireObject<Tools>()
    private val file by argument(help = "a course's GeoJSON").path(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val id by option("--id", help = "the course's id; the file's name if not given")
    private val name by option("--name", help = "its name; the GeoJSON's own if not given")

    override fun help(context: Context) = "Save a course from a GeoJSON file, as its next version."

    override fun run() = runBlocking {
        val geojson = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }
            .getOrElse { throw CliktError("${file.fileName} is not a JSON object: ${it.message}") }
        val courseId = id ?: file.fileName.toString().substringBefore('.')
        val courseName = name ?: (geojson["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val problems = listOfNotNull(CourseRules.idProblem(courseId), CourseRules.nameProblem(courseName)) +
            ((CourseRules.check(geojson) as? CourseCheck.Refused)?.problems.orEmpty())
        if (problems.isNotEmpty()) throw CliktError("Not saved:\n" + problems.joinToString("\n") { "  - $it" })
        val shape = (CourseRules.check(geojson) as CourseCheck.Ok).shape
        val expected = tools.courses.get(courseId)?.version ?: 0
        val saved = tools.courses.save(courseId, expected, courseName.trim(), geojson, Instant.now())
            ?: throw CliktError("Someone saved $courseId while this ran; run it again.")
        val layouts = shape.layouts.joinToString(", ") { "${it.id}${if (it.default) " (default)" else ""}, ${it.sectors.size} sector(s)" }
        echo("Saved $courseId (${saved.name}) as version ${saved.version}: $layouts.")
    }
}


/**
 * Removes a course and every version of it (M13.6), with its stored
 * re-timings, as the admin page does. A course whose laps a tablet timed is in
 * use and stays. The server is told nothing: tablets see it gone at their
 * next fetch of courses.
 */
class RemoveCourse(private val io: AdminIo) : CliktCommand(name = "remove-course") {
    private val tools: Tools by requireObject<Tools>()
    private val id by argument()

    override fun help(context: Context) = "Remove a course, every version of it, and its re-timings. It cannot be undone."

    override fun run() = runBlocking {
        val course = tools.courses.get(id) ?: throw CliktError("No course $id.")
        val all = tools.sessions.list()
        if (all.any { it.summary?.track == course.id }) throw CliktError("Laps were timed at ${course.id}, so it stays. Draw a new version instead.")
        val typed = io.readLine("Type the course id again to remove it (${course.name}, version ${course.version}): ")
        if (typed?.trim() != course.id) throw CliktError("Not removed.")
        tools.courses.delete(course.id)
        tools.access.remove(Thing(Kind.COURSE, course.id))
        val removed = tools.archive.removeTimings(all.map { it.id }, course.id)
        echo("Removed ${course.id}, and $removed re-timing(s).")
    }
}

/** Drivers (M14.3): made and changed on the admin page. */
class ListDrivers : CliktCommand(name = "drivers") {
    private val tools: Tools by requireObject<Tools>()

    override fun help(context: Context) = "List drivers."

    override fun run() = runBlocking {
        val drivers = tools.drivers.list()
        if (drivers.isEmpty()) echo("No drivers.") else drivers.forEach { echo("${it.code.padEnd(4)}  ${it.name}") }
    }
}

/** Events (M14.3): made and changed on the admin page. */
class ListEvents : CliktCommand(name = "events") {
    private val tools: Tools by requireObject<Tools>()

    override fun help(context: Context) = "List events and their parts."

    override fun run() = runBlocking {
        val events = tools.events.list()
        if (events.isEmpty()) return@runBlocking echo("No events.")
        for (e in events) {
            echo("${e.id}  ${e.name}, ${e.date}, ${e.course} (${e.layout}), cars ${e.cars.joinToString(", ")}")
            for (p in e.parts) echo("  ${p.id}  ${p.kind.name.lowercase().padEnd(8)}  ${p.name}: ${p.start} to ${p.end}")
        }
    }
}

/** A driver (M14.6), as the admin page's Drivers makes one. */
class AddDriver : CliktCommand(name = "add-driver") {
    private val tools: Tools by requireObject<Tools>()
    private val name by option("--name", help = "the driver's name").required()
    private val code by option("--code", help = "2–4 capital letters, for tables").required()

    override fun help(context: Context) = "Add a driver."

    override fun run() = runBlocking {
        val id = "d-" + ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val driver = Driver(id, name.trim(), code.trim())
        val problems = EventRules.driverProblems(driver, tools.drivers.list())
        if (problems.isNotEmpty()) throw CliktError("Not added:\n" + problems.joinToString("\n") { "  - $it" })
        tools.drivers.put(driver) ?: throw CliktError("Another driver took the code ${driver.code} just now.")
        echo("Added ${driver.name} (${driver.code}).")
    }
}

/** Removes a driver by their code (M14.6): never one who drove, as the admin page. */
class RemoveDriver(private val io: AdminIo) : CliktCommand(name = "remove-driver") {
    private val tools: Tools by requireObject<Tools>()
    private val code by argument(help = "the driver's code")

    override fun help(context: Context) = "Remove a driver who drove no session."

    override fun run() = runBlocking {
        val driver = tools.drivers.list().firstOrNull { it.code == code.uppercase() } ?: throw CliktError("No driver with the code $code.")
        if (tools.sessions.list().any { it.driver == driver.id }) throw CliktError("${driver.name} drove sessions, so stays.")
        val typed = io.readLine("Type the code again to remove ${driver.name}: ")
        if (typed?.trim()?.uppercase() != driver.code) throw CliktError("Not removed.")
        tools.drivers.delete(driver.id)
        tools.access.remove(Thing(Kind.DRIVER, driver.id))
        echo("Removed ${driver.name} (${driver.code}).")
    }
}

/**
 * An event from a JSON file (M14.6), as its next revision: `id`, `name`,
 * `date`, `course`, `layout`, `cars`, and `parts`, each a `kind` (`practice` or
 * `race`), `name`, and `start` and `end` as ISO times with their offset
 * (`2026-10-04T09:00:00-04:00`). Every rule the admin page applies.
 */
class ImportEvent : CliktCommand(name = "import-event") {
    private val tools: Tools by requireObject<Tools>()
    private val file by argument(help = "an event's JSON").path(mustExist = true, canBeDir = false, mustBeReadable = true)

    override fun help(context: Context) = "Save an event from a JSON file, as its next revision."

    override fun run() = runBlocking {
        val json = runCatching { Json.parseToJsonElement(Files.readString(file)).jsonObject }
            .getOrElse { throw CliktError("${file.fileName} is not a JSON object: ${it.message}") }
        fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val id = str(json, "id")
        val current = if (EventRules.idProblem(id) == null) tools.events.get(id) else null
        var draft = Event(id, str(json, "name").trim(), str(json, "date"), str(json, "course"), str(json, "layout"),
            (json["cars"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, current?.parts.orEmpty())
        val problems = mutableListOf<String>()
        val parts = (json["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { p ->
            fun time(key: String) = runCatching { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(str(p, key))) }
                .getOrElse { problems += "${str(p, "name")}: $key is an ISO time with its offset, 2026-10-04T09:00:00-04:00"; Instant.EPOCH }
            val part = Part(
                str(p, "id").ifBlank { draft.nextPartId() },
                if (str(p, "kind") == "race") PartKind.RACE else PartKind.PRACTICE,
                str(p, "name").trim(), time("start"), time("end"),
            )
            draft = draft.copy(parts = draft.parts + part)
            // The admin page's hand-made changes stay as they are.
            current?.parts?.firstOrNull { it.id == part.id }?.let { part.copy(added = it.added, removed = it.removed) } ?: part
        }
        // The race's flags and stints, set on the website, stay as they are (M15.3).
        val event = draft.copy(parts = parts).keepingRaceEdits(current)
        problems += EventRules.eventProblems(event) + existenceProblems(event)
        if (problems.isNotEmpty()) throw CliktError("Not saved:\n" + problems.joinToString("\n") { "  - $it" })
        val saved = tools.events.save(event, current?.revision ?: 0, Instant.now())
            ?: throw CliktError("Someone saved ${event.id} while this ran; run it again.")
        echo("Saved ${saved.id} (${saved.name}) as revision ${saved.revision}: ${saved.parts.joinToString(", ") { "${it.id} ${it.name}" }}.")
    }

    /** The same checks as the admin page's: the course and its layout, and every car, exist. */
    private suspend fun existenceProblems(event: Event): List<String> = buildList {
        val course = tools.courses.get(event.course)
        if (course == null) add("there's no course ${event.course}")
        val shape = course?.let { (CourseRules.check(it.geojson) as? CourseCheck.Ok)?.shape }
        if (shape != null && shape.layouts.none { it.id == event.layout }) add("${course.name} has no layout ${event.layout}")
        for (car in event.cars) {
            val slug = (Slug.check(car) as? SlugCheck.Ok)?.slug
            if (slug == null || tools.registry.get(slug) == null) add("there's no car $car")
        }
    }
}

/** Removes an event (M14.6); its sessions are untouched. */
class RemoveEvent(private val io: AdminIo) : CliktCommand(name = "remove-event") {
    private val tools: Tools by requireObject<Tools>()
    private val id by argument()

    override fun help(context: Context) = "Remove an event. Its sessions stay."

    override fun run() = runBlocking {
        val event = tools.events.get(id) ?: throw CliktError("No event $id.")
        val typed = io.readLine("Type the event id again to remove it (${event.name}): ")
        if (typed?.trim() != event.id) throw CliktError("Not removed.")
        tools.events.delete(event.id)
        tools.access.remove(Thing(Kind.EVENT, event.id))
        echo("Removed ${event.id}.")
    }
}


/** Who made a thing, as `list` and `users` say it: a thing with no record is a master admin's (M23). */
internal fun creatorOf(access: Access?): String = access?.creator?.takeIf { it.isNotBlank() } ?: "(master)"

/** The invited users (M23), each with who invited them, when, and what they created. */
class ListUsers : CliktCommand(name = "users") {
    private val tools: Tools by requireObject<Tools>()
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    override fun help(context: Context) = "List invited users: who invited them, when, and what they created."

    override fun run() = runBlocking {
        val users = tools.users.list()
        if (users.isEmpty()) return@runBlocking echo("No users. Master admins are the admin-emails secret.")
        val access = tools.access.all()
        for (u in users) {
            val made = access.filterValues { it.creator == u.email }.keys.map { it.key }.sorted()
            val editing = access.filterValues { u.email in it.editors }.keys.map { it.key }.sorted()
            echo("${u.email}  invited by ${u.invitedBy.ifBlank { "?" }} on ${day.format(u.invited)}")
            echo("  created: ${made.joinToString(", ").ifEmpty { "nothing" }}")
            if (editing.isNotEmpty()) echo("  edits: ${editing.joinToString(", ")}")
        }
    }
}

/** Invites a user (M23), as a master admin does on the site's Users page. */
class AddUser : CliktCommand(name = "add-user") {
    private val tools: Tools by requireObject<Tools>()
    private val email by argument(help = "their Google account's email")

    override fun help(context: Context) = "Invite a user. They sign in with Google and can create cars, events, courses and drivers."

    override fun run() = runBlocking {
        val address = email.normalEmail()
        if (!EMAIL.matches(address)) throw CliktError("$email isn't an email address.")
        if (!tools.users.add(User(address, "admin.sh", Instant.now()))) throw CliktError("$address is already a user.")
        echo("Invited $address. They sign in with Google, top right of any page.")
    }
}

/** Removes a user (M23): their sign-in ends at once; what they made stays, theirs. */
class RemoveUser(private val io: AdminIo) : CliktCommand(name = "remove-user") {
    private val tools: Tools by requireObject<Tools>()
    private val email by argument()

    override fun help(context: Context) = "Remove a user. Their sign-in ends at once; what they created stays."

    override fun run() = runBlocking {
        val address = email.normalEmail()
        tools.users.get(address) ?: throw CliktError("No user $address.")
        val typed = io.readLine("Type the email again to remove $address: ")
        if (typed?.normalEmail() != address) throw CliktError("Not removed.")
        tools.users.remove(address)
        echo("Removed $address. What they created stays, and a master admin can still delete it.")
    }
}

/** A thing to share, read from its kind and id, checked to exist. */
private suspend fun Tools.thingOf(kind: String, id: String): Thing {
    val k = Kind.of(kind.lowercase()) ?: throw CliktError("A kind is car, event, course or driver, not $kind.")
    val exists = when (k) {
        Kind.CAR -> runCatching { registry.get(Slug.parse(id)) }.getOrNull() != null
        Kind.EVENT -> events.get(id) != null
        Kind.COURSE -> courses.get(id) != null
        Kind.DRIVER -> drivers.get(id) != null
    }
    if (!exists) throw CliktError("No ${k.key} $id.")
    return Thing(k, id)
}

/** Adds an editor to a thing (M23), as its creator does on the site. Only invited users. */
class Share : CliktCommand(name = "share") {
    private val tools: Tools by requireObject<Tools>()
    private val kind by argument(help = "car, event, course or driver")
    private val id by argument()
    private val email by argument()

    override fun help(context: Context) = "Let an invited user edit a car, event, course or driver."

    override fun run() = runBlocking {
        val thing = tools.thingOf(kind, id)
        val address = email.normalEmail()
        tools.users.get(address) ?: throw CliktError("$address isn't a user. Invite them first (add-user).")
        val current = tools.access.get(thing) ?: Access("")
        if (current.creator == address) throw CliktError("$address made $thing, so already edits it.")
        tools.access.set(thing, current.withEditor(address))
        echo("$address can now edit $thing.")
    }
}

/** Takes an editor off a thing (M23). */
class Unshare : CliktCommand(name = "unshare") {
    private val tools: Tools by requireObject<Tools>()
    private val kind by argument(help = "car, event, course or driver")
    private val id by argument()
    private val email by argument()

    override fun help(context: Context) = "Stop a user editing a car, event, course or driver."

    override fun run() = runBlocking {
        val thing = tools.thingOf(kind, id)
        val address = email.normalEmail()
        val current = tools.access.get(thing)
        if (current == null || address !in current.editors) throw CliktError("$address doesn't edit $thing.")
        tools.access.set(thing, current.withoutEditor(address))
        echo("$address no longer edits $thing.")
    }
}

/** Enough of an email to catch a slip: something@something.something. */
private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
