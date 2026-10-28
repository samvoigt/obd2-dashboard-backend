package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.archive.MergedSession
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Merges against the **real** Firestore, then deleted (M33.2): the mapping and
 * the transaction that keeps a session in one merge. No session is touched: a
 * merge only names ids. Run by hand through `scripts/merge-smoke.sh`.
 */
fun main(args: Array<String>) {
    val project = args.firstOrNull().orEmpty()
    if (project.isBlank()) { System.err.println("usage: mergeSmoke <gcp project>"); exitProcess(2) }
    val merges = FirestoreMergeStore.connect(project)
    val tag = (1..6).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
    val car = "smoke-car-$tag"
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val one = MergedSession(UUID.randomUUID().toString(), car, listOf("$tag-a", "$tag-b"), created = now, updated = now)
    val two = MergedSession(UUID.randomUUID().toString(), car, listOf("$tag-b", "$tag-c"), created = now, updated = now)
    var failures = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "  ok    " else "  FAIL  ") + what); if (!ok) failures++ }

    runBlocking {
        println("Merge smoke test: project $project, tag $tag")
        try {
            val first = merges.save(one, 0, now)
            check("a merge saved as revision 1", first?.revision == 1)
            check("read back exactly", merges.get(one.id) == first)
            check("a second merge of one of its sessions refused", merges.save(two, 0, now) == null && merges.get(two.id) == null)
            val extended = merges.save(first!!.copy(members = one.members + "$tag-c", name = "Smoke"), 1, now)
            check("extended, keeping its id", extended?.revision == 2 && merges.get(one.id)?.members?.size == 3)
            check("a stale save refused", merges.save(first, 1, now) == null)
            check("split", merges.save(extended!!.copy(split = true), 2, now)?.split == true)
            check("its sessions free once split", merges.save(two, 0, now)?.revision == 1)
            check("the car's merges listed", merges.ofCar(car).map { it.id }.toSet() == setOf(one.id, two.id))
            check("ids Firestore can't hold are simply not found", listOf("", ".", "..", "a/b").all { merges.get(it) == null && !merges.delete(it) })
        } catch (e: Exception) {
            println("  FAIL  ${e.javaClass.simpleName}: ${e.message}")
            failures++
        } finally {
            merges.delete(one.id); merges.delete(two.id)
            check("gone", merges.get(one.id) == null && merges.get(two.id) == null && merges.ofCar(car).isEmpty())
        }
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}
