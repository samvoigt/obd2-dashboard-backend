package com.obd2dashboard.backend.events

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val t0: Instant = Instant.parse("2026-10-04T13:00:00Z")
private fun at(minutes: Long): Instant = t0.plusSeconds(minutes * 60)

private fun part(id: String, from: Long, to: Long, kind: PartKind = PartKind.PRACTICE, added: List<String> = emptyList(), removed: List<String> = emptyList()) =
    Part(id, kind, "Part $id", at(from), at(to), added, removed)

private fun event(vararg parts: Part, cars: List<String> = listOf("outback")) =
    Event("nhms-october", "NHMS October", "2026-10-04", "nhms", "road", cars, parts.toList())

private fun heard(id: String, from: Long, to: Long, car: String = "outback", source: String? = null) = SessionHeard(id, car, at(from), at(to), source)

class EventRulesTest {
    @Test
    fun `a valid event has no problems`() {
        EventRules.eventProblems(event(part("p1", 0, 60), part("p2", 60, 120), part("p3", 180, 900, PartKind.RACE))) shouldBe emptyList()
    }

    @Test
    fun `every problem with an event is said`() {
        val bad = Event(
            "Bad Id", " ", "4 Oct", "", "", listOf("a", "a"),
            listOf(part("p1", 0, 60, PartKind.RACE), part("p2", 30, 90, PartKind.RACE), part("p3", 100, 100), part("p4", 200, 3000, added = listOf("s"), removed = listOf("s"))),
        )
        val problems = EventRules.eventProblems(bad)
        problems shouldContain "an id is 2–32 lower-case letters, digits and hyphens, starting with a letter"
        problems shouldContain "a name cannot be blank"
        problems shouldContain "a date is written 2026-10-04"
        problems shouldContain "an event is at a course, on one of its layouts"
        problems shouldContain "a car is entered once"
        problems shouldContain "an event has at most one race"
        problems shouldContain "Part p1 and Part p2 overlap"
        problems shouldContain "Part p3: it ends after it starts"
        problems shouldContain "Part p4: a part is at most 30 hours"
        problems shouldContain "Part p4: a session is added or removed, not both"
        EventRules.eventProblems(event().copy(cars = emptyList())) shouldContain "an event has at least one car"
        EventRules.eventProblems(event(part("p1", 0, 10, added = listOf("s")), part("p2", 20, 30, added = listOf("s")))) shouldContain
            "the session s is added to Part p1 and Part p2; a session is in one part"
        EventRules.eventProblems(event(part("p1", 0, 10), part("p1", 20, 30))) shouldContain "each part has its own id"
        // Back to back is not an overlap: windows are [start, end).
        EventRules.eventProblems(event(part("p1", 0, 10), part("p2", 10, 20))) shouldBe emptyList()
    }

    @Test
    fun `a driver has a name and a code of its own`() {
        val sam = Driver("d1", "Sam", "SAM")
        EventRules.driverProblems(sam, listOf(sam)) shouldBe emptyList()
        EventRules.driverProblems(Driver("d2", "", "sa"), listOf(sam)) shouldBe
            listOf("a name cannot be blank", "a driver's code is 2–4 capital letters")
        EventRules.driverProblems(Driver("d2", "Samantha", "SAM"), listOf(sam)) shouldBe listOf("Sam already has the code SAM")
        EventRules.codeProblem("SAMVO") shouldBe "a driver's code is 2–4 capital letters"
    }

    @Test
    fun `a part holds the sessions heard in its window, from cars entered, never fake data`() {
        val e = event(part("p1", 0, 60), part("p2", 60, 120), cars = listOf("outback"))
        EventRules.sessionsIn(e, listOf(
            heard("inside", 10, 20),
            heard("edge", 50, 70), // mostly p1
            heard("late-edge", 55, 100), // mostly p2
            heard("tablet", 30, 31, source = "tablet"),
            heard("before", -30, -1),
            heard("at-end", 120, 130), // p2 ends at 120: out
            heard("touching-start", -10, 0), // heard until p1's first instant: in
            heard("fake", 10, 20, source = "fake"),
            heard("other-car", 10, 20, car = "yaris"),
        )) shouldBe mapOf("p1" to listOf("touching-start", "inside", "tablet", "edge"), "p2" to listOf("late-edge"))
    }

