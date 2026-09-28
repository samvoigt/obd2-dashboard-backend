package com.obd2dashboard.backend.events

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Where drivers live (Firestore in production). */
public interface DriverStore {
    public suspend fun list(): List<Driver>

    public suspend fun get(id: String): Driver?

    /**
     * Stores [driver] (new, or its new name or code), unless another driver has
     * its code: then null, and nothing changes. Checked and written as one.
     */
    public suspend fun put(driver: Driver): Driver?

    public suspend fun delete(id: String): Boolean
}

/** Where events live (Firestore in production). */
public interface EventStore {
    /** Every event, newest date first. */
    public suspend fun list(): List<Event>

    public suspend fun get(id: String): Event?

    /**
     * Stores [event] as its next revision, only if the stored one is still at
     * [expected] (0 for a new event): two editors can't both win. Null if it had
     * moved on (or, for a new one, the id is taken).
     */
    public suspend fun save(event: Event, expected: Int, now: Instant): Event?

    public suspend fun delete(id: String): Boolean
}

public class InMemoryDriverStore : DriverStore {
    private val drivers = ConcurrentHashMap<String, Driver>()

    override suspend fun list(): List<Driver> = drivers.values.sortedBy { it.name }

    override suspend fun get(id: String): Driver? = drivers[id]

    override suspend fun put(driver: Driver): Driver? = synchronized(drivers) {
        if (drivers.values.any { it.id != driver.id && it.code == driver.code }) return null
        drivers[driver.id] = driver
        driver
    }

    override suspend fun delete(id: String): Boolean = drivers.remove(id) != null
}

public class InMemoryEventStore : EventStore {
    private val events = ConcurrentHashMap<String, Event>()

    override suspend fun list(): List<Event> = events.values.sortedWith(compareByDescending<Event> { it.date }.thenBy { it.name })

    override suspend fun get(id: String): Event? = events[id]

    override suspend fun save(event: Event, expected: Int, now: Instant): Event? {
        var saved: Event? = null
        events.compute(event.id) { _, current ->
            if ((current?.revision ?: 0) != expected) return@compute current
            event.copy(revision = expected + 1, updated = now).also { saved = it }
        }
        return saved
    }

    override suspend fun delete(id: String): Boolean = events.remove(id) != null
}
