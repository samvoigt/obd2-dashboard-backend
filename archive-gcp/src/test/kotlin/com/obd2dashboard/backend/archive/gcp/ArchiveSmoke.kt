package com.obd2dashboard.backend.archive.gcp

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.StorageOptions
import com.obd2dashboard.backend.archive.ArchiveService
import com.obd2dashboard.backend.archive.LineBlock
import com.obd2dashboard.backend.archive.LineHash
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * The archive against the **real** bucket and database, then removed.
 *
 * Not a unit test: it needs credentials and live services, so it runs by hand
 * through `scripts/archive-smoke.sh`. It checks what the fakes cannot: the
 * object encoding, the transactions, and that a download is the exact file.
 */
fun main(args: Array<String>) {
    val (project, bucket) = args.toList().let { (it.getOrNull(0).orEmpty()) to (it.getOrNull(1).orEmpty()) }
    if (project.isBlank() || bucket.isBlank()) {
        System.err.println("usage: smoke <gcp project> <bucket>")
        exitProcess(2)
    }
    val index = FirestoreSessionIndex.connect(project)
    val store = GcsSegmentStore.connect(project, bucket)
    val archive = ArchiveService(index, store)
    val id = UUID.randomUUID().toString()
    val car = "smoke-archive"

    // The fixture with a fresh id, so runs never collide; the hash is over what is sent.
    val fixture = ArchiveSmoke::class.java.getResourceAsStream("/session-v3.jsonl")!!.use { it.readBytes() }
        .decodeToString().replace("7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f", id).toByteArray()
    val lines = (LineBlock.split(fixture) as LineBlock.Split.Ok).lines
    val sha = LineHash().also { it.addLines(fixture) }.hex()
    fun chunk(from: Int, count: Int) = (LineBlock.split(
        (from until from + count).fold(ByteArray(0)) { acc, i -> acc + lines.line(i) + '\n'.code.toByte() },
    ) as LineBlock.Split.Ok).lines

    var failures = 0
    fun check(what: String, ok: Boolean) {
        println((if (ok) "  ok    " else "  FAIL  ") + what)
        if (!ok) failures++
    }

    runBlocking {
        println("Archive smoke test: project $project, bucket $bucket, session $id")
        try {
            check("open", archive.open(car, id, lines.line(0)) == ArchiveService.Open.Created(0))
            check("reopen", archive.open(car, id, lines.line(0)) == ArchiveService.Open.Existing(0))
            check("chunk 1..20", archive.append(car, id, 1, chunk(1, 20)) == ArchiveService.Append.Acked(20))
            check("duplicate", archive.append(car, id, 5, chunk(5, 10)) == ArchiveService.Append.Acked(20))
            check("gap", archive.append(car, id, 30, chunk(30, 5)) == ArchiveService.Append.Gap(21))
            check("overlap 15..", archive.append(car, id, 15, chunk(15, lines.size - 15)) ==
                ArchiveService.Append.Acked(lines.size - 1L))
            check("wrong car", archive.append("other", id, 1, chunk(1, 1)) == ArchiveService.Append.WrongCar)
            check("index holds the segments", index.get(id)?.segments?.map { it.first } == listOf(0L, 1L, 21L))
            val streamed = java.io.ByteArrayOutputStream()
            archive.read(index.get(id)!!) { it.copyTo(streamed) }
            check("segments stream back in order (M7.1)", streamed.toByteArray().contentEquals(fixture))

            check("complete", archive.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) == ArchiveService.Complete.Done)
            check("complete again: answered at once, nothing done again (M17.1)", archive.complete(car, id, lines.size - 1L, lines.size.toLong(), sha) == ArchiveService.Complete.AlreadyDone)
            check("answered by the running hash: its segments listed until finished (M19.3)", index.get(id)?.segments?.size == 3)
            check("finished: composed into one object on the store's side (M19.3)", archive.finish(id))
            check("only the session object remains", store.list("sessions/$id/") == listOf(ArchiveService.sessionKey(id)))
            val summary = archive.summary(id)
            check("the summary is built from the stored log (M7.1)", summary?.lines == lines.size.toLong() && summary.unreadable == 0)
            check("and kept in Firestore", index.get(id)?.summary == summary)
            // A later whole-document write (the clock offset, M18.1) keeps the summary; the smaller offset is kept.
            check("the clock offset stored (M18.1)", archive.setClockOffset(id, 20_000) && index.get(id)?.clockOffsetMs == 20_000L)
            check("a larger one later keeps the smaller", archive.setClockOffset(id, 50_000) && index.get(id)?.clockOffsetMs == 20_000L)
            check("a later write keeps the summary", index.get(id)?.summary == summary)

            // The raw download, not through the store: it must be the gzip file itself.
            val storage = StorageOptions.newBuilder().setProjectId(project).build().service
            val blob = storage.get(BlobId.of(bucket, ArchiveService.sessionKey(id)))
            check("stored as application/gzip, no content-encoding", blob.contentType == "application/gzip" && blob.contentEncoding == null)
            val downloaded = GZIPInputStream(ByteArrayInputStream(blob.getContent())).use { it.readBytes() }
            check("the download is the fixture, byte for byte (three gzip members, composed)", downloaded.contentEquals(fixture))
            val rawRead = store.readRaw(ArchiveService.sessionKey(id)) { it.readBytes() }
            check("readRaw is the object as stored, gzip (M7.3)", rawRead.contentEquals(storage.readAllBytes(blob.blobId)) &&
                java.util.zip.GZIPInputStream(rawRead.inputStream()).readBytes().contentEquals(fixture))

            // M19.2: one-line chunks, as the tablet's many small ones, compacted into a piece and read back.
            val id2 = UUID.randomUUID().toString()
            val fixture2 = fixture.decodeToString().replace(id, id2).toByteArray()
            val lines2 = (LineBlock.split(fixture2) as LineBlock.Split.Ok).lines
            fun one(i: Int) = (LineBlock.split(lines2.line(i) + '\n'.code.toByte()) as LineBlock.Split.Ok).lines
            try {
                archive.open(car, id2, lines2.line(0))
                for (i in 1 until lines2.size) archive.append(car, id2, i.toLong(), one(i))
                check("compacted: ${ArchiveService.COMPACT_AT} segments after line 0 composed into one piece (M19.2)", archive.compact(id2))
                check("the index lists line 0, the piece and the rest", index.get(id2)?.segments?.size == lines2.size - 32 + 1)
                val back = java.io.ByteArrayOutputStream().also { out -> archive.read(index.get(id2)!!) { it.copyTo(out) } }.toByteArray()
                check("the piece reads back through its gzip members, byte for byte", back.contentEquals(fixture2))
                val sha2 = LineHash().also { it.addLines(fixture2) }.hex()
                check("complete and finish", archive.complete(car, id2, lines2.size - 1L, lines2.size.toLong(), sha2) == ArchiveService.Complete.Done && archive.finish(id2))
                val whole = java.io.ByteArrayOutputStream().also { out -> archive.read(index.get(id2)!!) { it.copyTo(out) } }.toByteArray()
                check("one object, byte for byte, and nothing else left", whole.contentEquals(fixture2) && store.list("sessions/$id2/") == listOf(ArchiveService.sessionKey(id2)))
            } finally {
                archive.delete(id2)
            }

            // SegmentStore.write's rule: a body that throws creates no object.
            val failedKey = "sessions/$id/should-not-exist.jsonl.gz"
            runCatching { store.write(failedKey) { out -> out.write(ByteArray(1000)); error("refused mid-write") } }
            check("a write whose body throws creates no object", store.list(failedKey).isEmpty())
        } finally {
            archive.delete(id)
        }
        check("deleted: no objects", store.list("sessions/$id/").isEmpty())
        check("deleted: no index entry", index.get(id) == null)
    }
    println(if (failures == 0) "PASSED" else "FAILED: $failures check(s)")
    exitProcess(if (failures == 0) 0 else 1)
}

private object ArchiveSmoke
