package com.obd2dashboard.backend.tools

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Passcodes
import com.obd2dashboard.backend.registry.Slug
import com.obd2dashboard.backend.registry.Tokens
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AdminTest {
    private val store = InMemoryCarStore()
    private val registry = CarRegistry(store, random = SecureRandom(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val segments = InMemorySegmentStore()
    private val archive = ArchiveService(sessions, segments)
    private val tools = Tools(registry, sessions, archive)
    private val yaris = Slug.parse("yaris")

    /** Answers prompts from queues; records every prompt shown. */
    private class FakeIo(lines: List<String> = emptyList(), secrets: List<String> = emptyList()) : AdminIo {
        private val lines = ArrayDeque(lines)
        private val secrets = ArrayDeque(secrets)
        val prompts = mutableListOf<String>()
        override fun readLine(prompt: String): String? = lines.removeFirstOrNull().also { prompts += prompt }
        override fun readSecret(prompt: String): CharArray {
            prompts += prompt
            return secrets.removeFirst().toCharArray()
        }
    }

    private fun run(args: String, io: AdminIo = FakeIo()): CliktCommandTestResult {
        var seen: Pair<String, String>? = null
        val result = Admin({ project, bucket -> seen = project to bucket; tools }, io)
            .test("--project test-project --bucket test-bucket $args")
        if (result.statusCode == 0) seen shouldBe ("test-project" to "test-bucket")
        return result
    }

    private fun tokenIn(output: String): String =
        Regex("${Regex.escape(Tokens.PREFIX)}[A-Za-z0-9_-]{43}").find(output)?.value.shouldNotBeNull()

    private fun tokenCount(output: String): Int =
        Regex("${Regex.escape(Tokens.PREFIX)}[A-Za-z0-9_-]{43}").findAll(output).count()

    @Test
    fun `add-car prints a working token and says it will not be shown again`() {
        val result = run("add-car yaris --name Yaris")
        result.statusCode shouldBe 0
        val token = tokenIn(result.stdout)
        result.stdout shouldContain "shown once"
        runBlocking { registry.authenticate(token)?.slug } shouldBe yaris
    }

    @Test
    fun `the token appears in add-car and rotate-token output and nowhere else`() {
        val added = run("add-car yaris --name Yaris").stdout
        tokenCount(added) shouldBe 1
        val token = tokenIn(added)
        val list = run("list")
        list.stdout shouldNotContain token
        list.stdout shouldNotContain token.removePrefix(Tokens.PREFIX).dropLast(4)
        run("rename yaris --name Renamed").stdout shouldNotContain token

        val rotated = run("rotate-token yaris", FakeIo(lines = listOf("y")))
        val newToken = tokenIn(rotated.stdout)
        tokenCount(rotated.stdout) shouldBe 1
        run("list").stdout shouldNotContain newToken
        runBlocking {
            registry.authenticate(token).shouldBeNull()
            registry.authenticate(newToken)?.slug shouldBe yaris
        }
    }

    @Test
    fun `list shows the hint and passcode state, never a hash`() {
        run("add-car yaris --name Yaris")
        run("set-passcode yaris", FakeIo(secrets = listOf("pit-lane", "pit-lane")))
        val car = runBlocking { registry.get(yaris) }.shouldNotBeNull()

        val out = run("list").stdout
        out shouldContain "yaris"
        out shouldContain "…${car.tokenHint}"
        out shouldContain "set"
        out shouldNotContain car.tokenHash
        out shouldNotContain car.passcodeHash!!
        out shouldNotContain "pbkdf2"
    }

    @Test
    fun `list with no cars says so`() {
        run("list").stdout shouldContain "No cars registered."
    }

    @Test
    fun `set-passcode stores a verifiable passcode, not echoed anywhere`() {
        run("add-car yaris --name Yaris")
        val io = FakeIo(secrets = listOf("pit-lane", "pit-lane"))
        val result = run("set-passcode yaris", io)
        result.statusCode shouldBe 0
        result.stdout shouldNotContain "pit-lane"
        io.prompts shouldHaveSize 2
        val stored = runBlocking { registry.get(yaris) }?.passcodeHash.shouldNotBeNull()
        Passcodes.verify("pit-lane".toCharArray(), stored) shouldBe true
    }

    @Test
    fun `a mismatched passcode is refused and nothing changes`() {
        run("add-car yaris --name Yaris")
        val result = run("set-passcode yaris", FakeIo(secrets = listOf("pit-lane", "pit-lanf")))
        result.statusCode shouldBe 1
        result.stderr shouldContain "differ"
        runBlocking { registry.get(yaris) }?.passcodeHash.shouldBeNull()
    }

    @Test
    fun `a short passcode is refused with the registry's reason`() {
        run("add-car yaris --name Yaris")
        val result = run("set-passcode yaris", FakeIo(secrets = listOf("12345", "12345")))
        result.statusCode shouldBe 1
        result.stderr shouldContain "at least 6 characters"
    }

    @Test
    fun `rotate-token without a yes changes nothing`() {
        val token = tokenIn(run("add-car yaris --name Yaris").stdout)
        val result = run("rotate-token yaris", FakeIo(lines = listOf("n")))
        result.statusCode shouldBe 1
        result.stdout shouldNotContain Tokens.PREFIX
        runBlocking { registry.authenticate(token)?.slug } shouldBe yaris
    }

    @Test
    fun `remove-car with the wrong slug typed changes nothing`() {
        val token = tokenIn(run("add-car yaris --name Yaris").stdout)
        val result = run("remove-car yaris", FakeIo(lines = listOf("yaris2")))
        result.statusCode shouldBe 1
        result.stderr shouldContain "Not removed."
        runBlocking { registry.authenticate(token)?.slug } shouldBe yaris
    }

    @Test
    fun `remove-car with the slug typed removes it and revokes the token`() {
        val token = tokenIn(run("add-car yaris --name Yaris").stdout)
        run("remove-car yaris", FakeIo(lines = listOf("yaris"))).statusCode shouldBe 0
        runBlocking { registry.authenticate(token) }.shouldBeNull()
    }

    @Test
    fun `registry refusals are shown as written and exit 1`() {
        run("add-car yaris --name Yaris")
        run("add-car yaris --name Again").let {
            it.statusCode shouldBe 1
            it.stderr shouldContain "a car with slug \"yaris\" already exists"
        }
        run("add-car API --name X").stderr shouldContain "is not a valid slug"
        run("rename nope --name X").stderr shouldContain "no car with slug \"nope\""
        run("rotate-token nope", FakeIo(lines = listOf("y"))).stderr shouldContain "no car with slug \"nope\""
    }

    @Test
    fun `a missing car is reported before any prompt`() {
        val io = FakeIo(lines = listOf("y"), secrets = listOf("pit-lane", "pit-lane"))
        run("set-passcode nope", io).statusCode shouldBe 1
        run("remove-car nope", io).statusCode shouldBe 1
        io.prompts shouldBe emptyList()
    }

    @Test
    fun `the project and bucket are required`() {
        Admin({ _, _ -> tools }, FakeIo()).test("list").statusCode shouldBe 1
        Admin({ _, _ -> tools }, FakeIo()).test("--project p list").statusCode shouldBe 1
    }

    // Sessions (M3.6)

    private val sessionId = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
    private val line0 = """{"type":"session","v":3,"id":"$sessionId","device":"dev-1","app":"1.0","started":"2026-09-24T13:08:32.623Z","vin":"TSTVEHCLE00000001","seq":0,"at":0}"""

    private fun seedSession(car: String = "yaris") = runBlocking {
        archive.open(car, sessionId, line0.toByteArray()) shouldBe ArchiveService.Open.Created(0)
    }

    @Test
    fun `sessions lists them without a VIN, and session shows it`() {
        run("add-car yaris --name Yaris")
        seedSession()
        val list = run("sessions")
        list.statusCode shouldBe 0
        list.stdout shouldContain sessionId
        list.stdout shouldContain "2026-09-24T13:08:32.623Z"
        list.stdout shouldContain "uploading"
        list.stdout shouldNotContain "TSTVEHCLE"
        run("sessions yaris").stdout shouldContain sessionId
        run("sessions outback").stdout shouldContain "No sessions."

        val one = run("session $sessionId")
        one.stdout shouldContain "TSTVEHCLE00000001"
        one.stdout shouldContain "dev-1"
        run("session 00000000-0000-4000-8000-000000000000").statusCode shouldBe 1
    }

    @Test
    fun `delete-session needs the id typed again`() {
        run("add-car yaris --name Yaris")
        seedSession()
        run("delete-session $sessionId", FakeIo(lines = listOf("nope"))).let {
            it.statusCode shouldBe 1
            it.stderr shouldContain "Not deleted."
        }
        runBlocking { sessions.get(sessionId) } shouldNotBe null

        run("delete-session $sessionId", FakeIo(lines = listOf(sessionId))).statusCode shouldBe 0
        runBlocking { sessions.get(sessionId) }.shouldBeNull()
        segments.objects.keys.none { it.contains(sessionId) } shouldBe true
    }

    @Test
    fun `remove-car refuses while the car has sessions, and says how many`() {
        val token = tokenIn(run("add-car yaris --name Yaris").stdout)
        seedSession()
        val io = FakeIo(lines = listOf("yaris"))
        run("remove-car yaris", io).let {
            it.statusCode shouldBe 1
            it.stderr shouldContain "1 session"
        }
        io.prompts shouldBe emptyList() // refused before asking
        runBlocking { registry.authenticate(token)?.slug } shouldBe yaris

        run("delete-session $sessionId", FakeIo(lines = listOf(sessionId)))
        run("remove-car yaris", FakeIo(lines = listOf("yaris"))).statusCode shouldBe 0
    }
}
