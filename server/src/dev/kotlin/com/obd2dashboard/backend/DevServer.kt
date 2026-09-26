package com.obd2dashboard.backend

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
    File(System.getProperty("devTokenFile", "build/dev-token")).apply {
        parentFile.mkdirs()
        writeText(token)
        setReadable(false, false)
        setReadable(true, true)
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    println("Dev server on http://localhost:$port (car dev-car; token in build/dev-token)")
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        module(registry, ArchiveService(InMemorySessionIndex(), InMemorySegmentStore()), InMemoryLiveHub(), messages = Messages(InMemoryMessageStore()), crewKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    }.start(wait = true)
}
