package com.obd2dashboard.backend.archive

import com.obd2dashboard.backend.archive.MergeRules.Candidate
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Merged sessions (M33.2): the rules, each alone, and the store's one-merge-per-session. */
class MergesTest {
    private val h = 3_600_000L
    /** NHMS Saturday's shape: back to back, 0.2 s and 12 s apart. */
    private val a = Candidate("a", "car", true, 0, 2 * h)
    private val b = Candidate("b", "car", true, 2 * h + 12_000, 2 * h + 312_000)
    private val c = Candidate("c", "car", true, 2 * h + 312_200, 5 * h)

    private fun messages(vararg s: Candidate, into: String? = null) = MergeRules.check(s.toList(), into).map { it.message }

    @Test
    fun `back-to-back complete sessions of one car in one part merge`() {
        messages(a, b, c) shouldBe emptyList()
        messages(a.copy(part = "Race session 1"), b.copy(part = "Race session 1")) shouldBe emptyList()
        // Out of order in is fine: time order is the rule's, not the request's.
        messages(c, a, b) shouldBe emptyList()
    }

    @Test
    fun `one session, or the same one twice, isn't a merge`() {
        messages(a) shouldBe listOf("Pick at least two sessions to merge.")
        messages(a, a) shouldBe listOf("Pick at least two sessions to merge.")
    }

    @Test
    fun `another car's session is refused`() {
        messages(a, b.copy(car = "other")) shouldBe listOf("These are different cars' sessions.")
    }

    @Test
    fun `a live or uploading session is refused, naming it`() {
        MergeRules.check(listOf(a, b.copy(complete = false))) shouldBe listOf(MergeRules.Problem("One of them is still live or uploading.", listOf("b")))
        messages(a, b.copy(complete = false), c.copy(complete = false)) shouldBe listOf("2 of them are still live or uploading.")
    }

    @Test
    fun `different parts, or a part and none, are refused`() {
        messages(a.copy(part = "Practice"), b.copy(part = "Race session 1")) shouldBe listOf("These are in different parts of the event: Practice and Race session 1.")
        messages(a.copy(part = "Practice"), b) shouldBe listOf("These are in different parts of the event: Practice and no part.")
    }

    @Test
    fun `an overlap over 5 s is refused, 5 s or less isn't`() {
        messages(a, b.copy(started = 2 * h - 5_000)) shouldBe emptyList()
        MergeRules.check(listOf(a, b.copy(started = 2 * h - 180_000))) shouldBe listOf(MergeRules.Problem("Two of them overlap by 3 min: they can't be one drive.", listOf("a", "b")))
    }

    @Test
    fun `a session in another merge is refused, but extending a merge brings its own`() {
        messages(a.copy(mergedIn = "m1"), b) shouldBe listOf("One of them is in a merged session already.")
        messages(a.copy(mergedIn = "m1"), b.copy(mergedIn = "m1"), c, into = "m1") shouldBe emptyList()
        messages(a.copy(mergedIn = "m1"), b.copy(mergedIn = "m2"), into = "m1") shouldBe listOf("Two merged sessions can't be merged: unmerge one first.")
    }

    private val t = Instant.parse("2026-10-17T13:23:25Z")
    private fun merge(id: String, vararg members: String) = MergedSession(id, "car", members.toList(), created = t, updated = t)

    @Test
    fun `the store keeps a session in one merge, and extending keeps the id`(): Unit = runBlocking {
        val store = InMemoryMergeStore()
        val first = store.save(merge("m1", "a", "b"), 0, t)!!
        first.revision shouldBe 1
        store.save(merge("m2", "b", "c"), 0, t) shouldBe null
        store.save(merge("m1", "x"), 0, t) shouldBe null // taken id
        val extended = store.save(first.copy(members = listOf("a", "b", "c")), 1, t.plusSeconds(60))!!
        extended.id shouldBe "m1"
        extended.revision shouldBe 2
        store.save(first.copy(name = "stale"), 1, t) shouldBe null
        store.holding("car", "c") shouldBe extended
        // Another car's merge of the same ids is its own business.
        store.save(merge("m3", "a", "b").copy(car = "other"), 0, t)!!.car shouldBe "other"
    }

    @Test
    fun `a split merge frees its members, and stays to be found`(): Unit = runBlocking {
        val store = InMemoryMergeStore()
        val m = store.save(merge("m1", "a", "b"), 0, t)!!
        val split = store.save(m.copy(split = true), 1, t)!!
        split.split shouldBe true
        store.holding("car", "a") shouldBe null
        store.save(merge("m2", "a", "b"), 0, t)!!.members shouldBe listOf("a", "b")
        store.get("m1")!!.split shouldBe true
        store.ofCar("car").map { it.id } shouldBe listOf("m1", "m2")
        store.delete("m1") shouldBe true
        store.get("m1") shouldBe null
    }
}
