package com.codex.quota.notifications.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.codex.quota.R
import com.codex.quota.notifications.withAppIcon
import com.codex.quota.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import java.text.DateFormat
import java.util.Date

enum class TaskConnectionState { OFF, CONNECTING, CONNECTED, RETRYING }
object TaskNotificationConnection { val state = MutableStateFlow(TaskConnectionState.OFF) }

/** Opt-in foreground connection, independent of quota refresh and all account credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCompletionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: TaskNotificationStore
    private var listening = false
    private val taskClient by lazy { NtfyTaskClient(context = applicationContext) }
    private val relayStates = java.util.concurrent.ConcurrentHashMap<String, TaskConnectionState>()
    private val localizedContext get() = ContextCompat.getContextForLanguage(this)

    override fun onCreate() {
        super.onCreate()
        store = TaskNotificationStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, localizedContext.getString(R.string.task_connection_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, localizedContext.getString(R.string.task_notification_title), NotificationManager.IMPORTANCE_DEFAULT))
        ServiceCompat.startForeground(this, CONNECTION_ID, connectionNotification(R.string.task_connecting),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!listening) {
            listening = true
            scope.launch {
                val computers = ComputerConnectionStore(this@TaskCompletionService)
                combine(store.settings, ComputerConnectionStore.changes) { settings, _ -> settings.copy(previewEnabled = false) }
                    .flatMapLatest { settings -> computers.observe(settings).map { connections ->
                        settings to connections.map { it.copy(name = "", lastProbeId = "", lastProbeAt = 0, lastReachableAt = 0, reachable = false, checking = false) }
                    } }.distinctUntilChanged().collectLatest { (settings, connections) ->
                    if (settings.enabled && connections.isEmpty() && computers.readFailure.value) {
                        setConnectionState(TaskConnectionState.RETRYING, "pairing-storage")
                        return@collectLatest
                    }
                    if (!settings.enabled || connections.isEmpty()) { stopSelf(); return@collectLatest }
                    relayStates.clear()
                    coroutineScope {
                        if (settings.remoteEnabled && settings.contentEnabled) connections.filter { it.hostId.isNotBlank() && it.key.isNotBlank() }.forEach { computer ->
                            launch { RemoteConversationClient(this@TaskCompletionService).listenComputer(computer) { record, event ->
                                if (!NotificationManagerCompat.from(this@TaskCompletionService).areNotificationsEnabled()) return@listenComputer
                                val identity = RemoteAttention.identity(event)
                                val notification = TaskCompletionEvent(event.id, if (event.status == "approval") "permission_request" else "question",
                                    event.at, identity)
                                val topic = RemoteProtocol.topic(computer.endpoint, computer.key, computer.hostId, "events")
                                if (store.claim(topic, notification)) showEvent(notification, record.snapshot.copy(reply = ""), record.id, record.endpointHash)
                            } }
                        }
                        connections.forEach { computer -> launch {
                            var retryDelay = 2_000L
                            while (currentCoroutineContext().isActive) {
                                setConnectionState(TaskConnectionState.CONNECTING, computer.id)
                                var failure: Exception? = null
                                try {
                                    taskClient.events(computer.endpoint, store.since(computer.endpoint,
                                        maxOf(settings.enabledAtSeconds, computer.createdAtSeconds))) {
                                        setConnectionState(TaskConnectionState.CONNECTED, computer.id)
                                        retryDelay = 2_000L
                                    }.collect { event ->
                                        val latest = store.read()
                                        val paired = computers.endpoint(TaskInbox.hash(computer.endpoint))
                                        if (latest.enabled && paired != null && paired.key == computer.key &&
                                            NotificationManagerCompat.from(this@TaskCompletionService).areNotificationsEnabled() &&
                                            store.claim(computer.endpoint, event)) handleEvent(computer, event)
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (e: Exception) { failure = e /* No private URLs, bodies or credentials in logs. */ }
                                setConnectionState(TaskConnectionState.RETRYING, computer.id)
                                RelayConnection.retry(this@TaskCompletionService, failure, retryDelay)
                                retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                            }
                        } }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun setConnectionState(state: TaskConnectionState, computerId: String) {
        relayStates[computerId] = state
        val aggregate = if (relayStates.values.any { it == TaskConnectionState.CONNECTED }) TaskConnectionState.CONNECTED else
            if (relayStates.values.any { it == TaskConnectionState.CONNECTING }) TaskConnectionState.CONNECTING else TaskConnectionState.RETRYING
        TaskNotificationConnection.state.value = aggregate
        val text = when (aggregate) {
            TaskConnectionState.CONNECTED -> R.string.task_connected
            TaskConnectionState.RETRYING -> R.string.task_reconnecting
            else -> R.string.task_connecting
        }
        getSystemService(NotificationManager::class.java).notify(CONNECTION_ID, connectionNotification(text))
    }

    private fun pendingIntent(recordId: String? = null): PendingIntent = PendingIntent.getActivity(this, 5100,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (recordId != null) {
                data = Uri.parse("codexquota://task/$recordId")
                putExtra(TASK_RECORD_EXTRA, recordId)
            }
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun connectionNotification(text: Int): Notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL)
        .withAppIcon(this)
        .setContentTitle(localizedContext.getString(R.string.app_name))
        .setContentText(localizedContext.getString(text)).setOngoing(true).setSilent(true)
        .setContentIntent(pendingIntent()).build()

    private fun handleEvent(computer: ComputerConnection, event: TaskCompletionEvent) {
        val endpoint = computer.endpoint
        val settings = store.read()
        val key = if (settings.contentEnabled) computer.key.takeIf { it.isNotBlank() } else null
        val snapshot = if (key != null) event.encryptedContent?.let { TaskContentCipher.decode(it, key, event.deduplicationId) } else null
        val ref = snapshot?.thread_ref ?: snapshot?.remote_ref
        if (ref != null && computer.hostId.isNotBlank() && ref.host_id != computer.hostId) return
        val inbox = TaskInbox(this)
        val syncStore = ConversationSyncSelectionStore(this)
        if (ref != null && event.status in setOf("turn_complete", "task_complete", "review_complete"))
            syncStore.markActivity(computer, ref.thread_id, ref.baseline_turn, false, event.timeEpochMs, true)
        val selected = snapshot?.let { ConversationSyncRules.caches(it, computer, syncStore.read(computer)) } == true
        var record: TaskInboxRecord? = null
        if (snapshot != null && selected) {
            val attachment = event.attachment?.takeIf { RelayRouting.attachmentUrl(this, endpoint, it.url) != null }
            val saved = TaskInboxRecord(inbox.recordId(endpoint, event.deduplicationId), event.timeEpochMs, event.deduplicationId,
                TaskInbox.hash(endpoint), snapshot, attachment?.url, attachment?.expiresAt ?: 0)
            record = runCatching { inbox.saveNotification(saved, event.status in setOf("turn_complete", "task_complete", "review_complete")) }.getOrNull()
        }
        // Show the authenticated inline preview immediately; a file download must not delay the alert.
        showEvent(event, snapshot, record?.id, TaskInbox.hash(endpoint))
        val saved = record
        val attachment = event.attachment
        if (saved != null && saved.identity == event.deduplicationId && attachment != null && key != null) scope.launch {
            val full = taskClient.downloadSnapshot(endpoint, attachment, key, event.deduplicationId) ?: return@launch
            if (full.remote_ref != null && computer.hostId.isNotBlank() && full.remote_ref.host_id != computer.hostId) return@launch
            val latest = store.read()
            if (latest.enabled && latest.contentEnabled && ComputerConnectionStore(this@TaskCompletionService).endpoint(TaskInbox.hash(endpoint))?.key == key && inbox.read(saved.id) != null) {
                  if (ref == null || !syncStore.isSelected(computer, ref.thread_id)) return@launch
                runCatching { inbox.replaceDownloaded(saved.copy(snapshot = full, hasFullSnapshot = true)) }
            }
        }
    }

    private fun showEvent(event: TaskCompletionEvent, snapshot: TaskConversationSnapshot? = null, recordId: String? = null, pairing: String) {
        val preview = snapshot.takeIf { store.read().previewEnabled }
        val text = when (event.status) {
            "test" -> R.string.task_test_received
            "api_error", "api_error_overloaded", "session_limit_reached" -> R.string.task_attention_error
            "permission_request", "question", "plan_ready" -> R.string.task_attention_input
            else -> R.string.task_turn_ended
        }
        val generic = localizedContext.getString(text)
        val notification = NotificationCompat.Builder(this, EVENT_CHANNEL)
            .withAppIcon(this)
            .setContentTitle(preview?.title?.takeIf { it.isNotBlank() } ?: localizedContext.getString(R.string.task_notification_title))
            .setContentText(preview?.reply?.takeIf { it.isNotBlank() } ?: generic)
            .setSubText(localizedContext.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview?.reply?.takeIf { it.isNotBlank() } ?: generic))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, EVENT_CHANNEL).withAppIcon(this)
                .setContentTitle(localizedContext.getString(R.string.task_notification_title)).setContentText(generic).build())
            .setWhen(event.timeEpochMs).setAutoCancel(true).setContentIntent(pendingIntent(recordId)).build()
        try {
            NotificationManagerCompat.from(this).notify(pairing + ":" + event.deduplicationId, EVENT_ID, notification)
        } catch (_: SecurityException) { /* Permission may be revoked while connected. */ }
    }

    override fun onDestroy() {
        scope.cancel()
        TaskNotificationConnection.state.value = TaskConnectionState.OFF
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TASK_RECORD_EXTRA = "task_record_id"
        const val TASK_HISTORY_EXTRA = "show_task_history"
        private const val CONNECTION_CHANNEL = "channel_codex_task_connection"
        private const val EVENT_CHANNEL = "channel_codex_task_events"
        private const val CONNECTION_ID = 5102
        private const val EVENT_ID = 5101
        fun startIfEnabled(context: Context): Boolean {
            if (!TaskNotificationStore(context).read().enabled) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, TaskCompletionService::class.java))
                true
            } catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
        }
        fun stop(context: Context) { context.stopService(Intent(context, TaskCompletionService::class.java)) }
    }
}
