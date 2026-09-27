package com.obd2dashboard.backend.archive.gcp

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.obd2dashboard.backend.archive.SegmentStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SegmentStore] on Cloud Storage.
 *
 * **Objects are gzip *files*** (`Content-Type: application/gzip`, no
 * content-encoding), not gzip-encoded objects: with `Content-Encoding: gzip`,
 * Cloud Storage decompresses on download, and `session.jsonl.gz` would arrive
 * as plain JSONL under a `.gz` name. A download here is exactly the file the
 * app itself writes.
 */
public class GcsSegmentStore(private val storage: Storage, private val bucket: String) : SegmentStore {
    override suspend fun put(key: String, lines: ByteArray) {
        withContext(Dispatchers.IO) { storage.create(info(key), gzip(lines)) }
    }

    override suspend fun read(key: String): ByteArray = withContext(Dispatchers.IO) {
        val bytes = storage.readAllBytes(BlobId.of(bucket, key))
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }

    override suspend fun <T> readStream(key: String, body: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        storage.reader(BlobId.of(bucket, key)).use { channel ->
            GZIPInputStream(Channels.newInputStream(channel), 64 * 1024).use { body(it) }
        }
    }

    /**
     * Streams into a resumable upload. **The object exists only once the channel
     * closes**, so if [body] throws, the channel is abandoned unclosed and no
     * object is created (`SegmentStore.write`'s rule).
     */
    override suspend fun write(key: String, body: suspend (OutputStream) -> Unit): Unit = withContext(Dispatchers.IO) {
        val channel = storage.writer(info(key))
        val gzip = GZIPOutputStream(Channels.newOutputStream(channel), 64 * 1024)
        body(gzip)
        gzip.close() // finishes the gzip stream and closes the channel, which creates the object
    }

    override suspend fun deletePrefix(prefix: String): Int = withContext(Dispatchers.IO) {
        val ids = storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll().map { it.blobId }
        if (ids.isNotEmpty()) storage.delete(ids)
        ids.size
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) { storage.delete(BlobId.of(bucket, key)) }
    }

    override suspend fun list(prefix: String): List<String> = withContext(Dispatchers.IO) {
        storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll().map { it.name }.sorted()
    }

    private fun info(key: String): BlobInfo =
        BlobInfo.newBuilder(BlobId.of(bucket, key)).setContentType("application/gzip").build()

    public companion object {
        /** A client for [projectId]'s [bucket], named explicitly, never guessed. */
        public fun connect(projectId: String, bucket: String): GcsSegmentStore {
            require(projectId.isNotBlank()) { "a Google Cloud project ID is required" }
            require(bucket.isNotBlank()) { "a bucket name is required" }
            return GcsSegmentStore(StorageOptions.newBuilder().setProjectId(projectId).build().service, bucket)
        }

        private fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    }
}
