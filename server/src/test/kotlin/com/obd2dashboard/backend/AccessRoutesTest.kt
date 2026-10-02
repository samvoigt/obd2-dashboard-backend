package com.obd2dashboard.backend

import com.obd2dashboard.backend.admin.Access
import com.obd2dashboard.backend.admin.InMemoryAccessStore
import com.obd2dashboard.backend.admin.InMemoryUserStore
import com.obd2dashboard.backend.admin.Kind
import com.obd2dashboard.backend.admin.Thing
import com.obd2dashboard.backend.admin.User
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.InMemorySegmentStore
import com.obd2dashboard.backend.archive.InMemorySessionIndex
import com.obd2dashboard.backend.archive.SessionRecord
import com.obd2dashboard.backend.live.InMemoryLiveHub
import com.obd2dashboard.backend.live.InMemoryMessageStore
import com.obd2dashboard.backend.live.Messages
import com.obd2dashboard.backend.registry.CarRegistry
import com.obd2dashboard.backend.registry.InMemoryCarStore
import com.obd2dashboard.backend.registry.Slug
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Users (M23.3–5): who signs in, who may do what to a car and its sessions,
 * sharing, and inviting. Sam is the master admin; Ann made a car and shared
 * it with Ed; Olga is a user with nothing; a stranger was never invited.
 */
class AccessRoutesTest {
    private val registry = CarRegistry(InMemoryCarStore(), passcodeIterations = 1_000)
    private val sessions = InMemorySessionIndex()
    private val users = InMemoryUserStore()
    private val access = InMemoryAccessStore()
    private val config = AdminConfig(
        googleClientId = "client-id",
        allowlist = Allowlist.parse("sam@example.com"),
        // Google says who someone is; whether they may sign in is the server's question.
        identity = IdentityVerifier { SignIn.Allowed("$it@example.com") },
    )

    init {
        runBlocking {
            for (name in listOf("ann", "ed", "olga")) users.add(User("$name@example.com", "sam@example.com", Instant.EPOCH))
        }
    }

    private fun ApplicationTestBuilder.app() {
        application {
            module(registry, ArchiveService(sessions, InMemorySegmentStore()), InMemoryLiveHub(),
                messages = Messages(InMemoryMessageStore()), courses = testCourses(), events = testEvents(), crewKey = testCrewKey(),
                admin = config, users = users, access = access)
        }
    }

