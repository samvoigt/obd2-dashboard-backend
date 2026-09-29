package com.obd2dashboard.backend.live

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the hub needs from a tablet's socket, and nothing about how it is carried. */
public interface TabletHandle {
    /** Another socket on this car's token took over (§5.2): send `superseded` and close. */
    public fun superseded()

    /** Close cleanly with [code]: 1001 when the socket is old, 1012 on shutdown (§5.3). */
    public fun close(code: Short, reason: String)

    /** Sends a frame to the tablet (a crew `message` or `clear`, §5.2). */
    public fun send(frame: String)
}

/** One tablet's hold on its car. Once superseded or detached, it changes nothing. */
public interface Attachment {
    public suspend fun apply(frame: TabletFrame): CarLive.Applied
    public suspend fun detach()
}

/** What a browser receives: a snapshot first (and again whenever it fell behind), then updates. */
public sealed interface BrowserEvent {
    public data class Snapshot(val snapshot: LiveSnapshot) : BrowserEvent
    public data class Update(val update: LiveUpdate) : BrowserEvent
}

/**
 * Cars' live state, between their tablets and their browsers (decision 7).
 *
 * An interface so the in-memory hub can give way to Pub/Sub or Redis if one
 * instance ever stops being enough; nothing outside depends on it being local.
 */
public interface LiveHub {
    /** Attaches [tablet] as [car]'s socket, superseding any earlier one. */
    public suspend fun attach(car: String, tablet: TabletHandle): Attachment

    /** A snapshot, then every update, for as long as it is collected. */
    public fun subscribe(car: String): Flow<BrowserEvent>

    public suspend fun status(car: String): CarStatus

    /** [car]'s session [id]'s smallest measured clock offset (M18.1); null if not measured, or another session is current. */
    public suspend fun sessionOffset(car: String, id: String): java.time.Duration?

    /** Sends [frame] to [car]'s attached tablet; false if none is attached (a message then waits for the next sync). */
    public suspend fun toTablet(car: String, frame: String): Boolean

    /** Tells [car]'s browsers of something that did not come from its tablet (a crew message's state). */
    public suspend fun publish(car: String, update: LiveUpdate)

    /**
     * Closes every tablet's socket with [code] (1012 on shutdown or drain), and
     * **ends every browser's stream**, so both reconnect, and on a deploy land on
     * the new revision (M4.8a).
     */
    public suspend fun closeAll(code: Short, reason: String)
}

public class InMemoryLiveHub(
    private val clock: Clock = Clock.systemUTC(),
    private val subscriberBuffer: Int = 256,
    private val newCar: () -> CarLive = { CarLive(clock) },
) : LiveHub {
    private class Subscriber(buffer: Int) {
        val channel = Channel<BrowserEvent>(buffer)
    }

    private inner class Car {
        val live = newCar()
        val mutex = Mutex()
        var tablet: TabletAttachment? = null
        val subscribers = LinkedHashSet<Subscriber>()

        /** Called with [mutex] held. A subscriber that cannot keep up is resnapshotted; nobody waits. */
        fun publish(update: LiveUpdate) {
            for (sub in subscribers) {
                if (!sub.channel.trySend(BrowserEvent.Update(update)).isSuccess) {
                    // What it missed is all in the snapshot, so drop it rather than deliver it late.
                    do {
                        val dropped = sub.channel.tryReceive()
                    } while (dropped.isSuccess)
                    sub.channel.trySend(BrowserEvent.Snapshot(live.snapshot()))
                }
            }
        }
    }

    private inner class TabletAttachment(private val car: Car, val handle: TabletHandle) : Attachment {
        var active = true

        override suspend fun apply(frame: TabletFrame): CarLive.Applied = car.mutex.withLock {
            if (!active) return@withLock CarLive.Applied.Refused("this socket has been superseded")
            val result = car.live.apply(frame)
            if (result is CarLive.Applied.Ok) result.updates.forEach(car::publish)
            result
        }

        override suspend fun detach() = car.mutex.withLock {
            if (!active) return@withLock
            active = false
            car.tablet = null
            car.publish(car.live.disconnected())
        }
    }

    private val cars = ConcurrentHashMap<String, Car>()

    private fun car(slug: String): Car = cars.computeIfAbsent(slug) { Car() }

    override suspend fun attach(car: String, tablet: TabletHandle): Attachment {
        val c = car(car)
        return c.mutex.withLock {
            c.tablet?.let { old ->
                old.active = false
                old.handle.superseded()
            }
            TabletAttachment(c, tablet).also {
                c.tablet = it
                c.publish(c.live.connected())
            }
        }
    }

    override fun subscribe(car: String): Flow<BrowserEvent> = flow {
        val c = car(car)
        val sub = Subscriber(subscriberBuffer)
        c.mutex.withLock {
            c.subscribers += sub
            sub.channel.trySend(BrowserEvent.Snapshot(c.live.snapshot()))
        }
        try {
            for (event in sub.channel) emit(event)
        } finally {
            c.mutex.withLock { c.subscribers -= sub }
            sub.channel.close()
        }
    }

    override suspend fun status(car: String): CarStatus {
        val c = cars[car] ?: return CarStatus(connected = false, inSession = false, lastDataAt = null)
        return c.mutex.withLock { c.live.status() }
    }

    override suspend fun sessionOffset(car: String, id: String): java.time.Duration? {
        val c = cars[car] ?: return null
        return c.mutex.withLock { c.live.sessionOffset(id) }
    }

    override suspend fun toTablet(car: String, frame: String): Boolean {
        val tablet = cars[car]?.let { c -> c.mutex.withLock { c.tablet } } ?: return false
        tablet.handle.send(frame)
        return true
    }

    override suspend fun publish(car: String, update: LiveUpdate) {
        val c = car(car)
        c.mutex.withLock { c.publish(if (update is LiveUpdate.Timing) c.live.timing(update.timing) else update) }
    }

    /** How many browsers are subscribed to [car]: for tests, which must see a departed browser removed. */
    internal suspend fun subscriberCount(car: String): Int = cars[car]?.let { c -> c.mutex.withLock { c.subscribers.size } } ?: 0

    override suspend fun closeAll(code: Short, reason: String) {
        for (c in cars.values) {
            val (tablet, subscribers) = c.mutex.withLock { c.tablet to c.subscribers.toList() }
            tablet?.handle?.close(code, reason)
            // Closing a browser's channel ends its flow, and with it the SSE response.
            subscribers.forEach { it.channel.close() }
        }
    }
}
