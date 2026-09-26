package com.obd2dashboard.backend.tools

import com.github.ajalt.clikt.core.CliktError

/**
 * What the admin tool reads from the person running it.
 *
 * An interface because the real source, the terminal, does not exist under
 * tests or under Gradle, and a passcode must never be read from anywhere it
 * would echo.
 */
interface AdminIo {
    /** A line of input after [prompt], or null at end of input. */
    fun readLine(prompt: String): String?

    /** A passcode after [prompt], not echoed. The caller clears it. */
    fun readSecret(prompt: String): CharArray
}

/** The terminal. Refuses to read a passcode without one rather than echo it. */
object ConsoleIo : AdminIo {
    override fun readLine(prompt: String): String? {
        print(prompt)
        System.out.flush()
        return readlnOrNull()
    }

    override fun readSecret(prompt: String): CharArray {
        val console = System.console()
            ?: throw CliktError("Reading a passcode needs a terminal. Run this through scripts/admin.sh, in a terminal.")
        return console.readPassword(prompt) ?: CharArray(0)
    }
}
