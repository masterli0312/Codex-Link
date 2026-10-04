package com.codex.quota.data.cloud

import android.content.Context
import android.util.AtomicFile
import com.codex.quota.security.KeystoreManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CloudCache(val identity: CloudIdentity, val environments: List<CloudEnvironment> = emptyList(),
    val page: CloudPage = CloudPage(emptyList()), val details: List<CloudDetails> = emptyList(),
    val fetchedAt: Long = 0, val selectedEnvironment: String = "", val branch: String = "main", val draft: String = "")

/** Private encrypted files, excluded from backup. Scope includes local credential identity AND workspace. */
class CloudStateStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "cloud-state").apply { mkdirs() }
    private val crypto = KeystoreManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(identity: CloudIdentity, suffix: String) = AtomicFile(File(directory,
        "${digest(identity.accountId)}-${identity.key}-$suffix"))
    @Synchronized fun cache(identity: CloudIdentity): CloudCache? = read(file(identity, "cache"))
        ?.let { runCatching { json.decodeFromString<CloudCache>(it) }.getOrNull() }?.takeIf { it.identity == identity }
    @Synchronized fun save(cache: CloudCache) {
        val compact = cache.copy(details = cache.details.filter { json.encodeToString(it).toByteArray().size <= 512 * 1024 }.takeLast(5))
        write(file(cache.identity, "cache"), json.encodeToString(compact))
    }
    @Synchronized fun setup(identity: CloudIdentity): CloudSetupSession? = read(file(identity, "setup"))
        ?.let { json.decodeFromString<CloudSetupSession>(it) }
    @Synchronized fun saveSetup(identity: CloudIdentity, session: CloudSetupSession) =
        write(file(identity, "setup"), json.encodeToString(session))
    @Synchronized fun preparationChoice(identity: CloudIdentity): CloudPreparationChoice? = read(file(identity, "preparation-choice"))
        ?.let { json.decodeFromString<CloudPreparationChoice>(it) }
    @Synchronized fun savePreparationChoice(identity: CloudIdentity, choice: CloudPreparationChoice) =
        write(file(identity, "preparation-choice"),json.encodeToString(choice))
    @Synchronized fun submission(identity: CloudIdentity, fingerprint: String): CloudSubmission? =
        read(file(identity, "send-$fingerprint"))?.let { json.decodeFromString<CloudSubmission>(it) }
    @Synchronized fun claim(identity: CloudIdentity, fingerprint: String): Boolean {
        if (submission(identity, fingerprint) != null) return false
        write(file(identity, "send-$fingerprint"), json.encodeToString(CloudSubmission(fingerprint, System.currentTimeMillis())))
        return true
    }
    @Synchronized fun submitted(identity: CloudIdentity, fingerprint: String, id: String) {
        val prior = submission(identity, fingerprint) ?: error("Missing Cloud submission")
        write(file(identity, "send-$fingerprint"), json.encodeToString(prior.copy(taskId = id)))
    }
    @Synchronized fun rejected(identity: CloudIdentity, fingerprint: String) { file(identity, "send-$fingerprint").delete() }
    @Synchronized fun removeAccount(accountId: String) {
        val prefix = digest(accountId) + "-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }
    @Synchronized fun clear() { directory.listFiles()?.forEach { it.delete() } }
    private fun read(file: AtomicFile): String? {
        if (!file.baseFile.exists()) return null
        val value = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        return crypto.decrypt(value) ?: throw CloudException(CloudErrorKind.RESPONSE)
    }
    private fun write(file: AtomicFile, value: String) {
        val encrypted = crypto.encrypt(value).toByteArray()
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
}
