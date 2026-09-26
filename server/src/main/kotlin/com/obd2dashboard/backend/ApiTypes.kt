package com.obd2dashboard.backend

import kotlinx.serialization.Serializable

/**
 * The body of every `4xx` the tablet can receive (contract §14.2).
 *
 * `error` is a stable code for the tablet to branch on; `message` is for a person.
 * `skipChunk` means something only on the archive lane (M3), and is false
 * everywhere else.
 */
@Serializable
data class ApiError(val error: String, val message: String, val skipChunk: Boolean = false)

/**
 * A car as anyone may see it: its slug and name, and nothing else.
 *
 * **Named fields, never a serialised `Car`**, which holds hashes. A test pins
 * this to exactly these two fields, so adding one is a decision (M2.5).
 */
@Serializable
data class PublicCar(val slug: String, val name: String)
