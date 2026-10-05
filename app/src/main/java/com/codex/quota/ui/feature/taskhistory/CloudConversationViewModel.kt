package com.codex.quota.ui.feature.taskhistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codex.quota.CodexQuotaApplication
import com.codex.quota.data.cloud.*
import com.codex.quota.domain.model.CodexAccount
import com.codex.quota.domain.model.CodexUsage
import com.codex.quota.domain.model.isApiKeyPlan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import com.codex.quota.data.cloud.DurableCloudWire.string
import java.util.UUID

data class CloudEditorState(val creating: Boolean = false, val config: CloudConfig? = null,
    val repositories: List<CloudGithubRepository> = emptyList(), val selectedRepos: Set<String> = emptySet(),
    val name: String = "", val install: String = "", val skill: String = "", val network: String = "restricted",
    val session: CloudSetupSession? = null, val details: CloudDetails? = null, val dirty: Boolean = false,
    val answer: String = "", val answerKey: String = "",
    val models: List<CloudPreparationModel> = emptyList(), val choice: CloudPreparationChoice = CloudPreparationChoice(),
    val awaitingTurnId: String = "", val submittedPrompt: String = "")

data class CloudUiState(val accounts: List<CodexAccount> = emptyList(), val accountId: String = "",
    val identity: CloudIdentity? = null, val cache: CloudCache? = null, val selectedTask: String = "",
    val newTask: Boolean = false, val loading: Boolean = false, val sending: Boolean = false,
    val error: CloudErrorKind? = null, val managingEnvironments: Boolean = false, val editor: CloudEditorState? = null,
    val models: List<CloudPreparationModel> = emptyList(), val choice: CloudPreparationChoice = CloudPreparationChoice(),
    val reply: String = "", val replyKey: String = "", val awaitingTurnId: String = "", val submittedPrompt: String = "",
    val usage: CodexUsage? = null, val showArchived: Boolean = false, val historyLoading: Boolean = false,
    val openingTask: Boolean = false)

