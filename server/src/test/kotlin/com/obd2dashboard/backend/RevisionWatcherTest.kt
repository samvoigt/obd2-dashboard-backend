package com.obd2dashboard.backend

import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RevisionWatcherTest {
    private fun service(json: String) = Json.parseToJsonElement(json).jsonObject
    private val base = "projects/obd2-dashboard-backend/locations/us-east4/services/obd2-backend/revisions"

    @Test
    fun `a revision named with traffic is serving, the one before it is not`() {
        val s = service("""{"latestReadyRevision":"$base/obd2-backend-00006-8r2","trafficStatuses":[{"type":"TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION","revision":"obd2-backend-00006-8r2","percent":100}]}""")
        RevisionWatcher.servingFrom(s, "obd2-backend-00006-8r2") shouldBe true
        RevisionWatcher.servingFrom(s, "obd2-backend-00005-7nl") shouldBe false
    }

    @Test
    fun `a 'latest' allocation means the latest ready revision`() {
        val s = service("""{"latestReadyRevision":"$base/obd2-backend-00006-8r2","trafficStatuses":[{"type":"TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST","percent":100}]}""")
        RevisionWatcher.servingFrom(s, "obd2-backend-00006-8r2") shouldBe true
        RevisionWatcher.servingFrom(s, "obd2-backend-00005-7nl") shouldBe false
    }

    @Test
    fun `a revision kept at zero percent is not serving, a split one is`() {
        val s = service("""{"trafficStatuses":[{"revision":"a","percent":90},{"revision":"b","percent":10},{"revision":"c","percent":0}]}""")
        RevisionWatcher.servingFrom(s, "a") shouldBe true
        RevisionWatcher.servingFrom(s, "b") shouldBe true
        RevisionWatcher.servingFrom(s, "c") shouldBe false
    }

    @Test
    fun `an answer without traffic statuses cannot be told`() {
        RevisionWatcher.servingFrom(service("""{"name":"x"}"""), "a") shouldBe null
    }

    private fun watcherTest(answers: List<() -> Boolean?>) = runTest {
        var drains = 0
        var i = 0
        val watcher = RevisionWatcher({ answers[minOf(i++, answers.lastIndex)]() }, { drains++ }, period = 30.seconds)
        val job = watcher.start(backgroundScope)
        repeat(answers.size) { advanceTimeBy(30.seconds); runCurrent() }
        job.cancel()
        results += drains
    }

    private val results = mutableListOf<Int>()

    @Test
    fun `it drains only when told the revision has no traffic, and again for stragglers`() {
        watcherTest(listOf({ true }, { true }))
        watcherTest(listOf({ null }, { null }))
        watcherTest(listOf({ error("API down") }, { true }))
        watcherTest(listOf({ true }, { false }, { false }))
        results shouldBe listOf(0, 0, 0, 2)
    }
}
