package com.obd2dashboard.backend.tools

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.IssuedToken
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.firestore.FirestoreCarStore
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * The owner's tool for cars: run as the owner, against Firestore directly, so
 * the server has no admin endpoint (M2 plan). Run it through `scripts/admin.sh`,
 * which supplies the project.
 */
fun main(args: Array<String>) {
    Admin(registryFor = { project -> CarRegistry(FirestoreCarStore.connect(project)) }, io = ConsoleIo)
        .main(args)
    // The Firestore client's channel would otherwise keep the JVM alive.
    exitProcess(0)
}

/** The root command. Subcommands find the registry in the context it sets. */
class Admin(
    private val registryFor: (project: String) -> CarRegistry,
    private val io: AdminIo,
) : CliktCommand(name = "admin") {
    private val project by option("--project", help = "Google Cloud project; admin.sh sets it").required()

    init {
        subcommands(AddCar(), RotateToken(io), SetPasscode(io), Rename(), ListCars(), RemoveCar(io))
    }

    override fun help(context: Context) = "Manage registered cars: tokens, passcodes and names."

    override fun run() {
        currentContext.findOrSetObject { Tools(registryFor(project)) }
    }
}

/** What subcommands share, set by [Admin.run]. */
class Tools(val registry: CarRegistry)

/**
 * A subcommand that talks to the registry. A refusal from the registry is
 * shown as it is (its messages are written for a person) and exits 1.
 */
abstract class RegistryCommand(name: String, private val helpText: String) : CliktCommand(name = name) {
    private val tools by requireObject<Tools>()

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

class AddCar : RegistryCommand("add-car", "Register a car and print its token, once.") {
    private val slug by argument(help = "Permanent URL name: a-z, 0-9 and -, 2 to 32 characters")
    private val name by option("--name", help = "Display name; can be changed later").required()

    override suspend fun execute(registry: CarRegistry) {
        showToken(registry.addCar(slugOf(slug), name), "Added")
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

class SetPasscode(private val io: AdminIo) :
    RegistryCommand("set-passcode", "Set a car's crew passcode, for sending messages.") {
    private val slug by argument()

    override suspend fun execute(registry: CarRegistry) {
        val car = slugOf(slug)
        registry.get(car) ?: throw RegistryException.NoSuchCar(car)
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
        val typed = io.readLine("Type the slug again to remove $car: ")
        if (typed?.trim() != car.value) throw CliktError("Not removed.")
        registry.removeCar(car)
        echo("Removed $car.")
    }
}