class CloudConversationViewModel(private val app: CodexQuotaApplication,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val repository = app.cloudRepository
    private val store = app.cloudStore
    private val _state = MutableStateFlow(CloudUiState())
    val state = _state.asStateFlow()
    private var selectionJob: Job? = null
    private var refreshJob: Job? = null
    private var streamJob: Job? = null
    private var streamOwner: Pair<CloudIdentity, String>? = null
    private var readsEnabled = false
    private var setupPending = false
    private var accountUsage: Map<String, CodexUsage?> = emptyMap()
    init {
        viewModelScope.launch {
            app.repository.observeAccounts().collect { rows ->
                val accounts = rows.map { it.account }.filter { !it.isDemoAccount && !it.planType.isApiKeyPlan }
                accountUsage = rows.associate { it.account.id to it.usage }
                _state.update { it.copy(accounts = accounts, usage = accountUsage[it.accountId]) }
                if (accounts.none { it.id == _state.value.accountId }) selectAccount(accounts.firstOrNull()?.id.orEmpty())
            }
        }
    }
    fun selectAccount(id: String) {
        if (_state.value.sending) return
        _state.value.cache?.let { prior -> viewModelScope.launch(ioDispatcher) { store.save(prior) } }
        selectionJob?.cancel(); refreshJob?.cancel(); saveJob?.cancel(); historyJob?.cancel()
        streamJob?.cancel(); streamOwner = null
        setupPending = false
        _state.update { CloudUiState(accounts = it.accounts, accountId = id, loading = id.isNotBlank(),
            newTask = it.newTask, managingEnvironments = it.managingEnvironments, usage = accountUsage[id]) }
        if (id.isBlank()) return
        selectionJob = viewModelScope.launch {
            try {
                val identity = repository.identity(id)
                val cache = withContext(ioDispatcher) { store.cache(identity) } ?: CloudCache(identity)
                _state.update { it.copy(identity = identity, cache = cache, loading = false) }
                refresh()
                loadPreparationModels(identity)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = kind(e)) } }
        }
    }
    fun environment(id: String) { changeCache { cache ->
        if (cache.environments.any { it.id == id }) cache.copy(selectedEnvironment = id) else cache
    } }
    fun branch(value: String) { changeCache { it.copy(branch = value.take(255)) } }
    fun draft(value: String) { if (value.toByteArray().size <= 16_384) changeCache { it.copy(draft = value) } }
    fun manageEnvironments() {
        if (_state.value.sending) return
        refreshJob?.cancel(); refreshJob = null
        _state.update { it.copy(managingEnvironments = true, loading = false, error = null) }
        refresh()
    }
    fun closeEnvironments() {
        refreshJob?.cancel(); refreshJob = null
        if(_state.value.editor != null) { _state.update { it.copy(editor = null, loading = false) }; syncStream(); return }
        _state.update { it.copy(managingEnvironments = false, loading = false) }
        syncStream()
    }
    fun configureEnvironment() {
        if(_state.value.sending) return
        _state.update { it.copy(editor = CloudEditorState(creating = true), error = null) }
        val identity = _state.value.identity ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val repos = repository.repositories(identity)
                if(_state.value.identity == identity) _state.update { it.copy(editor = it.editor?.copy(repositories = repos)) }
                loadPreparationModels(identity)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally { _state.update { it.copy(loading = false) }; syncStream() }
        }
    }
    fun editEnvironment(id: String) {
        if(_state.value.sending || !DurableCloudWire.validId(id)) return
        val identity = _state.value.identity ?: return
        refreshJob?.cancel()
        _state.update { it.copy(editor = CloudEditorState(), managingEnvironments = true, loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val saved = withContext(ioDispatcher) { store.setup(identity) }?.takeIf { it.configId == id }
                val config = repository.config(identity,id, saved?.takeIf { it.explicitDraft && it.stage != "published" }?.draftId.orEmpty())
                val session = saved ?: CloudSetupSession(id, threadId = config.threadId,
                    draftId = config.draft?.string("id").orEmpty(), stage = if(config.published && config.draft == null) "published" else "editing")
                if(_state.value.identity == identity) _state.update { it.copy(editor = editor(config,session)) }
                syncStream()
                val repos = runCatching { repository.repositories(identity) }.getOrElse {
                    if(it is CancellationException) throw it; emptyList()
                }
                val details = session.threadId.takeIf { it.isNotBlank() }?.let { thread ->
                    try { repository.details(identity,thread) } catch(ex: CloudException) { if(ex.httpCode == 404) null else throw ex }
                }
                if(_state.value.identity == identity) _state.update { it.copy(editor = editor(config,session,details).copy(repositories = repos)) }
                loadPreparationModels(identity)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally { _state.update { it.copy(loading = false) }; syncStream() }
        }
    }
    fun editorName(value: String) { _state.update { it.copy(editor = it.editor?.copy(name = value.take(120), dirty = true)) } }
    private suspend fun loadPreparationModels(identity: CloudIdentity) {
        val models = repository.preparationModels(identity)
        val saved = withContext(ioDispatcher) { store.preparationChoice(identity) }
        val choice = DurableCloudWire.chooseModel(models,saved)
        if(_state.value.identity == identity) _state.update { it.copy(models = models,choice = choice,
            editor = it.editor?.copy(models = models,choice = choice)) }
    }
    fun refreshPreparationModels() {
        val identity = _state.value.identity ?: return
        if(_state.value.sending || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true,error = null) }
            try { loadPreparationModels(identity) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally { _state.update { it.copy(loading = false) } }
        }
    }
    fun preparationModel(value: String) {
        if(_state.value.sending) return
        val s = _state.value
        val m = (s.editor?.models ?: s.models).firstOrNull { it.model == value } ?: return
        preparationChoice(CloudPreparationChoice(m.model,(s.editor?.choice ?: s.choice).effort.takeIf { it in m.efforts } ?: m.defaultEffort))
    }
    fun preparationEffort(value: String) {
        val s = _state.value
        preparationChoice((s.editor?.choice ?: s.choice).copy(effort = value))
    }
    private fun preparationChoice(choice: CloudPreparationChoice) {
        val s = _state.value; val identity = s.identity ?: return
        if(s.sending || !DurableCloudWire.validChoice(choice,s.editor?.models ?: s.models)) return
        _state.update { it.copy(choice = choice,editor = it.editor?.copy(choice = choice)) }
        viewModelScope.launch(ioDispatcher) { store.savePreparationChoice(identity,choice) }
    }
    fun editorRepo(id: String) { _state.update { s -> s.copy(editor = s.editor?.let { e ->
        e.copy(selectedRepos = if(id in e.selectedRepos) e.selectedRepos - id else e.selectedRepos + id,
            name = e.name.ifBlank { e.repositories.firstOrNull { it.id == id }?.name?.substringAfterLast('/').orEmpty() }, dirty = true)
    }) } }
    fun useEditorEnvironment() {
        val s = _state.value; val config = s.editor?.config ?: return
        if(s.sending || !config.published) return
        _state.update { it.copy(editor = null,managingEnvironments = false,newTask = true,selectedTask = "",
            cache = it.cache?.copy(selectedEnvironment = config.id)) }
        changeCache { it }
    }
    fun editorInstall(value: String) { if(value.toByteArray().size <= 65536) _state.update { it.copy(editor = it.editor?.copy(install = value, dirty = true)) } }
    fun editorSkill(value: String) { if(value.toByteArray().size <= 65536) _state.update { it.copy(editor = it.editor?.copy(skill = value, dirty = true)) } }
    fun editorNetwork(value: String) { if(value in setOf("restricted", "unrestricted", "disabled")) _state.update { it.copy(editor = it.editor?.copy(network = value, dirty = true)) } }
    fun editorAnswer(value: String) {
        if(value.toByteArray().size <= 16384) _state.update { s -> s.copy(editor = s.editor?.let { e ->
            e.copy(answer = value,answerKey = e.answerKey.ifBlank { UUID.randomUUID().toString() })
        }) }
    }
    fun sendSetupAnswer() {
        val e = _state.value.editor ?: return
        if (e.details?.task?.status == "running") {
            if (queueReply(e.session?.threadId.orEmpty(),e.answer,e.answerKey,e.choice))
                _state.update { it.copy(editor = it.editor?.copy(answer = "",answerKey = "")) }
        } else submitSetupAnswer()
    }
    private fun submitSetupAnswer() = mutateEditor { identity,e ->
        val threadId = e.session?.threadId ?: return@mutateEditor
        if(!CloudWire.validPrompt(e.answer)) return@mutateEditor
        if(!DurableCloudWire.validChoice(e.choice,e.models)) throw CloudException(CloudErrorKind.RESPONSE)
        val fp = digest("setup-answer:${identity.key}:$threadId:${e.answerKey}")
        if(!withContext(ioDispatcher) { store.claim(identity,fp) }) throw CloudException(CloudErrorKind.UNCERTAIN)
        val turnId = try { repository.reply(identity,threadId,e.answer,e.choice,e.details?.activeTurnId.orEmpty()) }
        catch(ex: CloudException) { if(ex.kind != CloudErrorKind.UNCERTAIN) withContext(ioDispatcher) { store.rejected(identity,fp) }; throw ex }
        withContext(ioDispatcher) { store.submitted(identity,fp,turnId) }
        e.session?.let { saveSession(identity,it.copy(stage = if(it.stage == "published") "published" else "prepared")) }
        _state.update { it.copy(editor = it.editor?.copy(answer = "",answerKey = "",awaitingTurnId = turnId,submittedPrompt = e.answer, details = it.editor.details?.let { d ->
            d.copy(task = d.task.copy(status = "running"),activeTurnId = turnId,messages = d.messages + CloudMessage("user",e.answer)) })) }
    }
    private fun editor(config: CloudConfig, session: CloudSetupSession, details: CloudDetails? = null): CloudEditorState {
        val previous = _state.value.editor?.takeIf { it.creating || it.config?.id == config.id }
        return CloudEditorState(
        config = config, name = config.name, install = config.editable.string("install_script"), skill = config.editable.string("start_skill"),
        network = (config.editable["network_policy"] as? JsonObject)?.string("type") ?: "restricted", session = session,
        details = details?.let { CloudConversationStream.merge(previous?.details,it) } ?: previous?.details,
        repositories = previous?.repositories.orEmpty(),models = previous?.models ?: _state.value.models,choice = previous?.choice ?: _state.value.choice,
        answer = previous?.answer.orEmpty(),answerKey = previous?.answerKey.orEmpty(),
        awaitingTurnId = previous?.awaitingTurnId.orEmpty(),submittedPrompt = previous?.submittedPrompt.orEmpty())
    }
    private suspend fun saveSession(identity: CloudIdentity, session: CloudSetupSession) {
        withContext(ioDispatcher) { store.saveSetup(identity, session) }
        if(_state.value.identity == identity) _state.update { it.copy(editor = it.editor?.copy(session = session)) }
    }
    private fun mutateEditor(block: suspend (CloudIdentity, CloudEditorState) -> Unit) {
        val s = _state.value; val identity = s.identity ?: return; val edit = s.editor ?: return
        if(s.sending || s.loading && edit.config == null) return
        refreshJob?.cancel(); _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try { block(identity,edit) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally {
                _state.update { it.copy(sending = false) }
                if(_state.value.editor?.session?.threadId?.isNotBlank() == true) refresh(showProgress = false)
            }
        }
    }
    fun createEnvironment() = mutateEditor { identity, e ->
        if(!e.creating || e.name.isBlank() || e.selectedRepos.isEmpty()) return@mutateEditor
        if(!DurableCloudWire.validChoice(e.choice,e.models)) throw CloudException(CloudErrorKind.RESPONSE)
        withContext(ioDispatcher) { store.savePreparationChoice(identity,e.choice) }
        val refs = e.repositories.filter { it.id in e.selectedRepos }.map { CloudRepositoryRef(it.id,it.branch) }
        val fp = digest("native-config:${identity.key}:${DurableCloudWire.createConfig(e.name,refs)}")
        val prior = withContext(ioDispatcher) { store.submission(identity,fp) }
        val config = if(prior != null) {
            if(prior.taskId.isBlank()) throw CloudException(CloudErrorKind.UNCERTAIN)
            repository.config(identity,prior.taskId)
        } else {
            if(!withContext(ioDispatcher) { store.claim(identity,fp) }) throw CloudException(CloudErrorKind.UNCERTAIN)
            try {
                repository.createConfig(identity,e.name,refs).also { c -> withContext(ioDispatcher) { store.submitted(identity,fp,c.id) } }
            } catch(ex: CloudException) {
                if(ex.httpCode in 400..499 && ex.kind != CloudErrorKind.UNCERTAIN) withContext(ioDispatcher) { store.rejected(identity,fp) }
                throw ex
            }
        }
        val session = CloudSetupSession(config.id, threadId = config.threadId)
        saveSession(identity,session)
        _state.update { it.copy(editor = editor(config,session)) }
        prepare(identity,session,e.choice)
    }
    fun prepareEnvironment() = mutateEditor { identity,e ->
        val config = e.config ?: return@mutateEditor
        if(!DurableCloudWire.validChoice(e.choice,e.models)) throw CloudException(CloudErrorKind.RESPONSE)
        withContext(ioDispatcher) { store.savePreparationChoice(identity,e.choice) }
        var session = e.session ?: CloudSetupSession(config.id,threadId = config.threadId)
        if(session.stage !in setOf("editing", "prepared", "published")) throw CloudException(CloudErrorKind.UNCERTAIN)
        if(session.stage == "published" || config.published && config.draft == null && !session.explicitDraft) session = openEditing(identity,config)
        prepare(identity,session,e.choice)
    }
    private suspend fun openEditing(identity: CloudIdentity, config: CloudConfig): CloudSetupSession {
        val fp = digest("native-draft:${identity.key}:${config.id}:${config.versionId}")
        if(!withContext(ioDispatcher) { store.claim(identity,fp) }) throw CloudException(CloudErrorKind.UNCERTAIN)
        val result = try { repository.openDraft(identity,config.id) }
        catch(e: CloudException) { if(e.httpCode in 400..499 && e.kind != CloudErrorKind.UNCERTAIN) withContext(ioDispatcher) { store.rejected(identity,fp) }; throw e }
        val session = CloudSetupSession(config.id,result.string("draft_id"),result.string("thread_id"),true)
        require(DurableCloudWire.validId(session.draftId) && DurableCloudWire.validId(session.threadId))
        saveSession(identity,session)
        withContext(ioDispatcher) { store.submitted(identity,fp,session.draftId) }
        return session
    }
    private suspend fun prepare(identity: CloudIdentity, initial: CloudSetupSession, choice: CloudPreparationChoice) {
        var session = initial
        if(session.threadId.isBlank()) {
            saveSession(identity,session.copy(stage = "allocating"))
            val threadId = repository.startThread(identity,session.configId,true)
            session = session.copy(threadId = threadId)
            saveSession(identity,session)
        }
        saveSession(identity,session.copy(stage = "setupPending"))
        val prompt = "Use \$cloud-environment-onboarding:setup to set up this cloud environment"
        val turnId = DurableCloudWire.replyTurnId(repository.prepareTurn(identity,session.threadId,prompt,choice))
        saveSession(identity,session.copy(stage = "prepared"))
        val c = repository.config(identity,session.configId,if(session.explicitDraft) session.draftId else "")
        _state.update { it.copy(editor = editor(c,session.copy(stage = "prepared"),it.editor?.details).copy(
            awaitingTurnId = turnId,submittedPrompt = prompt)) }
    }
    fun saveEnvironment() = mutateEditor { identity,e ->
        var c = e.config ?: return@mutateEditor
        var session = e.session ?: CloudSetupSession(c.id,threadId = c.threadId)
        if(session.stage !in setOf("editing", "prepared", "published")) throw CloudException(CloudErrorKind.UNCERTAIN)
        if(session.stage == "published" || c.draft == null) {
            session = openEditing(identity,c)
            c = repository.config(identity,c.id,session.draftId)
        }
        val saved = repository.saveDraft(identity,session,DurableCloudWire.draftPatch(c,e.install,e.skill,e.network))
        session = session.copy(draftId = saved.draft?.string("id").orEmpty())
        saveSession(identity,session)
        _state.update { it.copy(editor = editor(saved,session,e.details)) }
    }
    fun publishEnvironment() = mutateEditor { identity,e ->
        var session = e.session ?: return@mutateEditor
        var c = repository.config(identity,session.configId,if(session.explicitDraft && session.stage != "completing") session.draftId else "")
        if(session.stage == "completing") {
            if(c.published && c.versionId != session.previousVersion && c.versionId == c.latestReadyVersion) {
                saveSession(identity,session.copy(stage = "published")); _state.update { it.copy(editor = editor(c,session.copy(stage = "published"))) }; return@mutateEditor
            }
            throw CloudException(CloudErrorKind.UNCERTAIN)
        }
        if(e.dirty || (session.threadId.isNotBlank() && repository.details(identity,session.threadId).task.status == "running")) throw CloudException(CloudErrorKind.RESPONSE)
        if(session.operationId.isBlank()) {
            if(session.stage !in setOf("editing", "prepared")) throw CloudException(CloudErrorKind.UNCERTAIN)
            val draft = c.draft ?: throw CloudException(CloudErrorKind.RESPONSE)
            session = session.copy(draftId = draft.string("id"), publicationKey = UUID.randomUUID().toString(),previousVersion = c.versionId,stage = "beginPending")
            saveSession(identity,session)
            val op = repository.beginPublish(identity,session,draft.getValue("revision").jsonPrimitive.int,session.publicationKey)
            session = session.copy(operationId = op.string("id"),stage = "publishing")
            require(DurableCloudWire.validId(session.operationId)); saveSession(identity,session)
        }
        val deadline = System.currentTimeMillis() + 120_000
        while(true) {
            val op = repository.operation(identity,session.operationId)
            when(op.string("state")) {
                "SUCCEEDED" -> break
                "PENDING", "RUNNING" -> { if(System.currentTimeMillis() > deadline) throw CloudException(CloudErrorKind.NETWORK); delay(2000) }
                else -> { saveSession(identity,session.copy(stage = "publicationFailed")); throw CloudException(CloudErrorKind.RESPONSE) }
            }
        }
        session = session.copy(stage = "completing"); saveSession(identity,session)
        repository.completePublish(identity,session)
        c = repository.config(identity,session.configId)
        if(!c.published || c.versionId == session.previousVersion || c.versionId != c.latestReadyVersion) throw CloudException(CloudErrorKind.UNCERTAIN)
        session = session.copy(stage = "published"); saveSession(identity,session)
        _state.update { it.copy(editor = editor(c,session)) }
        val environments = repository.environments(identity)
        _state.update { it.copy(cache = it.cache?.let { cache -> CloudEnvironmentSetup.reconcile(cache,identity,environments) }) }
    }
    fun setupOpened() {
        refreshJob?.cancel(); refreshJob = null
        setupPending = true
        _state.update { it.copy(loading = false) }
    }
    fun setupUnavailable() { setupPending = false }
    fun newTask() { refreshJob?.cancel(); historyJob?.cancel(); _state.update { it.copy(newTask = true, selectedTask = "", showArchived = false, historyLoading = false, openingTask = false, loading = false, error = null) }; syncStream() }
    fun backToList() { refreshJob?.cancel(); historyJob?.cancel(); refreshJob = null; _state.update { it.copy(newTask = false, selectedTask = "", historyLoading = false, openingTask = false, error = null) }; refresh() }
    fun open(id: String) { if (!CloudWire.validId(id)) return; refreshJob?.cancel(); historyJob?.cancel(); refreshJob = null; _state.update { it.copy(selectedTask = id, newTask = false, historyLoading = false, openingTask = true, error = null,reply = "",replyKey = "",awaitingTurnId = "",submittedPrompt = "") }; refresh() }
    fun pinThread() = pinThread(_state.value.selectedTask)
    fun pinThread(threadId: String) {
        if (!DurableCloudWire.validId(threadId)) return
        changeCache { it.copy(pinnedThreads = if (threadId in it.pinnedThreads)
            it.pinnedThreads - threadId else it.pinnedThreads + threadId) }
    }
    fun renameThread(threadId: String, name: String) = manageThread("rename", name, threadId)
    fun archiveThread(threadId: String) = manageThread("archive", threadId = threadId)
    fun restoreThread(threadId: String) = manageThread("unarchive", threadId = threadId)
    fun renameThread(name: String) = manageThread("rename", name)
    fun archiveThread() = manageThread("archive")
    fun restoreThread() = manageThread("unarchive")
    fun showArchived(archived: Boolean) {
        if (_state.value.sending) return
        refreshJob?.cancel(); refreshJob = null
        _state.update { it.copy(showArchived = archived, selectedTask = "", newTask = false, loading = false, error = null) }
        refresh()
    }
    private var historyJob: Job? = null
    fun loadHistory() {
        val s = _state.value; val identity = s.identity ?: return
        val details = s.cache?.details?.firstOrNull { it.task.id == s.selectedTask } ?: return
        val cursor = CloudHistory.cursor(details)
        if (cursor.isBlank() || s.sending || historyJob?.isActive == true) return
        refreshJob?.cancel(); refreshJob = null
        _state.update { it.copy(historyLoading = true, loading = false) }
        historyJob = viewModelScope.launch {
            try {
                val older = repository.history(identity, s.selectedTask, cursor)
                if (_state.value.identity == identity && _state.value.selectedTask == s.selectedTask) {
                    _state.update { it.copy(cache = it.cache?.let { cache -> cache.copy(details = cache.details.map { current ->
                        if (current.task.id == s.selectedTask) CloudHistory.prepend(current, older, cursor) else current }) }, error = null) }
                    _state.value.cache?.let { withContext(ioDispatcher) { store.save(it) } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (_state.value.identity == identity && _state.value.selectedTask == s.selectedTask)
                _state.update { it.copy(error = kind(e)) } }
            finally { if (_state.value.identity == identity && _state.value.selectedTask == s.selectedTask)
                _state.update { it.copy(historyLoading = false) } }
        }
    }
    private fun manageThread(action: String, name: String = "", threadId: String = _state.value.selectedTask) {
        val s = _state.value; val identity = s.identity ?: return
        if (s.sending || threadId.isBlank() || s.editor != null) return
        if (runCatching { CloudThreadManagement.request(action, threadId, name) }.isFailure) return
        refreshJob?.cancel(); saveJob?.cancel()
        _state.update { it.copy(sending = true, error = null, loading = false) }
        viewModelScope.launch {
            try {
                repository.manageThread(identity, action, threadId, name)
                if (_state.value.identity == identity) {
                    _state.update { it.copy(cache = it.cache?.let { cache ->
                        CloudThreadManagement.apply(cache, action, threadId, name) },
                        selectedTask = if (action != "rename" && it.selectedTask == threadId) "" else it.selectedTask) }
                    _state.value.cache?.let { withContext(ioDispatcher) { store.save(it) } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (_state.value.identity == identity) _state.update { it.copy(error = kind(e)) } }
            finally { if (_state.value.identity == identity) _state.update { it.copy(sending = false) } }
        }
    }
    fun replyDraft(value: String) {
        if(value.toByteArray().size <= 16384) _state.update { it.copy(reply = value,replyKey = it.replyKey.ifBlank { UUID.randomUUID().toString() }) }
    }
    fun sendReply() {
        val s = _state.value; val identity = s.identity ?: return
        val details = s.cache?.details?.firstOrNull { it.task.id == s.selectedTask } ?: return
        if(s.sending || !CloudWire.validPrompt(s.reply) || !DurableCloudWire.validChoice(s.choice,s.models)) return
        if (details.task.status == "running") {
            if (queueReply(s.selectedTask,s.reply,s.replyKey,s.choice)) _state.update { it.copy(reply = "",replyKey = "") }
            return
        }
        refreshJob?.cancel(); _state.update { it.copy(sending = true,error = null) }
        viewModelScope.launch {
            val fp = digest("cloud-reply:${identity.key}:${s.selectedTask}:${s.replyKey}")
            try {
                if(!withContext(ioDispatcher) { store.claim(identity,fp) }) throw CloudException(CloudErrorKind.UNCERTAIN)
                val turnId = try { repository.reply(identity,s.selectedTask,s.reply,s.choice,details.activeTurnId) }
                catch(ex: CloudException) { if(ex.kind != CloudErrorKind.UNCERTAIN) withContext(ioDispatcher) { store.rejected(identity,fp) }; throw ex }
                withContext(ioDispatcher) { store.submitted(identity,fp,turnId) }
                _state.update { it.copy(reply = "",replyKey = "",awaitingTurnId = turnId,submittedPrompt = s.reply,cache = it.cache?.copy(details = it.cache.details.map { d ->
                    if(d.task.id == s.selectedTask) d.copy(task = d.task.copy(status = "running"),activeTurnId = turnId,
                        messages = d.messages + CloudMessage("user",s.reply)) else d })) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally { _state.update { it.copy(sending = false) }; refresh(showProgress = false) }
        }
    }
    private var saveJob: Job? = null
    private fun changeCache(block: (CloudCache) -> CloudCache) {
        if (_state.value.sending) return
        _state.update { it.copy(cache = it.cache?.let(block)) }
        val cache = _state.value.cache ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            _state.value.cache?.takeIf { it.identity == cache.identity }?.let { latest -> withContext(ioDispatcher) { store.save(latest) } }
        }
    }
    fun refresh(showProgress: Boolean = true) {
        if (!readsEnabled) return
        syncStream()
        val current = _state.value
        val identity = current.identity
        if (identity == null) {
            if (selectionJob?.isActive != true && current.accountId.isNotBlank()) selectAccount(current.accountId)
            return
        }
        if (refreshJob?.isActive == true || current.sending || current.historyLoading) return
        refreshJob = viewModelScope.launch {
            if(showProgress) _state.update { it.copy(loading = true) }
            try {
                if(current.editor != null && !current.editor.creating) {
                    val e = current.editor; val session = e.session ?: return@launch
                    val c = repository.config(identity,session.configId,if(session.explicitDraft && session.stage != "published") session.draftId else "")
                    val rawDetails = session.threadId.takeIf { it.isNotBlank() }?.let { repository.details(identity,it) }
                    val latestEditor = _state.value.editor ?: e
                    val details = rawDetails?.let { DurableCloudWire.reconcileReply(CloudConversationStream.merge(latestEditor.details,it),latestEditor.awaitingTurnId,latestEditor.submittedPrompt) }
                    val recoveredThread = session.threadId.ifBlank { c.threadId }
                    val recoveredStage = when {
                        session.stage == "allocating" && recoveredThread.isNotBlank() -> "editing"
                        session.stage == "setupPending" && details?.messages?.any { it.role == "user" && it.text.contains("cloud-environment-onboarding:setup") } == true -> "prepared"
                        else -> session.stage
                    }
                    val reconciled = session.copy(draftId = c.draft?.string("id") ?: session.draftId,
                        threadId = recoveredThread,stage = recoveredStage)
                    if(_state.value.identity == identity && _state.value.editor?.config?.id == c.id) _state.update { it.copy(editor =
                        if(it.editor?.dirty == true) it.editor?.copy(config = c,session = reconciled,details = details)
                        else editor(c,reconciled,details).copy(answer = it.editor?.answer.orEmpty(),answerKey = it.editor?.answerKey.orEmpty(),repositories = it.editor?.repositories.orEmpty()),error = null) }
                    if(rawDetails != null && rawDetails.latestTurnId == latestEditor.awaitingTurnId) _state.update { it.copy(editor = it.editor?.copy(awaitingTurnId = "",submittedPrompt = "")) }
                    withContext(ioDispatcher) { store.saveSetup(identity,reconciled) }
                } else if (current.managingEnvironments || setupPending) {
                    // Configuration return must refresh environments even when a task detail is selected.
                    val environments = repository.environments(identity)
                    if (_state.value.identity == identity) _state.update { s -> s.copy(cache = s.cache?.let {
                        CloudEnvironmentSetup.reconcile(it, identity, environments) }, error = null) }
                    setupPending = false
                } else if (current.selectedTask.isNotBlank()) {
                    val rawDetails = repository.details(identity, current.selectedTask)
                    val prior = _state.value.cache?.details?.firstOrNull { it.task.id == current.selectedTask }
                    val native = CloudConversationStream.merge(prior,rawDetails)
                    val rejected = native === prior && rawDetails.latestTurnId != prior?.latestTurnId
                    val details = CloudHistory.refreshed(prior, DurableCloudWire.reconcileReply(native,current.awaitingTurnId,current.submittedPrompt))
                    if (_state.value.identity == identity && _state.value.selectedTask == current.selectedTask)
                        _state.update { s -> s.copy(cache = s.cache?.let { it.copy(
                            details = (it.details.filterNot { d -> d.task.id == details.task.id } + details).takeLast(5),
                            page = if (current.showArchived) it.page else it.page.copy(items = (it.page.items.filterNot { t -> t.id == details.task.id } + details.task)
                                .sortedByDescending { t -> t.updatedAt }),
                            archivedPage = if (!current.showArchived) it.archivedPage else it.archivedPage.copy(items =
                                (it.archivedPage.items.filterNot { t -> t.id == details.task.id } + details.task).sortedByDescending { t -> t.updatedAt }),
                            fetchedAt = System.currentTimeMillis()) }, error = null, openingTask = s.openingTask && rejected,
                            awaitingTurnId = if(rawDetails.latestTurnId == current.awaitingTurnId) "" else s.awaitingTurnId,
                            submittedPrompt = if(rawDetails.latestTurnId == current.awaitingTurnId) "" else s.submittedPrompt) }
                } else {
                    val environments = repository.environments(identity)
                    val page = if (current.showArchived) repository.archivedTasks(identity) else repository.tasks(identity)
                    if (_state.value.identity == identity) _state.update { s -> s.copy(cache = s.cache?.let {
                        CloudEnvironmentSetup.reconcile(it, identity, environments).copy(page = if (current.showArchived) it.page else page,
                            archivedPage = if (current.showArchived) page else it.archivedPage,
                            fetchedAt = System.currentTimeMillis()) }, error = null) }
                }
                _state.value.cache?.takeIf { it.identity == identity }?.let { cache -> withContext(ioDispatcher) { store.save(cache) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (_state.value.identity == identity) _state.update { it.copy(error = kind(e)) } }
            finally { if (_state.value.identity == identity) _state.update { it.copy(loading = false) }; syncStream(); drainQueuedReply() }
        }
    }
    fun more() {
        val current = _state.value
        val identity = current.identity ?: return
        val cursor = (if (current.showArchived) current.cache?.archivedPage else current.cache?.page)?.cursor.orEmpty()
        if (cursor.isBlank() || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val page = if (current.showArchived) repository.archivedTasks(identity, cursor) else repository.tasks(identity, cursor)
                if (_state.value.identity == identity) _state.update { s -> s.copy(cache = s.cache?.let {
                    if (current.showArchived) it.copy(archivedPage = page.copy(items = (it.archivedPage.items + page.items).distinctBy { t -> t.id }))
                    else it.copy(page = page.copy(items = (it.page.items + page.items).distinctBy { t -> t.id })) }, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (_state.value.identity == identity) _state.update { it.copy(error = kind(e)) } }
            finally { if (_state.value.identity == identity) _state.update { it.copy(loading = false) } }
        }
    }
    fun send() {
        val current = _state.value
        val cache = current.cache ?: return
        val identity = current.identity ?: return
        if (current.sending || current.loading || !CloudWire.validBranch(cache.branch) || !CloudWire.validPrompt(cache.draft) ||
            cache.environments.none { it.id == cache.selectedEnvironment && it.published } || !DurableCloudWire.validChoice(current.choice,current.models)) return
        _state.update { it.copy(sending = true, error = null) }
        refreshJob?.cancel(); saveJob?.cancel()
        viewModelScope.launch {
            val fingerprint = CloudWire.fingerprint(identity, cache.selectedEnvironment, cache.branch, cache.draft)
            try {
                val prior = withContext(ioDispatcher) { store.submission(identity, fingerprint) }
                val id = if (prior != null) {
                    if (prior.taskId.isBlank()) throw CloudException(CloudErrorKind.UNCERTAIN)
                    prior.taskId
                } else {
                    val claimed = withContext(ioDispatcher) { store.save(cache); store.claim(identity, fingerprint) }
                    if (!claimed) throw CloudException(CloudErrorKind.UNCERTAIN)
                    val created = repository.create(identity, cache.selectedEnvironment, cache.branch, cache.draft)
                    withContext(ioDispatcher) { store.submitted(identity, fingerprint, created) }
                    created
                }
                val turnFingerprint = digest("native-turn:$fingerprint:$id")
                if(!withContext(ioDispatcher) { store.claim(identity,turnFingerprint) }) throw CloudException(CloudErrorKind.UNCERTAIN)
                val turnId = repository.reply(identity,id,cache.draft,current.choice)
                withContext(ioDispatcher) { store.submitted(identity,turnFingerprint,id) }
                val task = CloudTask(id, cache.draft.lineSequence().first().take(120), "running", System.currentTimeMillis())
                _state.update { s -> s.copy(newTask = false, selectedTask = id,awaitingTurnId = turnId,submittedPrompt = cache.draft, cache = s.cache?.let {
                    it.copy(draft = "", page = it.page.copy(items = (listOf(task) + it.page.items).distinctBy { t -> t.id })) }) }
                _state.value.cache?.let { withContext(ioDispatcher) { store.save(it) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Only definite client rejection permits a later user-initiated retry.
                if (e is CloudException && e.httpCode in 400..499 && e.httpCode != 408)
                    withContext(ioDispatcher) { store.rejected(identity, fingerprint) }
                _state.update { it.copy(error = kind(e)) }
            } finally {
                _state.update { it.copy(sending = false) }
                if (_state.value.accounts.none { it.id == _state.value.accountId }) selectAccount(_state.value.accounts.firstOrNull()?.id.orEmpty())
                else if (_state.value.selectedTask.isNotBlank()) refresh()
            }
        }
    }
    private fun kind(e: Exception) = (e as? CloudException)?.kind ?: CloudErrorKind.RESPONSE

    private fun queuedThread() = _state.value.editor?.session?.threadId ?: _state.value.selectedTask
    private fun queueReply(thread: String,text: String,key: String,choice: CloudPreparationChoice): Boolean {
        val s = _state.value
        if (s.sending || !DurableCloudWire.validId(thread) || !CloudWire.validPrompt(text) ||
            !DurableCloudWire.validChoice(choice,s.editor?.models ?: s.models) ||
            s.cache?.pendingReplies?.count { it.threadId == thread } != 0) return false
        val pending = CloudPendingReply(thread,text,key.ifBlank { UUID.randomUUID().toString() },choice)
        _state.update { it.copy(cache = it.cache?.copy(pendingReplies = it.cache.pendingReplies + pending)) }
        persistQueuedCache()
        return true
    }
    private fun persistQueuedCache() {
        val cache = _state.value.cache ?: return
        viewModelScope.launch(ioDispatcher) { store.save(cache) }
    }
    fun cancelQueuedReply() {
        if (_state.value.sending) return
        val thread = queuedThread()
        _state.update { it.copy(cache = it.cache?.copy(pendingReplies = it.cache.pendingReplies.filterNot { p -> p.threadId == thread })) }
        persistQueuedCache()
    }
    fun editQueuedReply() {
        val s = _state.value
        if (s.sending) return
        val pending = s.cache?.pendingReplies?.firstOrNull { it.threadId == queuedThread() } ?: return
        if (s.editor != null) editorAnswer(listOf(pending.text,s.editor.answer).filter { it.isNotBlank() }.joinToString("\n"))
        else replyDraft(listOf(pending.text,s.reply).filter { it.isNotBlank() }.joinToString("\n"))
        cancelQueuedReply()
    }
    fun stop() {
        val s = _state.value; val identity = s.identity ?: return
        val thread = queuedThread()
        val details = s.editor?.details ?: s.cache?.details?.firstOrNull { it.task.id == thread } ?: return
        if (s.sending || details.task.status != "running" || details.activeTurnId.isBlank()) return
        _state.update { it.copy(sending = true,cache = it.cache?.copy(pendingReplies = it.cache.pendingReplies.map { p -> if (p.threadId == thread) p.copy(attempted = true) else p })) }
        persistQueuedCache()
        viewModelScope.launch {
            try { repository.stop(identity,thread,details.activeTurnId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally { _state.update { it.copy(sending = false) }; refresh(showProgress = false) }
        }
    }
    fun steerQueuedReply() = deliverQueuedReply(steer = true)
    private fun drainQueuedReply() = deliverQueuedReply(steer = false)
    private fun deliverQueuedReply(steer: Boolean) {
        val s = _state.value
        val identity = s.identity ?: return
        val thread = queuedThread()
        val pending = s.cache?.pendingReplies?.firstOrNull { it.threadId == thread } ?: return
        val details = if (s.editor != null) s.editor.details else s.cache.details.firstOrNull { it.task.id == thread }
        if (!readsEnabled || s.sending || details == null ||
            (steer && (details.task.status != "running" || details.activeTurnId.isBlank())) ||
            (!steer && (details.task.status !in setOf("completed","failed") || pending.attempted))) return
        _state.update { it.copy(sending = true,error = null,cache = it.cache?.copy(pendingReplies = it.cache.pendingReplies.map { p -> if (p.key == pending.key) p.copy(attempted = true) else p })) }
        viewModelScope.launch {
            val fp = digest("cloud-queued:${identity.key}:$thread:${pending.key}")
            try {
                withContext(ioDispatcher) { _state.value.cache?.let { store.save(it) } }
                if (!withContext(ioDispatcher) { store.claim(identity,fp) }) throw CloudException(CloudErrorKind.UNCERTAIN)
                val turn = try { repository.reply(identity,thread,pending.text,pending.choice,if (steer) details.activeTurnId else "") }
                catch (e: CloudException) {
                    if (e.kind != CloudErrorKind.UNCERTAIN) withContext(ioDispatcher) { store.rejected(identity,fp) }
                    throw e
                }
                withContext(ioDispatcher) { store.submitted(identity,fp,turn) }
                _state.update { current ->
                    if (current.identity != identity) current else {
                        val optimistic = CloudMessage("user",pending.text,"$turn:queued-${pending.key}",turn,position = Int.MAX_VALUE)
                        fun accepted(d: CloudDetails?) = d?.copy(task = d.task.copy(status = "running"),activeTurnId = turn,
                            latestTurnId = turn,messages = d.messages + optimistic)
                        current.copy(cache = current.cache?.copy(pendingReplies = current.cache.pendingReplies.filterNot { it.key == pending.key },
                            details = current.cache.details.map { if (it.task.id == thread) accepted(it)!! else it }),
                            editor = current.editor?.let { if (it.session?.threadId == thread) it.copy(details = accepted(it.details),awaitingTurnId = turn,submittedPrompt = pending.text) else it },
                            awaitingTurnId = if (current.selectedTask == thread) turn else current.awaitingTurnId,
                            submittedPrompt = if (current.selectedTask == thread) pending.text else current.submittedPrompt)
                    }
                }
                persistQueuedCache()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = kind(e)) } }
            finally { _state.update { it.copy(sending = false) }; refresh(showProgress = false) }
        }
    }

    private fun syncStream() {
        val s = _state.value
        val identity = s.identity
        val thread = if (s.editor != null) s.editor.session?.threadId.orEmpty()
            else s.selectedTask.takeUnless { s.managingEnvironments || s.newTask }.orEmpty()
        val owner = if (readsEnabled && identity != null && thread.isNotBlank()) identity to thread else null
        if (owner == streamOwner && streamJob?.isActive == true) return
        streamJob?.cancel(); streamOwner = owner
        if (owner == null) return
        streamJob = viewModelScope.launch {
            try {
                repository.watchDetails(owner.first,owner.second).collect { incoming ->
                    if (!readsEnabled || streamOwner != owner || _state.value.identity != owner.first) return@collect
                    _state.update { current ->
                        if (current.editor?.session?.threadId == owner.second) {
                            val e = current.editor
                            val details = DurableCloudWire.reconcileReply(CloudConversationStream.merge(e.details,incoming),e.awaitingTurnId,e.submittedPrompt)
                            current.copy(editor = e.copy(details = details,
                                awaitingTurnId = if (details.latestTurnId == e.awaitingTurnId) "" else e.awaitingTurnId,
                                submittedPrompt = if (details.latestTurnId == e.awaitingTurnId) "" else e.submittedPrompt),error = null)
                        } else if (current.selectedTask == owner.second && !current.managingEnvironments) {
                            val prior = current.cache?.details?.firstOrNull { it.task.id == owner.second }
                            val native = CloudConversationStream.merge(prior,incoming)
                            val rejected = native === prior && incoming.latestTurnId != prior?.latestTurnId
                            val details = CloudHistory.refreshed(prior, DurableCloudWire.reconcileReply(native,current.awaitingTurnId,current.submittedPrompt))
                            current.copy(cache = current.cache?.let { cache -> cache.copy(
                                details = (cache.details.filterNot { it.task.id == owner.second } + details).takeLast(5),
                                page = cache.page.copy(items = (cache.page.items.filterNot { it.id == owner.second } + details.task).sortedByDescending { it.updatedAt }),
                                fetchedAt = System.currentTimeMillis()) },
                                awaitingTurnId = if (details.latestTurnId == current.awaitingTurnId) "" else current.awaitingTurnId,
                                submittedPrompt = if (details.latestTurnId == current.awaitingTurnId) "" else current.submittedPrompt,error = null,
                                openingTask = current.openingTask && rejected)
                        } else current
                    }
                    saveJob?.cancel()
                    saveJob = viewModelScope.launch {
                        delay(300)
                        _state.value.cache?.takeIf { it.identity == owner.first }?.let { withContext(ioDispatcher) { store.save(it) } }
                    }
                    drainQueuedReply()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // HTTP remains the independent fallback. The next refresh reconnects
                // and hydrates the socket, without replaying any user submission.
            }
        }
    }

    fun resumeReads() { readsEnabled = true; refresh() }
    fun pauseReads() { readsEnabled = false; refreshJob?.cancel(); streamJob?.cancel(); streamOwner = null }
}
