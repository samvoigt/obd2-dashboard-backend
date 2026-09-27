package com.obd2dashboard.backend.archive.gcp

import java.io.File
import java.time.Instant
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Courses against the **real** Firestore, then deleted (M12.3): the mapping,
 * the transaction and the versions. Run by hand through `scripts/course-smoke.sh`.
 */
fun main(args: Array<String>) {
    val project = args.firstOrNull().orEmpty()
    if (project.isBlank()) { System.err.println("usage: courseSmoke <gcp project>"); exitProcess(2) }
    val store = FirestoreCourseStore.connect(project)
    val id = "smoke-course-" + (1..6).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
    val nhms = Json.parseToJsonElement(File("../courses/seed/nhms.geojson").readText()).jsonObject
    var failures = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "  ok    " else "  FAIL  ") + what); if (!ok) failures++ }

    runBlocking {
        println("Course smoke test: project $project, course $id")
        try {
            val now = Instant.now()
            val first = store.save(id, 0, "Smoke", nhms, now)
            check("saved as version 1", first?.version == 1)
            check("read back, the GeoJSON exactly", store.get(id) == first)
            check("a save over the wrong version is refused", store.save(id, 0, "Smoke again", nhms, now) == null)
            val second = store.save(id, 1, "Smoke, renamed", nhms, now)
            check("version 2", second?.version == 2 && store.get(id)?.name == "Smoke, renamed")
            check("version 1 kept", store.get(id, 1)?.name == "Smoke")
            check("both versions listed", store.versions(id).map { it.version } == listOf(1, 2))
            check("current lists it at version 2", store.current().any { it.id == id && it.version == 2 })
        } catch (e: Exception) {
            println("  FAIL  ${e.javaClass.simpleName}: ${e.message}")
            failures++
        } finally {
            check("deleted, versions and all", store.delete(id) && store.get(id) == null && store.versions(id).isEmpty())
        }
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}
