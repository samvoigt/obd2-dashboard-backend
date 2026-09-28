package com.obd2dashboard.backend.archive.gcp

import com.obd2dashboard.backend.events.Driver
import com.obd2dashboard.backend.events.Event
import com.obd2dashboard.backend.events.Part
import com.obd2dashboard.backend.events.PartKind
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Drivers and events against the **real** Firestore, then deleted (M14.2): the
 * mappings and the transactions. Run by hand through `scripts/event-smoke.sh`.
 */
fun main(args: Array<String>) {
    val project = args.firstOrNull().orEmpty()
    if (project.isBlank()) { System.err.println("usage: eventSmoke <gcp project>"); exitProcess(2) }
    val drivers = FirestoreDriverStore.connect(project)
    val events = FirestoreEventStore.connect(project)
    val tag = (1..6).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
    val one = Driver("smoke-driver-$tag-1", "Smoke One", "Q" + tag.take(3).uppercase())
    val two = Driver("smoke-driver-$tag-2", "Smoke Two", "Z" + tag.take(3).uppercase())
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val event = Event(
        "smoke-event-$tag", "Smoke event", "2026-10-04", "nhms", "road", listOf("smoke-car"),
        listOf(Part("p1", PartKind.PRACTICE, "Practice 1", now, now.plusSeconds(3600), added = listOf("a"), removed = listOf("b"))),
    )
    var failures = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "  ok    " else "  FAIL  ") + what); if (!ok) failures++ }

    runBlocking {
        println("Event smoke test: project $project, tag $tag")
        try {
            check("a driver stored", drivers.put(one) == one && drivers.get(one.id) == one)
            check("another driver with the same code refused", drivers.put(two.copy(code = one.code)) == null && drivers.get(two.id) == null)
            check("another driver with its own code stored", drivers.put(two) == two)
            check("a driver keeps its own code on a rename", drivers.put(one.copy(name = "Smoke Uno")) != null)
            check("listed", drivers.list().map { it.id }.containsAll(listOf(one.id, two.id)))
            val first = events.save(event, 0, now)
            check("an event saved as revision 1", first?.revision == 1)
            check("read back exactly", events.get(event.id) == first)
            check("a new event on a taken id refused", events.save(event, 0, now) == null)
            check("a stale save refused", events.save(event.copy(name = "Stale"), 0, now) == null)
            check("revision 2", events.save(event.copy(name = "Renamed"), 1, now)?.revision == 2 && events.get(event.id)?.name == "Renamed")
            check("listed", events.list().any { it.id == event.id })
            check("ids Firestore can't hold are simply not found", listOf("", ".", "..", "a/b").all { drivers.get(it) == null && events.get(it) == null && !drivers.delete(it) })
        } catch (e: Exception) {
            println("  FAIL  ${e.javaClass.simpleName}: ${e.message}")
            failures++
        } finally {
            check("deleted", events.delete(event.id) && drivers.delete(one.id) && drivers.delete(two.id))
            check("gone", events.get(event.id) == null && drivers.get(one.id) == null && drivers.get(two.id) == null)
        }
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}
