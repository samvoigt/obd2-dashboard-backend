package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * A user and an access record against the **real** Firestore, then deleted
 * (M23). Run by hand through `scripts/user-smoke.sh`.
 */
fun main(args: Array<String>) {
    val project = args.firstOrNull().orEmpty()
    if (project.isBlank()) { System.err.println("usage: userSmoke <gcp project>"); exitProcess(2) }
    val users = FirestoreUserStore.connect(project)
    val access = FirestoreAccessStore.connect(project)
    val tag = (1..6).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
    val email = "smoke-$tag@example.com"
    val user = User(email, "smoke-master@example.com", Instant.now().truncatedTo(ChronoUnit.MILLIS))
    val thing = Thing(Kind.CAR, "smoke-car-$tag")
    var failures = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "  ok    " else "  FAIL  ") + what); if (!ok) failures++ }

    runBlocking {
        println("User smoke test: project $project, tag $tag")
        try {
            check("a user invited", users.add(user))
            check("invited once only", !users.add(user))
            check("read back exactly, whatever the email's case", users.get(email.uppercase()) == user)
            check("listed", users.list().any { it.email == email })
            access.set(thing, Access(email, setOf("Smoke-Editor-$tag@Example.com")))
            check("an access record read back, emails lower case", access.get(thing) == Access(email, setOf("smoke-editor-$tag@example.com")))
            check("in the full list", access.all()[thing]?.creator == email)
            check("ids Firestore can't hold are simply not found", listOf("", ".", "..", "a/b").all { users.get(it) == null })
        } catch (e: Exception) {
            println("  FAIL  ${e.javaClass.simpleName}: ${e.message}")
            failures++
        } finally {
            access.remove(thing)
            check("deleted", users.remove(email))
            check("gone", users.get(email) == null && access.get(thing) == null)
        }
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}
