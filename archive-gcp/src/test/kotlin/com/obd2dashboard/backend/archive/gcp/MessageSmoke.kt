package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.Messages
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Crew messages against the **real** Firestore, then deleted: the mapping,
 * the transactions and the two queries (which may ask for composite indexes).
 * Run by hand through `scripts/message-smoke.sh`.
 */
fun main(args: Array<String>) {
    val project = args.firstOrNull().orEmpty()
    if (project.isBlank()) { System.err.println("usage: smoke <gcp project>"); exitProcess(2) }
    val store = FirestoreMessageStore.connect(project)
    val messages = Messages(store)
    val car = "smoke-messages-" + (1..6).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
    val ids = mutableListOf<String>()
    var failures = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "  ok    " else "  FAIL  ") + what); if (!ok) failures++ }

    runBlocking {
        println("Message smoke test: project $project, car $car")
        try {
            val first = messages.send(car, "PIT NOW", "pit").message.also { ids += it.id }
            check("sent, and read back", store.get(first.id)?.text == "PIT NOW")
            check("active query (car + state)", messages.active(car).map { it.id } == listOf(first.id))
            check("received", messages.received(car, first.id)?.state == MessageState.Received)
            check("displayed", messages.displayed(car, first.id)?.state == MessageState.Displayed)
            check("a repeat changes nothing", messages.received(car, first.id) == null)
            val second = messages.send(car, "BOX THIS LAP", "box").also { ids += it.message.id }
            check("a newer one replaces it", second.replaced?.id == first.id && store.get(first.id)?.state == MessageState.Replaced)
            check("cleared", messages.clear(car, second.message.id)?.state == MessageState.Cleared)
            val third = messages.send(car, "PUSH", "push", Duration.ofMinutes(1)).message.also { ids += it.id }
            check("recent query (car + sentAt desc)", messages.recent(car, 20).map { it.id } == listOf(third.id, second.message.id, first.id))
            check("sync frame carries the active one", messages.syncFrame(car).contains(third.id))
        } catch (e: Exception) {
            println("  FAIL  ${e.javaClass.simpleName}: ${e.message}")
            failures++
        } finally {
            val db = com.google.cloud.firestore.FirestoreOptions.newBuilder().setProjectId(project).setDatabaseId("(default)").build().service
            ids.forEach { db.collection(FirestoreMessageStore.COLLECTION).document(it).delete().get() }
            check("deleted", ids.all { store.get(it) == null })
        }
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}
