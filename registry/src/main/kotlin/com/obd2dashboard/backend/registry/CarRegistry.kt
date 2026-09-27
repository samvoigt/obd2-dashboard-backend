package com.obd2dashboard.backend.registry

import java.security.SecureRandom
import java.time.Clock

/**
 * What can be done to cars, and the rules for doing it.
 *
 * The server uses [authenticate] and [list]; the admin tool uses the rest.
 * **Nothing here caches**: [authenticate] asks the store every time, so a rotated
 * or removed token fails on the very next request, which is what the contract
 * promises (§8: rotating revokes the old token at once).
 */
public class CarRegistry(
    private val store: CarStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val passcodeIterations: Int = Passcodes.DEFAULT_ITERATIONS,
) {
    /** Registers a car and returns its first token, which is never retrievable again. */
    public suspend fun addCar(slug: Slug, name: String): IssuedToken {
        val now = clock.instant()
        val token = Tokens.generate(random)
        val car = Car(
            slug = slug,
            name = checkName(name),
            tokenHash = Tokens.hash(token),
            tokenHint = Tokens.hint(token),
            tokenIssued = now,
            passcodeHash = null,
            created = now,
            updated = now,
        )
        if (!store.create(car)) throw RegistryException.CarExists(slug)
        return IssuedToken(car, token)
    }

    /** Replaces the car's token; the old one stops working at once. */
    public suspend fun rotateToken(slug: Slug): IssuedToken {
        val now = clock.instant()
        val token = Tokens.generate(random)
        val car = require(slug).copy(
            tokenHash = Tokens.hash(token),
            tokenHint = Tokens.hint(token),
            tokenIssued = now,
            updated = now,
        )
        save(car)
        return IssuedToken(car, token)
    }

    /**
     * Replaces the car's token with one the owner chose (decision 24); the old one
     * stops working at once. Refused if it is malformed or another car's.
     */
    public suspend fun setToken(slug: Slug, token: String) {
        Tokens.problemWith(token)?.let { throw RegistryException.InvalidToken(it) }
        val car = require(slug)
        val hash = Tokens.hash(token)
        store.findByTokenHash(hash)?.let { if (it.slug != slug) throw RegistryException.TokenInUse(it.slug) }
        val now = clock.instant()
        save(car.copy(tokenHash = hash, tokenHint = Tokens.hint(token), tokenIssued = now, updated = now))
    }

    public suspend fun setPasscode(slug: Slug, passcode: CharArray) {
        if (passcode.size < Passcodes.MIN_LENGTH) throw RegistryException.PasscodeTooShort()
        val hash = Passcodes.hash(passcode, random, passcodeIterations)
        save(require(slug).copy(passcodeHash = hash, updated = clock.instant()))
    }

    public suspend fun rename(slug: Slug, name: String) {
        save(require(slug).copy(name = checkName(name), updated = clock.instant()))
    }

    public suspend fun removeCar(slug: Slug) {
        if (!store.delete(slug)) throw RegistryException.NoSuchCar(slug)
    }

    public suspend fun get(slug: Slug): Car? = store.get(slug)

    /** Every car, by name, then slug for equal names. */
    public suspend fun list(): List<Car> =
        store.list().sortedWith(compareBy({ it.name.lowercase() }, { it.slug.value }))

    /**
     * The car [token] belongs to, or null.
     *
     * A malformed token is refused before the store is asked, so an
     * `Authorization` header full of garbage costs nothing and cannot throw.
     */
    public suspend fun authenticate(token: String): Car? {
        if (!Tokens.isWellFormed(token)) return null
        return store.findByTokenHash(Tokens.hash(token))
    }

    private suspend fun require(slug: Slug): Car = store.get(slug) ?: throw RegistryException.NoSuchCar(slug)

    private suspend fun save(car: Car) {
        if (!store.update(car)) throw RegistryException.NoSuchCar(car.slug)
    }

    private fun checkName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw RegistryException.InvalidName("a name cannot be blank")
        if (name.length > Car.MAX_NAME_LENGTH) {
            throw RegistryException.InvalidName("a name has at most ${Car.MAX_NAME_LENGTH} characters")
        }
        return name
    }
}
