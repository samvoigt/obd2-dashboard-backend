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
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.SessionIndex
import com.obd2dashboard.backend.archive.gcp.FirestoreMessageStore
import com.obd2dashboard.backend.archive.gcp.FirestoreSessionIndex
import com.obd2dashboard.backend.archive.gcp.GcsSegmentStore
import com.obd2dashboard.backend.live.MessageStore
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.IssuedToken
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore
import java.nio.file.Files
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
            ListSessions(), ShowSession(), DeleteSession(io),
        )
    }

    override fun help(context: Context) = "Manage registered cars (tokens, passcodes, names) and their sessions."

    override fun run() {
        currentContext.findOrSetObject { toolsFor(project, bucket) }
    }
}

/** What subcommands share, set by [Admin.run]. */
class Tools(val registry: CarRegistry, val sessions: SessionIndex, val archive: ArchiveService, val messages: MessageStore)

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
        // Read and check the chosen token first, so a typo leaves no car behind.
        val token = readChosenToken(io, tokenFile, "Token for ${slugOf(slug)}: ")
        val car = registry.addCar(slugOf(slug), name).car.slug // its generated token is replaced unseen
        try {
            registry.setToken(car, token)
        } catch (e: RegistryException) {
            registry.removeCar(car)
            throw e
        }
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
        val rows = listOf(listOf("SLUG", "NAME", "TOKEN", "ISSUED", "PASSCODE")) + cars.map {
            listOf(
                it.slug.value,
                it.name,
                "…${it.tokenHint}",
                issued.format(it.tokenIssued),
                if (it.passcodeHash != null) "set" else "not set",
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
        registry.get(car) ?: throw RegistryException.NoSuchCar(car)
        // Sessions are kept until the owner deletes them (decision 16); a car's
        // removal must not orphan them silently.
        val sessions = tools.sessions.listByCar(car.value).size
        if (sessions > 0) {
            throw CliktError("$car has $sessions session(s). Delete them first (admin.sh sessions $car, then delete-session).")
        }
        val typed = io.readLine("Type the slug again to remove $car: ")
        if (typed?.trim() != car.value) throw CliktError("Not removed.")
        registry.removeCar(car)
        // Its messages were a record for its sessions, which are gone (M5.8).
        val messages = tools.messages.deleteCar(car.value)
        echo("Removed $car, and its $messages message(s).")
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
        val rows = listOf(listOf("ID", "CAR", "STARTED", "LINES", "STATE")) + sessions.map {
            listOf(
                it.id,
                it.car,
                it.header?.started ?: "(no record yet)",
                (it.ackedThrough + 1).toString(),
                if (it.complete) "complete" else "uploading",
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
        val session = tools.sessions.get(id.lowercase()) ?: throw CliktError("No session $id.")
        val typed = io.readLine("Type the session id again to delete it (car ${session.car}): ")
        if (typed?.trim()?.lowercase() != session.id) throw CliktError("Not deleted.")
        tools.archive.delete(session.id)
        echo("Deleted ${session.id}.")
    }
}
