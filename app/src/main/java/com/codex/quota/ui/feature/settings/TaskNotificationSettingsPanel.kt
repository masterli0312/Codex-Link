package com.codex.quota.ui.feature.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.codex.quota.R
import com.codex.quota.ui.components.rememberQuotaClock
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun TaskNotificationSettingsPanel() {
    val context = LocalContext.current
    val store = remember { TaskNotificationStore(context) }
    val settings by store.settings.collectAsState(initial = store.read())
    val state by TaskNotificationConnection.state.collectAsState()
    val scope = rememberCoroutineScope()
    val now = rememberQuotaClock()
    val computerStore = remember { ComputerConnectionStore(context) }
    val computerRevision by ComputerConnectionStore.changes.collectAsState()
    var computers by remember { mutableStateOf<List<ComputerConnection>>(emptyList()) }
    var addComputer by remember { mutableStateOf(false) }
    var computerName by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ComputerConnection?>(null) }
    var exportError by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    LaunchedEffect(computerRevision, settings) { computers = withContext(Dispatchers.IO) { computerStore.all(settings) } }
    suspend fun shareComputer(computer: ComputerConnection) {
        exporting = true; exportError = false
        try {
            val zip = withContext(Dispatchers.IO) { exportInstaller(context, settings, computer) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".task-notification-files", zip)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, context.getString(R.string.task_export_installer)))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { exportError = true }
        finally { exporting = false }
    }
    var editEndpoint by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.ensureEndpoint() }
    fun enable() {
        store.setEnabled(true)
        startError = !TaskCompletionService.startIfEnabled(context)
        if (startError) store.setEnabled(false)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else startError = true
    }
    SettingsHeading(stringResource(R.string.computer_notification_receiving))
    SettingsGroup {
        SettingsToggleRow(stringResource(R.string.computer_task_completion_alerts), null, settings.enabled, { enabled ->
            if (!enabled) {
                store.setEnabled(false)
                TaskCompletionService.stop(context)
            } else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                enable()
            } else if (Build.VERSION.SDK_INT >= 33) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else { startError = true }
        }, enabled = TaskNotificationProtocol.normalizeEndpoint(settings.endpoint) != null)
        SettingsDivider()
        val status = when {
            !settings.enabled -> R.string.task_connection_off
            state == TaskConnectionState.CONNECTED -> R.string.task_connected
            state == TaskConnectionState.RETRYING -> R.string.task_reconnecting
            else -> R.string.task_connecting
        }
        SettingsInfo(stringResource(status), stringResource(R.string.task_notification_summary),
            if (settings.enabled && state == TaskConnectionState.CONNECTED) Icons.Outlined.CheckCircle else Icons.Outlined.NotificationsNone)
    }
    if (startError) Text(stringResource(R.string.task_start_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    SettingsHeading(stringResource(R.string.settings_conversation_sync))
    SettingsGroup {
        SettingsToggleRow(stringResource(R.string.task_content_title), null, settings.contentEnabled, { enabled ->
            try { store.setContentEnabled(enabled) } catch (_: Exception) { exportError = true }
        })
        SettingsDivider()
        SettingsToggleRow(stringResource(R.string.remote_title), null, settings.remoteEnabled, { store.setRemoteEnabled(it) },
            enabled = settings.contentEnabled)
    }
    Text(stringResource(R.string.task_content_summary), Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.remote_setup_detail), Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsHeading(stringResource(R.string.task_pairing_title))
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.task_pairing_steps), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { computerName = ""; addComputer = true }, enabled = !exporting && computers.size < 8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.computer_add))
            }
            if (exportError) Text(stringResource(R.string.task_export_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        SettingsDivider()
        SettingsNavigationRow(stringResource(R.string.task_copy_address), stringResource(R.string.task_pairing_address), Icons.Outlined.ContentCopy) {
            val clip = ClipData.newPlainText(context.getString(R.string.task_pairing_address), RelayRouting.endpoint(context, settings.endpoint))
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }
    }
    if (computers.isNotEmpty()) {
        SettingsHeading(stringResource(R.string.computer_connections))
        SettingsGroup {
            computers.forEachIndexed { index, computer ->
                if (index > 0) SettingsDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(computer.name.ifBlank { stringResource(R.string.conversation_paired_computer) }, style = MaterialTheme.typography.titleSmall)
                    val status = when {
                        computer.checking && now - computer.lastProbeAt < 30_000 -> R.string.computer_checking
                        computer.recentlyReachable(now) -> R.string.computer_reachable
                        computer.lastProbeAt > 0 && !computer.reachable -> R.string.computer_unreachable
                        else -> R.string.computer_not_checked
                    }
                    Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { scope.launch { ComputerConnectionClient(context).probe(computer) } },
                            enabled = computer.hostId.isNotBlank() && !(computer.checking && now - computer.lastProbeAt < 30_000) && !exporting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.computer_check)) }
                        TextButton(onClick = { scope.launch { shareComputer(computer) } }, enabled = !exporting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.computer_export)) }
                        TextButton(onClick = { removing = computer }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.computer_unpair), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (addComputer) AlertDialog(onDismissRequest = { addComputer = false }, title = { Text(stringResource(R.string.computer_add)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.computer_pair_detail), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(computerName, { if (it.toByteArray().size <= 160) computerName = it }, singleLine = true,
                label = { Text(stringResource(R.string.computer_name)) })
        } }, confirmButton = { TextButton(onClick = {
            addComputer = false
            scope.launch {
                try {
                    val computer = withContext(Dispatchers.IO) { computerStore.add(computerName) }
                    if (settings.enabled) TaskCompletionService.startIfEnabled(context)
                    shareComputer(computer)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { exportError = true }
            }
        }) { Text(stringResource(R.string.task_export_installer)) } },
        dismissButton = { TextButton(onClick = { addComputer = false }) { Text(stringResource(R.string.action_cancel)) } })
    removing?.let { computer -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.computer_unpair)) },
        text = { Text(stringResource(R.string.computer_unpair_detail)) }, confirmButton = { TextButton(onClick = {
            removing = null; scope.launch { withContext(Dispatchers.IO) { computerStore.remove(computer.id) } }
        }) { Text(stringResource(R.string.computer_unpair), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    SettingsHeading(stringResource(R.string.settings_delivery_privacy))
    SettingsGroup {
        SettingsNavigationRow(stringResource(R.string.task_background_settings), stringResource(R.string.task_background_detail), Icons.Outlined.BatteryChargingFull) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        SettingsDivider()
        SettingsToggleRow(stringResource(R.string.computer_notification_preview), stringResource(R.string.computer_notification_preview_detail),
            settings.previewEnabled, { store.setPreviewEnabled(it) }, enabled = settings.contentEnabled)
        SettingsDivider()
        SettingsInfo(stringResource(R.string.privacy_local_storage), stringResource(R.string.task_privacy_detail), Icons.Outlined.Lock)
    }
    TextButton(onClick = { input = RelayRouting.endpoint(context, settings.endpoint); editEndpoint = true; inputError = false },
        modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.task_change_address))
    }
    if (editEndpoint) AlertDialog(onDismissRequest = { editEndpoint = false },
        title = { Text(stringResource(R.string.task_pairing_address)) },
        text = { Column {
            Text(stringResource(R.string.computer_change_service_detail), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = input, onValueChange = { input = it; inputError = false }, singleLine = true,
                isError = inputError, label = { Text(stringResource(R.string.task_pairing_address)) })
            if (inputError) Text(stringResource(R.string.task_address_invalid), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            val endpoint = TaskNotificationProtocol.normalizeEndpoint(input)
            if (endpoint == null) inputError = true else {
                TaskCompletionService.stop(context)
                store.saveEndpoint(endpoint)
                if (store.read().enabled) TaskCompletionService.startIfEnabled(context)
                editEndpoint = false
            }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { editEndpoint = false }) { Text(stringResource(R.string.action_cancel)) } })
}

private fun exportInstaller(context: Context, settings: TaskNotificationSettings, computer: ComputerConnection): File {
    val endpoint = RelayRouting.endpoint(context, computer.endpoint)
    require(TaskNotificationProtocol.normalizeEndpoint(endpoint) != null)
    val dir = File(context.cacheDir, "task-notifications").apply { mkdirs() }
    val output = File(dir, "Codex-Usage-Windows-" + computer.id.take(8) + ".zip")
    ZipOutputStream(output.outputStream()).use { zip ->
        for (name in listOf("Install.ps1", "notify.cjs", "task-content.cjs", "remote.cjs", "remote-core.cjs", "codex-client.cjs", "native-runtime.cjs", "conversation-library.cjs", "conversation-images.cjs", "live-watch.cjs", "remote-activity.cjs", "remote-goal.cjs", "remote-options.cjs", "remote-stream.cjs", "remote-attachments.cjs", "shared-server.cjs", "Enable-SharedConversations.ps1", "SettingsFile.ps1", "setup.cmd", "NotificationLauncher.cs")) {
            zip.putNextEntry(ZipEntry(name))
            context.assets.open("task-notifications/$name").use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("pairing.json"))
        zip.write(buildJsonObject {
            put("endpoint", endpoint)
            if (settings.contentEnabled) put("contentKey", computer.key.ifBlank { TaskContentKeys(context).ensure() })
            if (computer.hostId.isNotBlank()) put("remoteHostId", computer.hostId)
            put("remoteEnabled", settings.remoteEnabled && settings.contentEnabled)
        }.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return output
}