    private suspend fun ApplicationTestBuilder.login(name: String): HttpResponse = client.post("/api/admin/login") {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, "http://localhost")
        contentType(ContentType.Application.Json)
        setBody("""{"credential":"$name"}""")
    }

    private suspend fun ApplicationTestBuilder.signIn(name: String): String = login(name).headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, cookie: String?, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = method
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            cookie?.let { header(HttpHeaders.Cookie, it) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private fun parse(response: HttpResponse): JsonElement = runBlocking { Json.parseToJsonElement(response.bodyAsText()) }

    private fun slugs(response: HttpResponse) = parse(response).jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }

    @Test
    fun `a master admin and invited users sign in, with their role, and a stranger can't`() = testApplication {
        app()
        parse(login("sam")).jsonObject["role"]!!.jsonPrimitive.content shouldBe "master"
        parse(login("ann")).jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
        val stranger = login("stranger")
        stranger.status shouldBe HttpStatusCode.Unauthorized
        stranger.headers[HttpHeaders.SetCookie] shouldBe null
        val ann = signIn("ann")
        parse(call(HttpMethod.Get, "/api/admin/me", ann)).jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
    }

    @Test
    fun `a car is its creator's and its editors' to change, and only the creator shares or deletes it`() = testApplication {
        app()
        val (sam, ann, ed, olga) = listOf("sam", "ann", "ed", "olga").map { signIn(it) }
        call(HttpMethod.Post, "/api/admin/cars", ann, """{"slug":"anncar","name":"Ann's"}""").status shouldBe HttpStatusCode.Created
        access.get(Thing(Kind.CAR, "anncar")) shouldBe Access("ann@example.com")

        // Olga, and Ed before he's added, can neither see nor change it.
        for (who in listOf(olga, ed)) {
            slugs(call(HttpMethod.Get, "/api/admin/cars", who)) shouldBe emptyList()
            call(HttpMethod.Patch, "/api/admin/cars/anncar", who, """{"name":"Mine"}""").status shouldBe HttpStatusCode.Forbidden
            call(HttpMethod.Get, "/api/admin/cars/anncar/sessions", who).status shouldBe HttpStatusCode.Forbidden
            call(HttpMethod.Post, "/api/admin/cars/anncar/token", who, "{}").status shouldBe HttpStatusCode.Forbidden
        }

        call(HttpMethod.Put, "/api/admin/access/car/anncar", ann, """{"editors":["Ed@Example.com"]}""").status shouldBe HttpStatusCode.OK
        // Ed edits now: renames it, replaces its token, sees its sessions; but neither shares nor deletes it.
        call(HttpMethod.Patch, "/api/admin/cars/anncar", ed, """{"name":"Ed's too"}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Post, "/api/admin/cars/anncar/token", ed, "{}").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Get, "/api/admin/cars/anncar/sessions", ed).status shouldBe HttpStatusCode.OK
        val edsView = parse(call(HttpMethod.Get, "/api/admin/cars", ed)).jsonArray.single().jsonObject["access"]!!.jsonObject
        edsView["creator"]!!.jsonPrimitive.content shouldBe "ann@example.com"
        edsView["canShare"]!!.jsonPrimitive.boolean shouldBe false
        edsView["canDelete"]!!.jsonPrimitive.boolean shouldBe false
        call(HttpMethod.Put, "/api/admin/access/car/anncar", ed, """{"editors":["olga@example.com"]}""").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Delete, "/api/admin/cars/anncar", ed).status shouldBe HttpStatusCode.Forbidden

        // Sam sees it and could do anything; Ann deletes it, and its record goes.
        parse(call(HttpMethod.Get, "/api/admin/cars", sam)).jsonArray.single().jsonObject["access"]!!.jsonObject["canDelete"]!!.jsonPrimitive.boolean shouldBe true
        call(HttpMethod.Delete, "/api/admin/cars/anncar", ann).status shouldBe HttpStatusCode.OK
        access.get(Thing(Kind.CAR, "anncar")) shouldBe null
    }

    @Test
    fun `a car made before M23 has no record, so it's the master admin's alone`() = testApplication {
        app()
        registry.addCar(Slug.parse("yaris"), "Yaris")
        val (sam, ann) = listOf("sam", "ann").map { signIn(it) }
        slugs(call(HttpMethod.Get, "/api/admin/cars", ann)) shouldBe emptyList()
        call(HttpMethod.Patch, "/api/admin/cars/yaris", ann, """{"name":"X"}""").status shouldBe HttpStatusCode.Forbidden
        val samsView = parse(call(HttpMethod.Get, "/api/admin/cars", sam)).jsonArray.single().jsonObject["access"]!!.jsonObject
        samsView["creator"] shouldBe JsonNull
        samsView["canShare"]!!.jsonPrimitive.boolean shouldBe true
        // Sam shares it: it gets a record with no creator, and Ann may edit it, but not delete it.
        call(HttpMethod.Put, "/api/admin/access/car/yaris", sam, """{"editors":["ann@example.com"]}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Patch, "/api/admin/cars/yaris", ann, """{"name":"Ann's Yaris"}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Delete, "/api/admin/cars/yaris", ann).status shouldBe HttpStatusCode.Forbidden
    }

    @Test
    fun `a car's editors name its sessions, and only its creator deletes them`() = testApplication {
        app()
        val (ann, ed, olga) = listOf("ann", "ed", "olga").map { signIn(it) }
        call(HttpMethod.Post, "/api/admin/cars", ann, """{"slug":"anncar","name":"Ann's"}""")
        call(HttpMethod.Put, "/api/admin/access/car/anncar", ann, """{"editors":["ed@example.com"]}""")
        val id = "7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f"
        val old = Instant.parse("2026-09-01T00:00:00Z")
        sessions.create(SessionRecord(id, "anncar", null, null, -1, emptyList(), true, null, 0, old, old))
        call(HttpMethod.Put, "/api/admin/sessions/$id/name", olga, """{"name":"Mine"}""").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Put, "/api/admin/sessions/$id/name", ed, """{"name":"Morning"}""").status shouldBe HttpStatusCode.OK
        call(HttpMethod.Delete, "/api/admin/sessions/$id", ed).status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Delete, "/api/admin/sessions/$id", olga).status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Delete, "/api/admin/sessions/$id", ann).status shouldBe HttpStatusCode.NoContent
    }

    @Test
    fun `editors are invited users, never the creator or a master admin`() = testApplication {
        app()
        val ann = signIn("ann")
        call(HttpMethod.Post, "/api/admin/cars", ann, """{"slug":"anncar","name":"Ann's"}""")
        for (email in listOf("ann@example.com", "sam@example.com", "stranger@example.com")) {
            call(HttpMethod.Put, "/api/admin/access/car/anncar", ann, """{"editors":["$email"]}""").status shouldBe HttpStatusCode.BadRequest
        }
        val detail = parse(call(HttpMethod.Get, "/api/admin/access/car/anncar", ann)).jsonObject
        detail["creator"]!!.jsonPrimitive.content shouldBe "ann@example.com"
        detail["invitable"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("ed@example.com", "olga@example.com")
        call(HttpMethod.Get, "/api/admin/access/boat/x", ann).status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `a master admin invites and removes users, a removed user is signed out at once, and what they made stays`() = testApplication {
        app()
        val (sam, ann) = listOf("sam", "ann").map { signIn(it) }
        call(HttpMethod.Post, "/api/admin/cars", ann, """{"slug":"anncar","name":"Ann's"}""")

        call(HttpMethod.Post, "/api/admin/users", ann, """{"email":"pat@example.com"}""").status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Get, "/api/admin/users", ann).status shouldBe HttpStatusCode.Forbidden
        call(HttpMethod.Post, "/api/admin/users", sam, """{"email":" Pat@Example.com "}""").status shouldBe HttpStatusCode.Created
        call(HttpMethod.Post, "/api/admin/users", sam, """{"email":"pat@example.com"}""").status shouldBe HttpStatusCode.Conflict
        call(HttpMethod.Post, "/api/admin/users", sam, """{"email":"sam@example.com"}""").status shouldBe HttpStatusCode.BadRequest
        call(HttpMethod.Post, "/api/admin/users", sam, """{"email":"not an email"}""").status shouldBe HttpStatusCode.BadRequest
        parse(login("pat")).jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"

        val listed = parse(call(HttpMethod.Get, "/api/admin/users", sam)).jsonArray.associate {
            it.jsonObject["email"]!!.jsonPrimitive.content to it.jsonObject["created"]!!.jsonArray.map { c -> c.jsonPrimitive.content }
        }
        listed["ann@example.com"] shouldBe listOf("car:anncar")
        listed["pat@example.com"] shouldBe emptyList()

        call(HttpMethod.Delete, "/api/admin/users/ann@example.com", sam).status shouldBe HttpStatusCode.NoContent
        call(HttpMethod.Get, "/api/admin/me", ann).status shouldBe HttpStatusCode.Unauthorized
        call(HttpMethod.Patch, "/api/admin/cars/anncar", ann, """{"name":"Still mine?"}""").status shouldBe HttpStatusCode.Unauthorized
        access.get(Thing(Kind.CAR, "anncar"))?.creator shouldBe "ann@example.com"
        call(HttpMethod.Delete, "/api/admin/users/ann@example.com", sam).status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `reads need no Origin, as a browser sends none on a same-origin GET, and changes still do`() = testApplication {
        app()
        val ann = signIn("ann")
        val sam = signIn("sam")
        call(HttpMethod.Post, "/api/admin/cars", ann, """{"slug":"anns-car","name":"Ann's car"}""").status shouldBe HttpStatusCode.Created
        suspend fun bare(method: HttpMethod, path: String, cookie: String) = client.request(path) {
            this.method = method
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Cookie, cookie)
        }
        bare(HttpMethod.Get, "/api/admin/access/car/anns-car", ann).status shouldBe HttpStatusCode.OK // SHARE, read
        bare(HttpMethod.Get, "/api/admin/users", sam).status shouldBe HttpStatusCode.OK // INVITE, read
        bare(HttpMethod.Get, "/api/admin/cars/anns-car/sessions", ann).status shouldBe HttpStatusCode.OK // EDIT, read
        bare(HttpMethod.Delete, "/api/admin/cars/anns-car", ann).status shouldBe HttpStatusCode.Forbidden // a change, no Origin
    }
}
