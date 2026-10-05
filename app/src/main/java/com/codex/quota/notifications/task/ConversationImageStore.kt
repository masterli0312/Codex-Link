package com.codex.quota.notifications.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Encrypted cache, bounded downloads and sampled decoding. No decoding or disk work on the UI thread. */
object ConversationImageStore {
    private val gate = Semaphore(3)
    private val imageLocks = Array(32) { Mutex() }
    private val diskLock = Mutex()
    private var requests: RemoteConversationClient? = null
    private fun requests(context: Context): RemoteConversationClient = synchronized(this) {
        requests ?: RemoteConversationClient(context.applicationContext).also { requests = it }
    }
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun load(context: Context, recordId: String, image: ConversationImageRef, large: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        val record = requireNotNull(TaskInbox(context).read(recordId))
        val connection = requireNotNull(ComputerConnectionStore(context).find(record))
        val thread = requireNotNull(record.snapshot.thread_ref ?: record.snapshot.remote_ref).thread_id
        require(image.id.matches(Regex("[a-f0-9]{64}")))
        // Include pairing secret in the hashed cache identity, so a re-pair cannot read an old image.
        val cacheId = TaskInbox.hash(connection.endpoint + ":" + connection.hostId + ":" + thread + ":" + image.id + ":" + connection.key)
        val memoryId = cacheId + if (large) ":large" else ":small"
        memory[memoryId]?.let { return@withContext it }
        imageLocks[cacheId.hashCode() and 31].withLock { gate.withPermit {
            memory[memoryId]?.let { return@withPermit it }
            val root = File(context.noBackupFilesDir, "conversation-images").apply { mkdirs() }
            val cache = File(root, "$cacheId.bin")
            fun open(bytes: ByteArray) = ConversationImageCrypto.decrypt(bytes, connection.key, connection.hostId, thread, image.id)
            var plain = if (cache.isFile && cache.length() in 29..ConversationImageRules.MAX_BYTES.toLong() + 28)
                runCatching { open(AtomicFile(cache).readFully()) }.getOrNull() else null
            if (plain == null) {
                val encrypted = ConversationImageTransfer.retry {
                    val (current, download) = requests(context).image(recordId, image.id)
                    require(current.key == connection.key && current.hostId == connection.hostId)
                    val url = requireNotNull(RelayRouting.attachmentUrl(context, connection.endpoint, download.url))
                    val encrypted = ConversationImageTransfer.download(http, url, download.size + 28)
                    plain = open(encrypted)
                    require(plain!!.size.toLong() == download.size && MessageDigest.getInstance("SHA-256").digest(plain)
                        .joinToString("") { "%02x".format(it) } == download.sha256)
                    encrypted
                }
                // A corrupt response is never cached. Keep at most 64 MiB / seven days on disk.
                diskLock.withLock {
                    var total = 0L
                    root.listFiles().orEmpty().filter { it.isFile && it.extension == "bin" }.sortedByDescending { it.lastModified() }.forEach { file ->
                        val keep = System.currentTimeMillis() - file.lastModified() <= 7 * 86400_000L &&
                            total + file.length() + encrypted.size <= 64 * 1024 * 1024L
                        if (keep) total += file.length() else file.delete()
                    }
                    val atomic = AtomicFile(cache)
                    val output = atomic.startWrite()
                    try { output.write(encrypted); atomic.finishWrite(output) }
                    catch (e: Exception) { atomic.failWrite(output); throw e }
                }
            }
            val bytes = requireNotNull(plain)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000)
            var sample = 1
            val maxSide = if (large) 4096 else 640
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide ||
                bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 8_000_000) sample *= 2
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
            require(ComputerConnectionStore(context).find(record)?.key == connection.key)
            memory.put(memoryId, bitmap)
            bitmap
        } }
    }

    /** Return the unchanged original bytes, usually from the same encrypted preview cache. */
    suspend fun original(context: Context, recordId: String, image: ConversationImageRef): ByteArray = withContext(Dispatchers.IO) {
        val record = requireNotNull(TaskInbox(context).read(recordId))
        val connection = requireNotNull(ComputerConnectionStore(context).find(record))
        val thread = requireNotNull(record.snapshot.thread_ref ?: record.snapshot.remote_ref).thread_id
        require(image.id.matches(Regex("[a-f0-9]{64}")))
        val cacheId = TaskInbox.hash(connection.endpoint + ":" + connection.hostId + ":" + thread + ":" + image.id + ":" + connection.key)
        val cache = File(context.noBackupFilesDir, "conversation-images/$cacheId.bin")
        val cached = imageLocks[cacheId.hashCode() and 31].withLock {
            if (cache.isFile && cache.length() in 29..ConversationImageRules.MAX_BYTES.toLong() + 28)
                runCatching { ConversationImageCrypto.decrypt(AtomicFile(cache).readFully(), connection.key, connection.hostId, thread, image.id) }.getOrNull()
            else null
        }
        val bytes = cached ?: run {
            val (current, download) = requests(context).image(recordId, image.id)
            require(current.key == connection.key && current.hostId == connection.hostId)
            val url = requireNotNull(RelayRouting.attachmentUrl(context, connection.endpoint, download.url))
            val encrypted = ConversationImageTransfer.download(http, url, download.size + 28)
            val plain = ConversationImageCrypto.decrypt(encrypted, connection.key, connection.hostId, thread, image.id)
            require(plain.size.toLong() == download.size && MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) } == download.sha256)
            plain
        }
        require(ComputerConnectionStore(context).find(record)?.key == connection.key)
        bytes
    }
}
