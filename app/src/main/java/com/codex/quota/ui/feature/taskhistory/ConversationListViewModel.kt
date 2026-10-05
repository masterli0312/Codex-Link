package com.codex.quota.ui.feature.taskhistory

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codex.quota.notifications.task.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** Retain data across detail navigation; collect disk changes only while the list is visible. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val inbox = TaskInbox(appContext)
    private val computerStore = ComputerConnectionStore(appContext)
    private val syncStore = ConversationSyncSelectionStore(appContext)
    val records = TaskInbox.changes.mapLatest { withContext(Dispatchers.IO) { inbox.list() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)
    val computers = combine(TaskNotificationStore(appContext).settings, ComputerConnectionStore.changes) { settings, _ -> settings }
        .flatMapLatest { settings -> computerStore.observe(settings).flowOn(Dispatchers.IO) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val syncSelections = combine(computers, ConversationSyncSelectionStore.changes) { computers, _ -> computers }
        .mapLatest { computers -> withContext(Dispatchers.IO) {
            computers.associate { ConversationSyncRules.scope(it) to syncStore.read(it) }
        } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)
    suspend fun saveSyncSelection(computer: ComputerConnection, ids: Set<String>, available: List<RemoteThreadSummary>) =
        withContext(Dispatchers.IO) { syncStore.replace(computer, ids, available) }

    // The last completed projection avoids an empty list frame on return from a detail.
    private val presentations = linkedMapOf<List<String>, ConversationListPresentation>()
    fun presentationFor(key: List<String>) = presentations[key] ?: ConversationListPresentation()
    fun rememberPresentation(key: List<String>, value: ConversationListPresentation) {
        presentations[key] = value
        while (presentations.size > 8) presentations.remove(presentations.keys.first())
    }
}
