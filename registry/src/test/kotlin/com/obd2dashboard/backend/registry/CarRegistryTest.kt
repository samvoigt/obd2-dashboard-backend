package com.obd2dashboard.backend.registry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CarRegistryTest {
    private val start = Instant.parse("2026-09-26T12:00:00Z")
    private var clock = Clock.fixed(start, ZoneOffset.UTC)
    private val store = InMemoryCarStore()
    private val registry get() = CarRegistry(store, clock, SecureRandom(), passcodeIterations = 1_000)

    private val yaris = Slug.parse("yaris")
    private val outback = Slug.parse("outback")

    @Test
    fun `adding a car returns a token that authenticates as that car`() = runTest {
        val issued = registry.addCar(yaris, "  Yaris  ")

        issued.car.name shouldBe "Yaris"
        issued.car.tokenHash shouldBe Tokens.hash(issued.token)
        issued.car.tokenHint shouldBe Tokens.hint(issued.token)
        issued.car.tokenIssued shouldBe start
        issued.car.passcodeHash.shouldBeNull()
        registry.authenticate(issued.token)?.slug shouldBe yaris
    }

    @Test
    fun `the store never holds the token itself`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        store.get(yaris).toString() shouldNotContain issued.token
        store.get(yaris).toString() shouldNotContain issued.token.removePrefix(Tokens.PREFIX)
    }

    @Test
    fun `an issued token does not print itself`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        issued.toString() shouldNotContain issued.token
    }

    @Test
    fun `a duplicate slug is refused and the first car is untouched`() = runTest {
        val first = registry.addCar(yaris, "Yaris")
        shouldThrow<RegistryException.CarExists> { registry.addCar(yaris, "Another") }
        registry.authenticate(first.token)?.name shouldBe "Yaris"
    }

    @Test
    fun `rotating makes the old token fail and the new one pass`() = runTest {
        val old = registry.addCar(yaris, "Yaris")
        clock = Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC)

        val new = registry.rotateToken(yaris)

        new.token shouldNotBe old.token
        registry.authenticate(old.token).shouldBeNull()
        registry.authenticate(new.token)?.slug shouldBe yaris
        new.car.tokenIssued shouldBe start.plusSeconds(60)
        new.car.created shouldBe start
    }

    @Test
    fun `rotating keeps the passcode`() = runTest {
        registry.addCar(yaris, "Yaris")
        registry.setPasscode(yaris, "pit-lane".toCharArray())
        registry.rotateToken(yaris)
        Passcodes.verify("pit-lane".toCharArray(), registry.get(yaris)!!.passcodeHash!!) shouldBe true
    }

    @Test
    fun `two cars' tokens resolve to their own cars`() = runTest {
        val a = registry.addCar(yaris, "Yaris")
        val b = registry.addCar(outback, "Outback")
        registry.authenticate(a.token)?.slug shouldBe yaris
        registry.authenticate(b.token)?.slug shouldBe outback
    }

    @Test
    fun `authenticate returns nothing for unknown or malformed tokens`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        val unknown = Tokens.generate(SecureRandom())
        for (token in listOf(unknown, "", "Bearer x", issued.token + " ", issued.token.dropLast(1), Tokens.hash(issued.token))) {
            registry.authenticate(token).shouldBeNull()
        }
    }

    @Test
    fun `a chosen token replaces the old one at once`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        registry.setToken(yaris, "bears-yaris-15")
        registry.authenticate("bears-yaris-15")?.slug shouldBe yaris
        registry.authenticate(issued.token).shouldBeNull()
        registry.get(yaris)!!.tokenHint shouldBe "-15"
        registry.setToken(yaris, "bears-yaris-15") // setting the same one again is fine
        registry.authenticate("bears-yaris-15")?.slug shouldBe yaris
    }

    @Test
    fun `a chosen token is refused if malformed, another car's, or for no car`() = runTest {
        registry.addCar(yaris, "Yaris")
        registry.addCar(outback, "Outback")
        registry.setToken(outback, "bears-outback")
        shouldThrow<RegistryException.InvalidToken> { registry.setToken(yaris, "short") }
        shouldThrow<RegistryException.InvalidToken> { registry.setToken(yaris, "has spaces in it") }
        shouldThrow<RegistryException.TokenInUse> { registry.setToken(yaris, "bears-outback") }
        shouldThrow<RegistryException.NoSuchCar> { registry.setToken(Slug.parse("nope"), "bears-nope-1") }
        registry.authenticate("bears-outback")?.slug shouldBe outback
    }

    @Test
    fun `a passcode round-trips and a short one is refused`() = runTest {
        registry.addCar(yaris, "Yaris")
        shouldThrow<RegistryException.PasscodeTooShort> { registry.setPasscode(yaris, "12345".toCharArray()) }
        registry.get(yaris)!!.passcodeHash.shouldBeNull()

        registry.setPasscode(yaris, "123456".toCharArray())
        val stored = registry.get(yaris)!!.passcodeHash.shouldNotBeNull()
        Passcodes.verify("123456".toCharArray(), stored) shouldBe true
        Passcodes.verify("123457".toCharArray(), stored) shouldBe false
    }

    @Test
    fun `renaming changes the name and not the token`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        registry.rename(yaris, "Yaris #42")
        registry.authenticate(issued.token)?.name shouldBe "Yaris #42"
    }

    @Test
    fun `names are trimmed and must be 1 to 60 characters`() = runTest {
        shouldThrow<RegistryException.InvalidName> { registry.addCar(yaris, "   ") }
        shouldThrow<RegistryException.InvalidName> { registry.addCar(yaris, "x".repeat(Car.MAX_NAME_LENGTH + 1)) }
        registry.addCar(yaris, "x".repeat(Car.MAX_NAME_LENGTH)).car.name.length shouldBe Car.MAX_NAME_LENGTH
    }

    @Test
    fun `operations on a missing car say so`() = runTest {
        shouldThrow<RegistryException.NoSuchCar> { registry.rotateToken(yaris) }
        shouldThrow<RegistryException.NoSuchCar> { registry.setPasscode(yaris, "123456".toCharArray()) }
        shouldThrow<RegistryException.NoSuchCar> { registry.rename(yaris, "Yaris") }
        shouldThrow<RegistryException.NoSuchCar> { registry.removeCar(yaris) }
    }

    @Test
    fun `removing a car revokes its token`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        registry.removeCar(yaris)
        registry.authenticate(issued.token).shouldBeNull()
        registry.list() shouldBe emptyList()
    }

    @Test
    fun `list is by name, case-insensitively, then slug`() = runTest {
        registry.addCar(Slug.parse("zed"), "alpha")
        registry.addCar(outback, "Bravo")
        registry.addCar(yaris, "alpha")
        registry.list().map { it.slug.value } shouldContainExactly listOf("yaris", "zed", "outback")
    }

    @Test
    fun `two cars sharing a token hash is corruption, not a coin toss`() = runTest {
        val issued = registry.addCar(yaris, "Yaris")
        store.create(store.get(yaris)!!.copy(slug = outback))
        shouldThrow<RegistryException.DuplicateToken> { registry.authenticate(issued.token) }
    }
}