    @Test
    fun `by hand wins, added though heard late, removed though inside, and a tie goes to the earlier part`() {
        val e = event(part("p1", 0, 60, removed = listOf("inside")), part("p2", 60, 120, added = listOf("late", "fake")))
        EventRules.sessionsIn(e, listOf(
            heard("inside", 10, 20),
            heard("late", 400, 410), // a tablet session uploaded after the window
            heard("fake", 70, 80, source = "fake"), // never, even by hand
            heard("even", 50, 70),
        )) shouldBe mapOf("p1" to listOf("even"), "p2" to listOf("late"))
    }

    @Test
    fun `only the race has flags and stints, the flag after the green, stints for cars entered, starts apart`() {
        val race = part("p2", 60, 480, PartKind.RACE)
        EventRules.eventProblems(event(part("p1", 0, 60), race.copy(green = at(61), flag = at(470), stints = mapOf("outback" to listOf(Stint(1, "d1"), Stint(2, null)))))) shouldBe emptyList()
        EventRules.eventProblems(event(part("p1", 0, 60, added = emptyList()).copy(green = at(1)))) shouldContain "Part p1: only the race has flags and stints"
        EventRules.eventProblems(event(race.copy(green = at(100), flag = at(100)))) shouldContain "Part p2: the flag falls after the green flag"
        EventRules.eventProblems(event(race.copy(stints = mapOf("yaris" to listOf(Stint(1, null)))))) shouldContain "Part p2: stints for yaris, which isn't entered"
        EventRules.eventProblems(event(race.copy(stints = mapOf("outback" to listOf(Stint(5, "a"), Stint(5, "b")))))) shouldContain "Part p2: two of outback's stints start at once"
    }

    @Test
    fun `a save keeps the race's flags and stints, which only their own routes set`() {
        val stored = event(part("p1", 0, 60), part("p2", 60, 480, PartKind.RACE).copy(green = at(61), stints = mapOf("outback" to listOf(Stint(1, "d1")))))
        val drawn = event(part("p1", 0, 50), part("p2", 60, 500, PartKind.RACE), part("p3", 500, 510))
        val kept = drawn.keepingRaceEdits(stored)
        kept.parts[1].green shouldBe at(61)
        kept.parts[1].stints shouldBe mapOf("outback" to listOf(Stint(1, "d1")))
        kept.parts[1].end shouldBe at(500) // what was drawn stays drawn
        kept.parts[2] shouldBe drawn.parts[2]
        drawn.keepingRaceEdits(null) shouldBe drawn
    }

    @Test
    fun `part ids go on from the highest ever used`() {
        event().nextPartId() shouldBe "p1"
        event(part("p1", 0, 1), part("p3", 2, 3)).nextPartId() shouldBe "p4"
        event(part("p2", 0, 1, PartKind.RACE)).race!!.id shouldBe "p2"
    }

    @Test
    fun `the in-memory stores keep codes unique and saves conditional`() = runTest {
        val drivers = InMemoryDriverStore()
        drivers.put(Driver("d1", "Sam", "SAM")) shouldBe Driver("d1", "Sam", "SAM")
        drivers.put(Driver("d2", "Samantha", "SAM")) shouldBe null
        drivers.put(Driver("d1", "Sam V", "SAM"))!!.name shouldBe "Sam V" // its own code again is fine
        val events = InMemoryEventStore()
        events.save(event(), 0, t0)!!.revision shouldBe 1
        events.save(event(), 0, t0) shouldBe null // the id is taken
        events.save(event().copy(name = "Renamed"), 1, t0)!!.revision shouldBe 2
        events.save(event(), 1, t0) shouldBe null // stale
        events.get("nhms-october")!!.name shouldBe "Renamed"
    }
}
