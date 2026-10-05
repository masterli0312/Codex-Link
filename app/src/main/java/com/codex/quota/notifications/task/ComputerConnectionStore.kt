package com.codex.quota.notifications.task

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class ComputerConnection(val id: String, val hostId: String, val name: String, val endpoint: String,
    val key: String, val createdAtSeconds: Long, val legacy: Boolean = false, val lastProbeId: String = "",
    val lastProbeAt: Long = 0, val lastReachableAt: Long = 0, val reachable: Boolean = false, val checking: Boolean = false) {
    override fun toString() = "ComputerConnection(id=$id)" // Never expose pairing secrets through diagnostics.
    fun recentlyReachable(now: Long = System.currentTimeMillis()) = reachable && now - lastReachableAt in 0..60_000
    fun confirmedUnreachable(now: Long = System.currentTimeMillis()) = !checking && !reachable &&
        lastProbeAt > lastReachableAt && now - lastProbeAt in 0..60_000 && !recentlyReachable(now)
    fun observe(event: RemoteEvent, now: Long): ComputerConnection {
        if (event.host_id != hostId || now - event.at !in 0..30_000) return this
        if (reachable && now - lastReachableAt in 0 until 15_000) return this
        return copy(reachable = true, lastReachableAt = now)
    }
    fun startProbe(requestId: String, now: Long) = copy(lastProbeId = requestId, lastProbeAt = now, checking = true)
    fun endProbe(requestId: String, hostName: String?, now: Long): ComputerConnection {
        if (lastProbeId != requestId) return this
        return copy(reachable = hostName != null || recentlyReachable(now), checking = false,
            lastReachableAt = if (hostName != null) now else lastReachableAt,
            name = name.ifBlank { hostName?.takeIf { it.toByteArray().size <= 160 }.orEmpty() })
    }
    fun matches(record: TaskInboxRecord): Boolean {
        val host = record.snapshot.thread_ref?.host_id ?: record.snapshot.remote_ref?.host_id ?: record.remoteState?.command?.host_id ?: record.computerHostId
        return TaskInbox.hash(endpoint) == record.endpointHash && (hostId.isBlank() && legacy && host.isBlank() || host == hostId)
    }
}
@Serializable
private data class ComputerRegistry(val connections: List<ComputerConnection> = emptyList(), val legacyRemoved: Boolean = false,
    val legacyProbe: ComputerConnection? = null)

