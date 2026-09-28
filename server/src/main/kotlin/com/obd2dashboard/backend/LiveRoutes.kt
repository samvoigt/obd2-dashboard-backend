package com.obd2dashboard.backend

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.live.Attachment
import com.obd2dashboard.backend.live.CarLive
import com.obd2dashboard.backend.live.ErrorCode
import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.ServerFrames
import com.obd2dashboard.backend.live.TabletFrame
import com.obd2dashboard.backend.live.TabletFrames
import com.obd2dashboard.backend.live.TabletHandle
import com.obd2dashboard.backend.registry.CarRegistry
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The live lane's timings; tests shorten them. */
data class LiveConfig(
    /** Close cleanly with 1001 before Cloud Run's 60-minute cut (§5.3, §14.5). */
    val maxAge: Duration = 55.minutes,
    /** How often an open socket's token is checked again, so a rotated one is shut out. */
    val recheck: Duration = 30.seconds,
)

const val LIVE_PROTOCOL = "obd2-telemetry.v1"

/** 1008: the socket broke a rule (auth, superseded, version). Clean closes are 1001 and 1012. */
private const val POLICY: Short = 1008

fun Application.installLive(hub: LiveHub, project: String? = null) {
    install(WebSockets) {
        pingPeriod = 15.seconds // §5.1: the tablet treats 30 s of silence as a dead link, and so do we
        timeout = 30.seconds
        maxFrameSize = 256L * 1024 // over 64 KB is refused per frame (bad_message), not by closing
        masking = false
        extensions { install(WebSocketDeflateExtension) }
    }
    // On SIGTERM Cloud Run allows 10 s: tell every tablet it is a restart (1012), not a drop.
    monitor.subscribe(ApplicationStopping) {
        runBlocking { hub.closeAll(CloseReason.Codes.SERVICE_RESTART.code, "server restarting") }
    }
    // A deploy leaves open sockets on this revision; once it has no traffic, move everyone on (M4.8a).
    project?.let { p ->
        RevisionWatcher.onCloudRun(
            project = p,
            drain = { hub.closeAll(CloseReason.Codes.SERVICE_RESTART.code, "a new revision is serving") },
            log = { log.info(it) },
        )?.start(this)
    }
}

/** Contract §5.1–5.3: `wss://…/v1/live`. */
fun Route.liveRoutes(
    registry: CarRegistry,
    archive: ArchiveService,
    hub: LiveHub,
    crew: CrewMessages,
    config: LiveConfig,
    clock: Clock,
    downlink: CourseDownlink,
    /** The live run (M17.2): every frame `CarLive` takes. */
    timings: LiveTimings? = null,
    /** `timing` to the tablet (M17.4). */
    timing: TimingDownlink? = null,
) {
    webSocket("/v1/live", protocol = LIVE_PROTOCOL) {
        TabletSocket(this, registry, archive, hub, crew, config, clock, downlink, timings, timing).run()
    }
    // Offered no (or another) subprotocol: the one refusal the server makes on version (§5.2).
    webSocket("/v1/live") {
        send(Frame.Text(ServerFrames.error(ErrorCode.UnsupportedVersion, "this server speaks $LIVE_PROTOCOL; the app needs updating")))
        close(CloseReason(POLICY, "unsupported version"))
    }
}

/** One tablet's connection, from upgrade to close. */
private class TabletSocket(
    private val session: DefaultWebSocketServerSession,
    private val registry: CarRegistry,
    private val archive: ArchiveService,
    private val hub: LiveHub,
    private val crew: CrewMessages,
    private val config: LiveConfig,
    private val clock: Clock,
    private val downlink: CourseDownlink,
    private val timings: LiveTimings?,
    private val timing: TimingDownlink?,
) : TabletHandle {
    private val log = session.call.application.log

    suspend fun run() {
        // Authenticated after the upgrade, so a refusal can be the contract's own frame (§5.2).
        val token = bearerToken(session.call)
        val car = token?.let { registry.principalFor(it) }
        if (car == null) return refuse(ErrorCode.Auth, "this car's token was not recognised")

        var attachment: Attachment? = null
        val watchdog = session.launch {
            // A clean close before Cloud Run's cut, so the tablet reconnects at once (§5.3).
            launch { delay(config.maxAge); close(CloseReason.Codes.GOING_AWAY.code, "socket age") }
            // A rotated or removed token is shut out within one period.
            while (true) {
                delay(config.recheck)
                if (registry.principalFor(token)?.slug != car.slug) {
                    refuse(ErrorCode.Auth, "this car's token was rotated or removed")
                    break
                }
            }
        }
        try {
            var hello = false
            for (frame in session.incoming) {
                if (frame !is Frame.Text) {
                    badMessage("frames are JSON text")
                    continue
                }
                val parsed = TabletFrames.parse(frame.readText())
                if (parsed is TabletFrames.Parsed.Bad) {
                    badMessage(parsed.reason)
                    continue
                }
                val tabletFrame = (parsed as TabletFrames.Parsed.Ok).frame
                if (tabletFrame is TabletFrame.Hello) {
                    hello = true
                    if (attachment == null) attachment = hub.attach(car.slug, this)
                    session.send(Frame.Text(ServerFrames.welcome(clock.millis())))
                    // The complete active set, after every hello (§5.4): the tablet takes down anything not in it.
                    session.send(Frame.Text(crew.sync(car.slug)))
                    // Courses (M12.6): only to a tablet that asked, which then hears of every change too.
                    if (CourseDownlink.FEATURE in tabletFrame.features) {
                        downlink.listen(this)
                        session.send(Frame.Text(downlink.frame()))
                    }
                    // `timing` (M17.4) follows the session frame, which comes after every hello.
                    timing?.listen(car.slug, this, TimingDownlink.FEATURE in tabletFrame.features)
                    continue
                }
                val attached = attachment
                if (!hello || attached == null) {
                    badMessage("hello comes first")
                    continue
                }
                if (tabletFrame is TabletFrame.Session &&
                    archive.announce(car.slug, tabletFrame.id) == ArchiveService.Announce.WrongCar
                ) {
                    badMessage("session ${tabletFrame.id} belongs to another car")
                    continue
                }
                when (tabletFrame) {
                    is TabletFrame.Received -> crew.received(car.slug, tabletFrame.id)
                    is TabletFrame.Displayed -> crew.displayed(car.slug, tabletFrame.id)
                    else -> Unit
                }
                val applied = attached.apply(tabletFrame)
                if (applied is CarLive.Applied.Refused) {
                    badMessage(applied.reason)
                } else {
                    timings?.offer(car.slug, tabletFrame)
                    if (tabletFrame is TabletFrame.Session) timing?.sessionStarted(car.slug)
                }
            }
        } finally {
            watchdog.cancel()
            attachment?.detach()
            downlink.leave(this)
            timing?.leave(car.slug, this)
        }
    }

    override fun superseded() {
        session.launch { refuse(ErrorCode.Superseded, "another connection on this car's token took over") }
    }

    override fun close(code: Short, reason: String) {
        session.launch { session.close(CloseReason(code, reason)) }
    }

    override fun send(frame: String) {
        session.launch { runCatching { session.send(Frame.Text(frame)) } }
    }

    private suspend fun refuse(code: ErrorCode, message: String) {
        runCatching {
            session.send(Frame.Text(ServerFrames.error(code, message)))
            session.close(CloseReason(POLICY, code.wire))
        }
    }

    private suspend fun badMessage(reason: String) {
        log.info("Live: bad_message: $reason")
        runCatching { session.send(Frame.Text(ServerFrames.error(ErrorCode.BadMessage, reason))) }
    }
}
