package com.obd2dashboard.backend

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import java.security.MessageDigest

const val TABLET_AUTH = "tablet"

/**
 * The tablet sends one shared key as `Authorization: Bearer <key>` — decision 4.
 *
 * Compared in constant time so response timing says nothing about how much of a
 * guessed key was right.
 */
fun Application.installTabletAuth(tabletKey: String) {
    val expected = tabletKey.toByteArray()
    install(Authentication) {
        bearer(TABLET_AUTH) {
            realm = "tablet"
            authenticate { credential ->
                if (MessageDigest.isEqual(credential.token.toByteArray(), expected)) {
                    UserIdPrincipal("tablet")
                } else {
                    null
                }
            }
        }
    }
}
