package com.obd2dashboard.backend.registry.firestore

import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens
import java.security.SecureRandom
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Round-trips a throwaway car through the **real** Firestore, and removes it.
 *
 * Not a unit test: it needs credentials and a live database, so it runs by hand
 * through `scripts/firestore-smoke.sh`. It checks what the in-memory store
 * cannot: the document mapping, the preconditions, and the token-hash query.
 * Tokens are never printed.
 */
fun main(args: Array<String>) {
    val project = args.firstOrNull().orEmpty()
    if (project.isBlank()) {
        System.err.println("usage: smoke <gcp project>")
        exitProcess(2)
    }
    val store = FirestoreCarStore.connect(project)
    val registry = CarRegistry(store, passcodeIterations = 1_000)
    val slug = Slug.parse("smoke-" + (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""))
    var failures = 0
    fun check(what: String, ok: Boolean) {
        println((if (ok) "  ok    " else "  FAIL  ") + what)
        if (!ok) failures++
    }

    runBlocking {
        println("Firestore smoke test: project $project, car $slug")
        try {
            val first = registry.addCar(slug, "Smoke test")
            val stored = store.get(slug)
            check("created car reads back", stored?.name == "Smoke test")
            check(
                "timestamps survive at microsecond precision",
                stored?.created == first.car.created.truncatedTo(ChronoUnit.MICROS),
            )
            check("no passcode yet", stored?.passcodeHash == null)
            check("found by token", registry.authenticate(first.token)?.slug == slug)
            check("unknown token finds nothing", registry.authenticate(Tokens.generate(SecureRandom())) == null)
            check(
                "creating the same slug again is refused",
                runCatching { registry.addCar(slug, "Again") }.exceptionOrNull() is RegistryException.CarExists,
            )

            val second = registry.rotateToken(slug)
            check("rotated: old token fails", registry.authenticate(first.token) == null)
            check("rotated: new token works", registry.authenticate(second.token)?.slug == slug)

            registry.setPasscode(slug, "smoke-pass".toCharArray())
            check("passcode stored", store.get(slug)?.passcodeHash?.startsWith("pbkdf2-sha256$") == true)
            registry.rename(slug, "Smoke test, renamed")
            check("rename keeps the passcode", store.get(slug)?.passcodeHash != null)
            check("listed", registry.list().any { it.slug == slug })

            registry.removeCar(slug)
            check("removed: gone", store.get(slug) == null)
            check("removed: token fails", registry.authenticate(second.token) == null)
            check("update of a missing car is refused", !store.update(second.car))
            check("delete of a missing car is refused", !store.delete(slug))
        } finally {
            // Leave nothing behind, whatever failed above.
            runCatching { store.delete(slug) }
        }
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}
