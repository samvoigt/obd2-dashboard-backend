package com.obd2dashboard.backend

import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import java.security.MessageDigest

const val TABLET_AUTH = "tablet"

/**
 * The tablet sends one shared key as `Authorization: Bearer <key>` — decision 4.
 * **Retired in M2.6** by [carTokens], when the per-car tokens are deployed.
 *
 * Compared in constant time so response timing says nothing about how much of a
 * guessed key was right.
 */
fun AuthenticationConfig.tabletKey(tabletKey: String) {
    val expected = tabletKey.toByteArray()
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
