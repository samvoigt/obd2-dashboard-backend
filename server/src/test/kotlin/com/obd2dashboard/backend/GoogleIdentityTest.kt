package com.obd2dashboard.backend

import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.api.client.json.webtoken.JsonWebToken
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The real verification, against a key set of our own served locally (M6.2):
 * Google's library fetches it by URL, as it fetches Google's.
 */
class GoogleIdentityTest {
    private val now = Instant.parse("2026-09-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ours = keyPair()
    private val stranger = keyPair()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/certs") { exchange ->
            val body = jwks(ours, KID).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val identity = GoogleIdentity(
        CLIENT_ID,
        Allowlist.parse(" Sam@Example.com , crew@example.com"),
        clock,
        "http://127.0.0.1:${server.address.port}/certs",
    )

    @After
    fun stop() = server.stop(0)

    private fun token(
        keys: KeyPair = ours,
        kid: String = KID,
        audience: String = CLIENT_ID,
        issuer: String = "https://accounts.google.com",
        email: String? = "sam@example.com",
        verified: Any? = true,
        expires: Instant = now.plusSeconds(3600),
    ): String {
        val header = JsonWebSignature.Header().setAlgorithm("RS256").setKeyId(kid).setType("JWT")
        val payload = JsonWebToken.Payload()
            .setAudience(audience)
            .setIssuer(issuer)
            .setSubject("1234567890")
            .setIssuedAtTimeSeconds(now.epochSecond - 60)
            .setExpirationTimeSeconds(expires.epochSecond)
        email?.let { payload["email"] = it }
        verified?.let { payload["email_verified"] = it }
        return JsonWebSignature.signUsingRsaSha256(keys.private, GsonFactory.getDefaultInstance(), header, payload)
    }

    private fun verify(credential: String) = runBlocking { identity.verify(credential) }

    @Test
    fun `a good token from an allowed email is its email, lower-cased`() {
        verify(token()) shouldBe SignIn.Allowed("sam@example.com")
        verify(token(email = "SAM@example.com")) shouldBe SignIn.Allowed("sam@example.com")
        verify(token(issuer = "accounts.google.com")) shouldBe SignIn.Allowed("sam@example.com") // Google uses both
        verify(token(verified = "true")) shouldBe SignIn.Allowed("sam@example.com")
    }

    @Test
    fun `each refusal says why`() {
        verify("not a token") shouldBe SignIn.Refused(Refusal.Malformed)
        verify("") shouldBe SignIn.Refused(Refusal.Malformed)
        verify(token(keys = stranger)) shouldBe SignIn.Refused(Refusal.BadSignature)
        verify(token(kid = "someone-else")) shouldBe SignIn.Refused(Refusal.BadSignature)
        verify(token(expires = now.minusSeconds(3600))) shouldBe SignIn.Refused(Refusal.Expired)
        verify(token(audience = "another-client.apps.googleusercontent.com")) shouldBe SignIn.Refused(Refusal.WrongAudience)
        verify(token(issuer = "https://evil.example.com")) shouldBe SignIn.Refused(Refusal.WrongIssuer)
        verify(token(verified = false)) shouldBe SignIn.Refused(Refusal.EmailNotVerified)
        verify(token(verified = null)) shouldBe SignIn.Refused(Refusal.EmailNotVerified)
        verify(token(email = null)) shouldBe SignIn.Refused(Refusal.EmailNotVerified)
        verify(token(email = "someone@example.com")) shouldBe SignIn.Refused(Refusal.NotAllowed)
    }

    @Test
    fun `a token is refused as expired from the second it expires`() {
        verify(token(expires = now.plusSeconds(1))) shouldBe SignIn.Allowed("sam@example.com")
        verify(token(expires = now)) shouldBe SignIn.Refused(Refusal.Expired)
    }

    @Test
    fun `a token whose payload was changed after signing is refused`() {
        val good = token().split('.')
        val forged = token(email = "crew@example.com").split('.')[1]
        verify("${good[0]}.$forged.${good[2]}") shouldBe SignIn.Refused(Refusal.BadSignature)
    }

    @Test
    fun `the allowlist trims, ignores case, and is empty when unset`() {
        Allowlist.parse(" Sam@Example.com , crew@example.com").allows("sam@example.com") shouldBe true
        Allowlist.parse(" Sam@Example.com , crew@example.com").allows("CREW@example.com ") shouldBe true
        Allowlist.parse(null).allows("sam@example.com") shouldBe false
        Allowlist.parse("").allows("") shouldBe false
        Allowlist.parse(" , ").allows("") shouldBe false
    }

    private companion object {
        const val CLIENT_ID = "test-client.apps.googleusercontent.com"
        const val KID = "test-key-1"

        fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun jwks(keys: KeyPair, kid: String): String {
            val key = keys.public as RSAPublicKey
            val b64 = Base64.getUrlEncoder().withoutPadding()
            fun unsigned(bytes: ByteArray) = if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
            val n = b64.encodeToString(unsigned(key.modulus.toByteArray()))
            val e = b64.encodeToString(unsigned(key.publicExponent.toByteArray()))
            return """{"keys":[{"kty":"RSA","alg":"RS256","use":"sig","kid":"$kid","n":"$n","e":"$e"}]}"""
        }
    }
}