/** Each additional computer gets a distinct notification address and AES key, excluded from backups. */
class ComputerConnectionStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "remote-computers.json"))
    private val keys = TaskContentKeys(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = synchronized(lock) {
        caches.getOrPut(file.baseFile.absolutePath) { PairingRegistryCache(ComputerRegistry()) }
    }
    val readFailure get() = cache.unavailable
    private fun read(): ComputerRegistry = cache.read(file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
        // readFully restores AtomicFile's old backup before checking the actual bytes.
        val bytes = file.readFully()
        require(bytes.size <= 64_000)
        json.decodeFromString<ComputerRegistry>(requireNotNull(keys.decryptLocal(String(bytes, Charsets.UTF_8))))
    }.let { if (readFailure.value && it == ComputerRegistry()) ComputerRegistry(legacyRemoved = true) else it }
    private fun readForWrite() = cache.requireWritable(read())
    /** Retry a failed read instead of interpreting it as an unpair action or stopping subscriptions. */
    fun observe(settings: TaskNotificationSettings) = flow {
        do {
            emit(all(settings))
            if (!readFailure.value) break
            delay(2_000)
        } while (true)
    }
    fun retryRead() { changes.update { it + 1 } }
    private fun save(value: ComputerRegistry) {
        val stream = file.startWrite()
        try { stream.write(keys.encryptLocal(json.encodeToString(value)).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        cache.remember(value)
        changes.update { it + 1 }
    }
    fun all(settings: TaskNotificationSettings = TaskNotificationStore(context).read()): List<ComputerConnection> = synchronized(lock) {
        val registry = read()
        listOfNotNull(legacy(registry, settings)) + registry.connections
    }
    private fun legacy(registry: ComputerRegistry, settings: TaskNotificationSettings = TaskNotificationStore(context).read()): ComputerConnection? =
        if (!registry.legacyRemoved && TaskNotificationProtocol.normalizeEndpoint(settings.endpoint) != null) {
            val hash = TaskInbox.hash(settings.endpoint)
            val remembered = registry.legacyProbe?.takeIf { it.endpoint == settings.endpoint }
            val hosts = if (remembered != null && remembered.lastReachableAt > 0 && remembered.hostId.isNotBlank()) emptyList() else TaskInbox(context).list().filter { it.endpointHash == hash }.mapNotNull {
                it.snapshot.thread_ref?.host_id ?: it.snapshot.remote_ref?.host_id ?: it.remoteState?.command?.host_id
            }.distinct()
            val host = legacyHost(hosts, remembered)
            registry.legacyProbe?.takeIf { it.endpoint == settings.endpoint }?.copy(hostId = host, key = keys.read().orEmpty()) ?:
                ComputerConnection(hash, host, "", settings.endpoint, keys.read().orEmpty(), settings.enabledAtSeconds, legacy = true)
        } else null
    // Normal pairings are already explicit in the registry. Resolving the legacy
    // account scans and decrypts the inbox; it must not precede every live frame.
    fun find(record: TaskInboxRecord) = synchronized(lock) {
        val registry = read()
        registry.connections.firstOrNull { it.matches(record) } ?: legacy(registry)?.takeIf { it.matches(record) }
    }
    fun endpoint(hash: String) = synchronized(lock) {
        val registry = read()
        registry.connections.firstOrNull { TaskInbox.hash(it.endpoint) == hash } ?:
            legacy(registry)?.takeIf { TaskInbox.hash(it.endpoint) == hash }
    }
    private fun findId(id: String, registry: ComputerRegistry = read()) = registry.connections.firstOrNull { it.id == id } ?: legacy(registry)?.takeIf { it.id == id }
    fun add(name: String): ComputerConnection = synchronized(lock) {
        val registry = readForWrite(); require(registry.connections.size < 7 && name.toByteArray().size <= 160)
        val host = UUID.randomUUID().toString()
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val current = TaskNotificationStore(context).read().endpoint
        val origin = current.toHttpUrl()
        val endpoint = origin.newBuilder().encodedPath("/codex-usage-" + nonce).query(null).fragment(null).build().toString()
        val connection = ComputerConnection(host, host, name.trim(), endpoint, TaskContentCipher.newKey(), System.currentTimeMillis() / 1000)
        save(registry.copy(connections = registry.connections + connection)); connection
    }
    /** Restore the exact identity the desktop already uses, without creating new topics or keys. */
    fun restore(backup: ComputerPairingBackup): ComputerConnection = synchronized(lock) {
        // Validate even when called outside the file-picker import path.
        require(TaskNotificationProtocol.normalizeEndpoint(backup.endpoint) == backup.endpoint)
        require(java.util.Base64.getDecoder().decode(backup.key).size == 32)
        require(UUID.fromString(backup.hostId).toString() == backup.hostId)
        val hadRegistry = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
        val registry = read()
        val unreadable = readFailure.value
        if (unreadable) {
            // Keep the unreadable original for recovery; imports must not silently destroy it.
            val original = file.baseFile
            if (original.exists()) original.copyTo(File(original.parentFile, "remote-computers.unreadable-${System.currentTimeMillis()}"))
        }
        val existing = registry.connections.firstOrNull { it.hostId == backup.hostId }
        require(existing != null || registry.connections.size < 7)
        val restored = ComputerConnection(existing?.id ?: backup.hostId, backup.hostId, existing?.name.orEmpty(),
            backup.endpoint, backup.key, existing?.createdAtSeconds ?: System.currentTimeMillis() / 1000)
        val connections = registry.connections.filterNot { it.hostId == backup.hostId } + restored
        val replacesLegacy = unreadable || !hadRegistry || registry.legacyProbe?.hostId == backup.hostId
        save(registry.copy(connections = connections, legacyRemoved = registry.legacyRemoved || replacesLegacy,
            legacyProbe = if (replacesLegacy) null else registry.legacyProbe))
        restored
    }
    fun remove(id: String) = synchronized(lock) {
        val registry = readForWrite(); val connection = all().firstOrNull { it.id == id } ?: return@synchronized
        save(if (connection.legacy) registry.copy(legacyRemoved = true, legacyProbe = null) else
            registry.copy(connections = registry.connections.filterNot { it.id == id }))
        File(context.cacheDir, "task-notifications/Codex-Usage-Windows-" + connection.id.take(8) + ".zip").delete()
        if (connection.legacy) context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE).edit().remove(TaskContentKeys.PREF_KEY).apply()
    }
    fun beginProbe(connection: ComputerConnection, id: String) = synchronized(lock) {
        mutate(connection.id) { it.startProbe(id, System.currentTimeMillis()) }
    }
    fun finishProbe(connection: ComputerConnection, id: String, name: String? = null) = synchronized(lock) {
        mutate(connection.id) { it.endProbe(id, name, System.currentTimeMillis()) }
    }
    /** Called only after authenticating a current event from the exact paired host. */
    fun observeHost(connection: ComputerConnection, event: RemoteEvent) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val current = findId(connection.id)?.takeIf { it.hostId == connection.hostId && it.key == connection.key } ?: return@synchronized
        // Bound encrypted writes; a valid stream renews the lease without repeated presence round trips.
        val updated = current.observe(event, now)
        if (updated != current) mutate(current.id) { updated }
    }
    private fun mutate(id: String, change: (ComputerConnection) -> ComputerConnection) {
        val registry = read()
        if (readFailure.value) return // Presence changes must never overwrite unreadable pairings.
        val current = findId(id, registry) ?: return
        val updated = change(current)
        save(if (current.legacy) registry.copy(legacyProbe = updated.copy(key = "")) else
            registry.copy(connections = registry.connections.map { if (it.id == id) updated else it }))
    }
    fun clear() = synchronized(lock) { file.delete(); cache.clear(); changes.update { it + 1 }; Unit }
    companion object {
        private val lock = Any()
        private val caches = mutableMapOf<String, PairingRegistryCache<ComputerRegistry>>()
        val changes = MutableStateFlow(0L)

        /** Historical snapshots may name old bridge installations. Keep a verified pairing stable. */
        fun legacyHost(hosts: List<String>, remembered: ComputerConnection?): String {
            if (remembered != null && remembered.lastReachableAt > 0 && remembered.hostId.isNotBlank()) return remembered.hostId
            return hosts.distinct().singleOrNull() ?: if (hosts.isEmpty()) remembered?.hostId.orEmpty() else ""
        }
    }
}
