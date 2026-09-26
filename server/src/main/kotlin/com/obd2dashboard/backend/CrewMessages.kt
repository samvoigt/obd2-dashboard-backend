package com.obd2dashboard.backend

import com.obd2dashboard.backend.live.LiveHub
import com.obd2dashboard.backend.live.LiveUpdate
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.Messages
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Crew messages joined to the live lane (contract §5.4): [Messages]' rules, the
 * hub's route to a car's tablet, and every change told to the car's browsers
 * (crew ones only; the public stream drops `MessageChanged`).
 *
 * **Expiry both ways**: a timer from each send, and a lazy check on every sync
 * and send, since timers do not survive a restart or a deploy's drain.
 */
class CrewMessages(
    val messages: Messages,
    private val hub: LiveHub,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    /** Runs [action] after [delay]; tests capture it instead of waiting out a real minute. */
    private val schedule: (delay: Duration, action: suspend () -> Unit) -> Unit = { d, action ->
        scope.launch {
            delay(d.toMillis().coerceAtLeast(0))
            action()
        }
    },
) {
    /** Sends a message: stored, told to browsers, pushed to the tablet if attached (else it waits for the sync). */
    suspend fun send(car: String, text: String, preset: String?, ttl: Duration = Messages.DEFAULT_TTL): Message {
        expire(car)
        val sent = messages.send(car, text, preset, ttl)
        sent.replaced?.let { hub.publish(car, LiveUpdate.MessageChanged(it)) }
        hub.publish(car, LiveUpdate.MessageChanged(sent.message))
        hub.toTablet(car, messages.messageFrame(sent.message))
        schedule(Duration.between(clock.instant(), sent.message.expiresAt)) { expire(car) }
        return sent.message
    }

    /** Clears a message: told to browsers, and `clear` to the tablet; if it is away, the next sync leaves it out. */
    suspend fun clear(car: String, id: String): Message? {
        val cleared = messages.clear(car, id) ?: return null
        hub.publish(car, LiveUpdate.MessageChanged(cleared))
        hub.toTablet(car, messages.clearFrame(id))
        return cleared
    }

    /** The complete active set, for the `messages` frame after every `hello` (§5.4). */
    suspend fun sync(car: String): String {
        expire(car)
        return messages.syncFrame(car)
    }

    suspend fun received(car: String, id: String) {
        messages.received(car, id)?.let { hub.publish(car, LiveUpdate.MessageChanged(it)) }
    }

    suspend fun displayed(car: String, id: String) {
        messages.displayed(car, id)?.let { hub.publish(car, LiveUpdate.MessageChanged(it)) }
    }

    suspend fun expire(car: String) {
        messages.expireDue(car).forEach { hub.publish(car, LiveUpdate.MessageChanged(it)) }
    }
}
