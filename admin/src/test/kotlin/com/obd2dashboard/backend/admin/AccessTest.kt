package com.obd2dashboard.backend.admin

import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Who may do what (M23): every cell of the plan's table. */
class AccessTest {
    private val master = Who("sam@example.com", Role.MASTER)
    private val creator = Who("ann@example.com", Role.USER)
    private val editor = Who("ed@example.com", Role.USER)
    private val other = Who("olga@example.com", Role.USER)
    private val record = Access(creator = "ann@example.com", editors = setOf("ed@example.com"))

    private fun can(who: Who, access: Access?) = Action.entries.filter { Permissions.may(who, it, access) }.toSet()

    @Test
    fun `a master admin may do anything, to anything, with or without a record`() {
        can(master, record) shouldBe Action.entries.toSet()
        can(master, null) shouldBe Action.entries.toSet()
    }

    @Test
    fun `the creator edits, shares and deletes, but never invites`() {
        can(creator, record) shouldBe setOf(Action.CREATE, Action.EDIT, Action.SHARE, Action.DELETE)
    }

    @Test
    fun `an editor edits, but neither shares nor deletes`() {
        can(editor, record) shouldBe setOf(Action.CREATE, Action.EDIT)
    }

    @Test
    fun `any other user only creates`() {
        can(other, record) shouldBe setOf(Action.CREATE)
    }

    @Test
    fun `a thing with no record is a master admin's alone`() {
        can(creator, null) shouldBe setOf(Action.CREATE)
    }

    @Test
    fun `things are keyed kind and id, and read back`() {
        Thing(Kind.CAR, "outback").key shouldBe "car:outback"
        Thing.parse("event:nhms-october") shouldBe Thing(Kind.EVENT, "nhms-october")
        Thing.parse("driver:d-0a1b2c3d") shouldBe Thing(Kind.DRIVER, "d-0a1b2c3d")
        Thing.parse("boat:x") shouldBe null
        Thing.parse("car:") shouldBe null
        Thing.parse("car") shouldBe null
    }

    @Test
    fun `editors are added and removed by email, whatever its case`() {
        val a = Access("ann@example.com").withEditor(" Ed@Example.com ")
        a.editors shouldBe setOf("ed@example.com")
        a.withoutEditor("ED@example.com").editors shouldBe emptySet()
    }

    @Test
    fun `the in-memory stores keep emails lower case, one user each`() = runTest {
        val users = InMemoryUserStore()
        users.add(User("Ann@Example.com", "sam@example.com", Instant.EPOCH)) shouldBe true
        users.add(User("ann@example.com", "sam@example.com", Instant.EPOCH)) shouldBe false
        users.get("ANN@example.com")?.email shouldBe "ann@example.com"
        users.remove("ann@example.com") shouldBe true
        users.list() shouldBe emptyList()

        val access = InMemoryAccessStore()
        val car = Thing(Kind.CAR, "outback")
        access.set(car, Access("Ann@Example.com", setOf("Ed@Example.com")))
        access.get(car) shouldBe Access("ann@example.com", setOf("ed@example.com"))
        access.all().keys shouldBe setOf(car)
        access.remove(car)
        access.get(car) shouldBe null
    }
}
