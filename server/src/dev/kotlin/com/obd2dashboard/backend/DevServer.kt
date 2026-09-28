package com.obd2dashboard.backend

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import java.time.Instant
import com.obd2dashboard.backend.courses.InMemoryCourseStore
import com.obd2dashboard.backend.events.InMemoryDriverStore
import com.obd2dashboard.backend.events.InMemoryEventStore
import com.obd2dashboard.backend.courses.CourseStore
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.runBlocking

/**
 * The real server module on in-memory stores, for working on the website
 * without touching the cloud: one car, `dev-car`, whose token is written to
 * `server/build/dev-token` (never printed). Replay into it with
 * `scripts/replay.sh --server http://localhost:8080 --token-file server/build/dev-token --live …`.
 */
fun main() {
    val registry = CarRegistry(InMemoryCarStore())
    val token = runBlocking { registry.addCar(Slug.parse("dev-car"), "Dev car") }.token
    // A generated crew passcode too (M5.7), for logging in to the page locally. Never printed.
    val passcode = (1..12).map { "abcdefghjkmnpqrstuvwxyz23456789".random(java.security.SecureRandom().asKotlinRandom()) }.joinToString("")
    runBlocking { registry.setPasscode(Slug.parse("dev-car"), passcode.toCharArray()) }
    fun private(name: String, text: String) = File(System.getProperty("devTokenFile", "build/dev-token")).resolveSibling(name).apply {
        parentFile.mkdirs()
        writeText(text)
        setReadable(false, false)
        setReadable(true, true)
    }
    private("dev-token", token)
    private("dev-passcode", passcode)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    println("Dev server on http://localhost:$port (car dev-car; token and crew passcode in build/dev-token, build/dev-passcode)")
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        module(
            registry, ArchiveService(InMemorySessionIndex(), InMemorySegmentStore()), InMemoryLiveHub(),
            messages = Messages(InMemoryMessageStore()), crewKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
            admin = devAdmin(),
            courses = devCourses(),
            events = EventStores(InMemoryDriverStore(), InMemoryEventStore()),
        )
    }.start(wait = true)
}

/**
 * The admin page without Google (M6.3): the page shows a dev sign-in, whose
 * credential `dev` signs in as `dev@localhost`. Only this dev source set builds
 * it; `main` has no way to.
 */
private fun devAdmin(): AdminConfig {
    val email = "dev@localhost"
    return AdminConfig(
        googleClientId = null,
        allowlist = Allowlist(listOf(email)),
        identity = IdentityVerifier { if (it == "dev") SignIn.Allowed(email) else SignIn.Refused(Refusal.Malformed) },
        dev = true,
    )
}

/** Courses in memory, with NHMS from its seed (M12.2) so the editor has one to open. */
private fun devCourses(): CourseStore = InMemoryCourseStore().also { store ->
    val seed = File("../courses/seed/nhms.geojson")
    if (seed.isFile) {
        runBlocking { store.save("nhms", 0, "New Hampshire Motor Speedway", Json.parseToJsonElement(seed.readText()).jsonObject, Instant.now()) }
    }
}

