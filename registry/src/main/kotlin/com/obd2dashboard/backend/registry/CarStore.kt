package com.obd2dashboard.backend.registry

import java.util.concurrent.ConcurrentHashMap

/**
 * Where cars are kept. Firestore in production (M2.2); [InMemoryCarStore] in tests.
 *
 * Deliberately thin: rules live in [CarRegistry], so every store behaves the same
 * because it has nothing to decide.
 */
public interface CarStore {
    public suspend fun get(slug: Slug): Car?

    /**
     * The car whose current token hashes to [tokenHash], or null.
     *
     * @throws RegistryException.DuplicateToken if more than one does.
     */
    public suspend fun findByTokenHash(tokenHash: String): Car?

    public suspend fun list(): List<Car>

    /** Adds [car] **atomically**, returning false if its slug is already taken. */
    public suspend fun create(car: Car): Boolean

    /** Replaces the stored car with [car]'s slug, returning false if there is none. */
    public suspend fun update(car: Car): Boolean

    /** Removes the car, returning false if there was none. */
    public suspend fun delete(slug: Slug): Boolean
}

/** A [CarStore] in memory, for tests. */
public class InMemoryCarStore : CarStore {
    private val cars = ConcurrentHashMap<Slug, Car>()

    override suspend fun get(slug: Slug): Car? = cars[slug]

    override suspend fun findByTokenHash(tokenHash: String): Car? {
        val matches = cars.values.filter { it.tokenHash == tokenHash }
        if (matches.size > 1) throw RegistryException.DuplicateToken(matches.map { it.slug })
        return matches.singleOrNull()
    }

    override suspend fun list(): List<Car> = cars.values.toList()

    override suspend fun create(car: Car): Boolean = cars.putIfAbsent(car.slug, car) == null

    override suspend fun update(car: Car): Boolean = cars.replace(car.slug, car) != null

    override suspend fun delete(slug: Slug): Boolean = cars.remove(slug) != null
}
