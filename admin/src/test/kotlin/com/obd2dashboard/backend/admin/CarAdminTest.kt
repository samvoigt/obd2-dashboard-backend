package com.obd2dashboard.backend.admin

import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.RegistryException
import com.obd2dashboard.backend.registry.Slug
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CarAdminTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val messages = InMemoryMessageStore()
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val admin = CarAdmin(registry, ArchiveService(sessions, InMemorySegmentStore()), Messages(messages), Clock.fixed(now, ZoneOffset.UTC))
    private val yaris = Slug.parse("yaris")

    private suspend fun message(id: String, car: String) =
        messages.create(Message(id, car, "PIT NOW", "pit", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), MessageState.Cleared))

    @Test
    fun `removing a car revokes its token and deletes its messages only`() = runTest {
        val token = registry.addCar(yaris, "Yaris").token
        registry.addCar(Slug.parse("outback"), "Outback")
        message("m_1", "yaris")
        message("m_2", "outback")
        admin.removeCar(yaris) shouldBe 1
        registry.authenticate(token).shouldBeNull()
        messages.get("m_1").shouldBeNull()
        messages.get("m_2").shouldNotBeNull()
    }

    @Test
    fun `a car with sessions is refused, and nothing changes`() = runTest {
        val token = registry.addCar(yaris, "Yaris").token
        message("m_1", "yaris")
        sessions.create(SessionRecord(SESSION, "yaris", null, null, -1, emptyList(), false, null, 0, Instant.EPOCH, Instant.EPOCH))
        shouldThrow<CarHasSessions> { admin.checkRemovable(yaris) }.count shouldBe 1
        shouldThrow<CarHasSessions> { admin.removeCar(yaris) }.count shouldBe 1
        registry.authenticate(token)?.slug shouldBe yaris
        messages.get("m_1").shouldNotBeNull()
    }

    @Test
    fun `a car added with a generated token gets it once`() = runTest {
        val token = admin.addCar(yaris, "Yaris")!!
        registry.authenticate(token)?.slug shouldBe yaris
    }

    @Test
    fun `a car added with a chosen token has only that one`() = runTest {
        admin.addCar(yaris, "Yaris", "bears-yaris-15") shouldBe null
        registry.authenticate("bears-yaris-15")?.slug shouldBe yaris
        registry.get(yaris)!!.tokenHint shouldBe "-15"
    }

    @Test
    fun `a chosen token that is bad or taken leaves no car behind`() = runTest {
        admin.addCar(Slug.parse("outback"), "Outback", "bears-outback")
        shouldThrow<RegistryException.InvalidToken> { admin.addCar(yaris, "Yaris", "short") }
        registry.get(yaris).shouldBeNull()
        shouldThrow<RegistryException.TokenInUse> { admin.addCar(yaris, "Yaris", "bears-outback") }
        registry.get(yaris).shouldBeNull()
        registry.authenticate("bears-outback")?.slug shouldBe Slug.parse("outback")
    }

    private suspend fun session(complete: Boolean, updated: Instant) =
        sessions.create(SessionRecord(SESSION, "yaris", null, null, -1, emptyList(), complete, null, 0, Instant.EPOCH, updated))

    @Test
    fun `a quiet incomplete session, or a complete one, can be deleted`() = runTest {
        session(complete = false, updated = now.minus(CarAdmin.UPLOAD_QUIET))
        admin.checkDeletable(SESSION).id shouldBe SESSION
        admin.deleteSession(SESSION)
        sessions.get(SESSION).shouldBeNull()
        session(complete = true, updated = now)
        admin.deleteSession(SESSION)
        sessions.get(SESSION).shouldBeNull()
    }

    @Test
    fun `a session still uploading, or live, is refused and kept`() = runTest {
        session(complete = false, updated = now.minus(CarAdmin.UPLOAD_QUIET).plusSeconds(1))
        shouldThrow<SessionBusy> { admin.deleteSession(SESSION) }.live shouldBe false
        sessions.get(SESSION).shouldNotBeNull()
        sessions.delete(SESSION)
        session(complete = true, updated = Instant.EPOCH)
        shouldThrow<SessionBusy> { admin.deleteSession(SESSION, live = true) }.live shouldBe true
        sessions.get(SESSION).shouldNotBeNull()
        shouldThrow<NoSuchSession> { admin.deleteSession("00000000-0000-4000-8000-000000000000") }
    }

    @Test
    fun `an unknown car is refused`() = runTest {
        shouldThrow<RegistryException.NoSuchCar> { admin.checkRemovable(yaris) }
        shouldThrow<RegistryException.NoSuchCar> { admin.removeCar(yaris) }
    }

    private companion object {
        const val SESSION = "0b8f3c52-5f7e-4b7e-9c55-1d7b0a3e9f10"
    }
}
