package com.codex.quota.notifications.task

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    private fun read(): ComputerRegistry = runCatching {
        require(file.baseFile.length() <= 64_000)
        json.decodeFromString<ComputerRegistry>(requireNotNull(keys.decryptLocal(String(file.readFully(), Charsets.UTF_8))))
    }.getOrElse { ComputerRegistry(legacyRemoved = file.baseFile.exists()) }
    private fun save(value: ComputerRegistry) {
        val stream = file.startWrite()
        try { stream.write(keys.encryptLocal(json.encodeToString(value)).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
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
        val registry = read(); require(registry.connections.size < 7 && name.toByteArray().size <= 160)
        val host = UUID.randomUUID().toString()
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val current = TaskNotificationStore(context).read().endpoint
        val origin = current.toHttpUrl()
        val endpoint = origin.newBuilder().encodedPath("/codex-usage-" + nonce).query(null).fragment(null).build().toString()
        val connection = ComputerConnection(host, host, name.trim(), endpoint, TaskContentCipher.newKey(), System.currentTimeMillis() / 1000)
        save(registry.copy(connections = registry.connections + connection)); connection
    }
    fun remove(id: String) = synchronized(lock) {
        val registry = read(); val connection = all().firstOrNull { it.id == id } ?: return@synchronized
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
        val current = findId(id, registry) ?: return
        val updated = change(current)
        save(if (current.legacy) registry.copy(legacyProbe = updated.copy(key = "")) else
            registry.copy(connections = registry.connections.map { if (it.id == id) updated else it }))
    }
    fun clear() = synchronized(lock) { file.delete(); changes.update { it + 1 }; Unit }
    companion object {
        private val lock = Any()
        val changes = MutableStateFlow(0L)

        /** Historical snapshots may name old bridge installations. Keep a verified pairing stable. */
        fun legacyHost(hosts: List<String>, remembered: ComputerConnection?): String {
            if (remembered != null && remembered.lastReachableAt > 0 && remembered.hostId.isNotBlank()) return remembered.hostId
            return hosts.distinct().singleOrNull() ?: if (hosts.isEmpty()) remembered?.hostId.orEmpty() else ""
        }
    }
}
