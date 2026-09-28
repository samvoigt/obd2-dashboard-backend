package com.obd2dashboard.backend.tools

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Message
import com.obd2dashboard.backend.live.MessageState
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
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AdminTest {
    private val store = InMemoryCarStore()
    private val registry = CarRegistry(store, random = SecureRandom(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val segments = InMemorySegmentStore()
    // Uploads are stamped 10 minutes ago, so a seeded session is quiet enough to delete (M6.7).
    private val archive = ArchiveService(sessions, segments, java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(-10)))
    private val messages = InMemoryMessageStore()
    private val courses = com.obd2dashboard.backend.courses.InMemoryCourseStore()
    private val tools = Tools(registry, sessions, archive, messages, courses)

    private fun message(id: String, car: String) = runBlocking {
        messages.create(Message(id, car, "PIT NOW", "pit", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), MessageState.Cleared))
    }
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
    fun `a passcode file only its owner can read sets the passcode, and any other is refused`() {
        run("add-car yaris --name Yaris")
        val file = java.nio.file.Files.createTempFile("passcode", ".txt")
        try {
            java.nio.file.Files.writeString(file, "pit-lane\n")
            java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            run("set-passcode yaris --passcode-file $file").let {
                it.statusCode shouldBe 1
                it.stderr shouldContain "chmod 600"
            }
            runBlocking { registry.get(yaris) }?.passcodeHash.shouldBeNull()

            java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            run("set-passcode yaris --passcode-file $file").statusCode shouldBe 0
            val stored = runBlocking { registry.get(yaris) }?.passcodeHash.shouldNotBeNull()
            Passcodes.verify("pit-lane".toCharArray(), stored) shouldBe true // the trailing newline is not part of it
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
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
    fun `set-token takes a chosen token typed twice, and the old one stops working`() {
        val old = tokenIn(run("add-car yaris --name Yaris").stdout)
        val io = FakeIo(secrets = listOf("bears-yaris-15", "bears-yaris-15"))
        run("set-token yaris", io).let {
            it.statusCode shouldBe 0
            it.stdout shouldContain "Token set for yaris (ends …-15)"
            it.stdout shouldNotContain "bears-yaris-15"
        }
        io.prompts shouldBe listOf("New token for yaris: ", "Again: ")
        runBlocking { registry.authenticate("bears-yaris-15")?.slug } shouldBe yaris
        runBlocking { registry.authenticate(old) }.shouldBeNull()
    }

    @Test
    fun `set-token refuses a mismatch, a bad token, and another car's, changing nothing`() {
        val old = tokenIn(run("add-car yaris --name Yaris").stdout)
        run("add-car outback --name Outback --choose-token", FakeIo(secrets = listOf("bears-outback", "bears-outback")))
        for ((secrets, message) in listOf(
            listOf("bears-yaris-15", "bears-yaris-16") to "The two tokens differ. Nothing changed.",
            listOf("short", "short") to "A token needs at least 8 characters. Nothing changed.",
            listOf("has a space", "has a space") to "A token may use only letters, digits and . _ ~ - (no spaces). Nothing changed.",
            listOf("bears-outback", "bears-outback") to "that token is already outback's; choose another",
        )) {
            run("set-token yaris", FakeIo(secrets = secrets)).let {
                it.statusCode shouldBe 1
                it.stderr shouldContain message
            }
        }
        runBlocking { registry.authenticate(old)?.slug } shouldBe yaris
        runBlocking { registry.authenticate("bears-outback")?.slug } shouldBe Slug.parse("outback")
    }

    @Test
    fun `set-token from a file refuses one others can read`() {
        run("add-car yaris --name Yaris")
        val file = java.nio.file.Files.createTempFile("token", ".txt")
        try {
            java.nio.file.Files.writeString(file, "bears-yaris-15\n")
            java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            run("set-token yaris --token-file $file").let {
                it.statusCode shouldBe 1
                it.stderr shouldContain "chmod 600"
            }
            runBlocking { registry.authenticate("bears-yaris-15") }.shouldBeNull()
            java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            run("set-token yaris --token-file $file").statusCode shouldBe 0
            runBlocking { registry.authenticate("bears-yaris-15")?.slug } shouldBe yaris
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `add-car --choose-token never shows a generated token`() {
        val result = run("add-car yaris --name Yaris --choose-token", FakeIo(secrets = listOf("bears-yaris-15", "bears-yaris-15")))
        result.statusCode shouldBe 0
        tokenCount(result.stdout) shouldBe 0
        result.stdout shouldContain "Added yaris (Yaris), with your token (ends …-15)."
        runBlocking { registry.authenticate("bears-yaris-15")?.slug } shouldBe yaris
    }

    @Test
    fun `add-car --choose-token with a bad or taken token leaves no car`() {
        run("add-car yaris --name Yaris --choose-token", FakeIo(secrets = listOf("short", "short"))).statusCode shouldBe 1
        runBlocking { registry.get(yaris) }.shouldBeNull()
        run("add-car outback --name Outback --choose-token", FakeIo(secrets = listOf("bears-15-x", "bears-15-x")))
        run("add-car yaris --name Yaris --choose-token", FakeIo(secrets = listOf("bears-15-x", "bears-15-x"))).let {
            it.statusCode shouldBe 1
            it.stderr shouldContain "already outback's"
        }
        runBlocking { registry.get(yaris) }.shouldBeNull()
    }

    @Test
    fun `remove-car with the wrong slug typed changes nothing`() {
        val token = tokenIn(run("add-car yaris --name Yaris").stdout)
        message("m_1", "yaris")
        val result = run("remove-car yaris", FakeIo(lines = listOf("yaris2")))
        result.statusCode shouldBe 1
        result.stderr shouldContain "Not removed."
        runBlocking { registry.authenticate(token)?.slug } shouldBe yaris
        runBlocking { messages.get("m_1") }.shouldNotBeNull()
    }

    @Test
    fun `remove-car with the slug typed removes it, revokes the token, and deletes its messages only`() {
        val token = tokenIn(run("add-car yaris --name Yaris").stdout)
        message("m_1", "yaris")
        message("m_2", "yaris")
        message("m_3", "outback")
        run("remove-car yaris", FakeIo(lines = listOf("yaris"))).let {
            it.statusCode shouldBe 0
            it.stdout shouldContain "Removed yaris, and its 2 message(s)."
        }
        runBlocking { registry.authenticate(token) }.shouldBeNull()
        runBlocking { messages.recent("yaris", 10) } shouldHaveSize 0
        runBlocking { messages.get("m_3") }.shouldNotBeNull()
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
    fun `delete-session refuses an upload still going, before asking`() {
        run("add-car yaris --name Yaris")
        runBlocking { ArchiveService(sessions, segments).open("yaris", sessionId, line0.toByteArray()) } // stamped now
        val io = FakeIo(lines = listOf(sessionId))
        run("delete-session $sessionId", io).let {
            it.statusCode shouldBe 1
            it.stderr shouldContain "still uploading"
        }
        io.prompts shouldBe emptyList()
        runBlocking { sessions.get(sessionId) } shouldNotBe null
        run("delete-session 00000000-0000-4000-8000-000000000000").stderr shouldContain "No session"
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

    @Test
    fun `import-course saves a course from its file, each run the next version`() {
        val seed = java.io.File("../courses/seed/nhms.geojson").absolutePath
        val first = run("import-course $seed --name \"New Hampshire Motor Speedway\"")
        first.statusCode shouldBe 0
        first.output shouldContain "Saved nhms (New Hampshire Motor Speedway) as version 1: road (default), 0 sector(s)"
        run("import-course $seed").output shouldContain "as version 2" // the id from the file's name, the name from the GeoJSON
        runBlocking { courses.get("nhms")!!.name } shouldBe "New Hampshire Motor Speedway"
    }

    @Test
    fun `import-course refuses an invalid course, saying every problem, and saves nothing`() {
        val bad = kotlin.io.path.createTempFile("bad", ".geojson").toFile().apply {
            writeText("""{"type":"FeatureCollection","features":[]}""")
            deleteOnExit()
        }
        val result = run("import-course ${bad.absolutePath} --id home-loop --name Loop")
        result.statusCode shouldBe 1
        result.output shouldContain "a course needs at least one layout"
        runBlocking { courses.current() } shouldBe emptyList()
    }

    @Test
    fun `remove-course removes a course and its re-timings, only if typed again, never one in use`() {
        val seed = java.io.File("../courses/seed/nhms.geojson").absolutePath
        run("import-course $seed")
        run("import-course $seed --id nhms-oval --name Oval")
        runBlocking {
            archive.putDerived("s1", "timing-v1-nhms-2.json.gz", ByteArray(1))
            archive.putDerived("s1", "timing-v1-nhms-oval-1.json.gz", ByteArray(1))
            sessions.create(com.obd2dashboard.backend.archive.SessionRecord("s1", "yaris", null, null, 0, emptyList(), true, null, 0, Instant.EPOCH, Instant.EPOCH))
        }
        run("remove-course nhms", FakeIo(lines = listOf("nope"))).output shouldContain "Not removed."
        run("remove-course nhms", FakeIo(lines = listOf("nhms"))).output shouldContain "Removed nhms, and 1 re-timing(s)."
        runBlocking { courses.get("nhms") } shouldBe null
        segments.objects.keys.filter { "timing-" in it } shouldBe listOf("sessions/s1/timing-v1-nhms-oval-1.json.gz")
        run("remove-course nhms").statusCode shouldBe 1

        // Laps a tablet timed there: it stays.
        runBlocking { sessions.setSummary("s1", com.obd2dashboard.backend.archive.SessionReader().apply {
            read("""{"type":"lap","course":"nhms-oval","lap":1,"time":90.0,"seq":1,"at":1}""".byteInputStream())
        }.summary()) }
        val io = FakeIo(lines = listOf("nhms-oval"))
        run("remove-course nhms-oval", io).output shouldContain "so it stays"
        io.prompts shouldBe emptyList()
    }
}
