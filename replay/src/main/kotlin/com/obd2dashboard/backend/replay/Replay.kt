package com.obd2dashboard.backend.replay

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    Replay(System.getenv("OBD2_TOKEN")).main(args)
    exitProcess(0)
}

/**
 * Plays session logs into the server as the tablet would (contract §6).
 *
 * The token comes from `OBD2_TOKEN` or `--token-file`, **never an argument**,
 * where it would land in shell history and transcripts.
 */
class Replay(private val envToken: String?) : CliktCommand(name = "replay") {
    private val server by option("--server", help = "e.g. https://obd2-backend-….run.app").required()
    private val tokenFile by option("--token-file", help = "a file holding the car's token; else OBD2_TOKEN").path(mustExist = true)
    private val workDir by option("--work-dir", help = "where positions and upgraded logs are kept")
        .path().default(Path.of("build/replay"))
    private val chunkMinutes by option("--chunk-minutes", help = "log time per chunk (the tablet's is 2)").double().default(2.0)
    private val chunkLines by option("--chunk-lines", help = "at most this many lines per chunk").int().default(2_000)
    private val speed by option("--speed", help = "0 = as fast as possible, 1 = real time").double().default(0.0)
    private val loseResponses by option("--lose-responses", help = "chance of losing an answer and resending").double().default(0.0)
    private val duplicate by option("--duplicate", help = "chance of sending an acknowledged chunk again").double().default(0.0)
    private val stopAfter by option("--stop-after", help = "stop after this many chunks, keeping the position").int()
    private val fresh by option("--fresh", help = "forget saved positions and start each session from line 1").flag()
    private val seed by option("--seed", help = "for repeatable faults").long()
    private val files by argument(help = "session logs, .jsonl or .jsonl.gz").path(mustExist = true).multiple(required = true)

    override fun help(context: Context) = "Upload session logs as the tablet does, faults included."

    override fun run() = runBlocking {
        val token = (tokenFile?.let { Files.readString(it).trim() } ?: envToken?.trim())
            ?.takeIf { it.isNotEmpty() }
            ?: throw CliktError("No token: set OBD2_TOKEN or pass --token-file.")
        val tokenSeed = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        val device = UUID.nameUUIDFromBytes("replay-device".toByteArray()).toString()
        val options = ReplayOptions(
            chunkMillis = (chunkMinutes * 60_000).toLong(),
            chunkLines = chunkLines,
            speed = speed,
            loseResponses = loseResponses,
            duplicate = duplicate,
            stopAfter = stopAfter,
            random = seed?.let { Random(it) } ?: Random.Default,
        )
        var failed = false
        var stopped = false
        for (path in files) {
            val file = SessionFile.load(path, tokenSeed, device)
            if (fresh) Files.deleteIfExists(workDir.resolve("${file.id}.state.json"))
            echo("${path.fileName}: session ${file.id}, ${file.lines.size} lines")
            val replayer = Replayer(URI.create(server.trimEnd('/')), token, workDir, options, log = { echo("  $it") })
            when (val result = replayer.replay(file)) {
                is ReplayResult.Completed -> echo("  COMPLETE  sha256 ${result.sha256}  (upgraded file: ${workDir.resolve("${file.id}.v3.jsonl")})")
                is ReplayResult.Stopped -> { echo("  STOPPED at line ${result.ackedThrough}; run again to resume"); stopped = true }
                is ReplayResult.Failed -> { echo("  FAILED: ${result.reason}", err = true); failed = true }
            }
        }
        if (failed) throw CliktError("Some sessions failed.", statusCode = 1)
        if (stopped) throw CliktError("Stopped as asked; positions are saved.", statusCode = 3)
    }
}
