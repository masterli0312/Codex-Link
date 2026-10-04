package com.codex.quota

import com.codex.quota.data.cloud.*
import com.codex.quota.domain.repository.CodexAccountRepository
import com.codex.quota.ui.feature.taskhistory.CloudConversationViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CompletableDeferred

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudEnvironmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val identity = CloudIdentity("account-a", "workspace-a")
    private val repository = mockk<CloudRepository>()
    private val store = mockk<CloudStateStore>(relaxed = true)
    private val app = mockk<CodexQuotaApplication>()
    private var environments = emptyList<CloudEnvironment>()
    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        val accounts = mockk<CodexAccountRepository>()
        every { accounts.observeAccounts() } returns emptyFlow()
        every { app.repository } returns accounts
        every { app.cloudRepository } returns repository
        every { app.cloudStore } returns store
        every { repository.watchDetails(any(),any()) } returns emptyFlow()
        every { store.cache(any()) } returns null
        coEvery { repository.identity("account-a") } returns identity
        coEvery { repository.identity("account-b") } returns CloudIdentity("account-b", "workspace-b")
        coEvery { repository.environments(any()) } coAnswers { environments }
        coEvery { repository.tasks(any(), any()) } returns CloudPage(emptyList())
        coEvery { repository.details(identity, "task-a") } returns CloudDetails(CloudTask("task-a", "Fixture"), emptyList())
        coEvery { repository.preparationModels(any()) } returns listOf(
            CloudPreparationModel("model-a","Model A",listOf("low","high","ultra"),"low",true),
            CloudPreparationModel("model-b","Model B",listOf("medium","high"),"medium"))
        every { store.preparationChoice(any()) } returns null
    }
    @After fun finish() { Dispatchers.resetMain() }
    @Test fun `configuration return refreshes environment list even when task detail was open`() = runTest(dispatcher) {
        val vm = CloudConversationViewModel(app, dispatcher)
        vm.selectAccount(identity.accountId); runCurrent()
        vm.resumeReads(); advanceUntilIdle()
        vm.open("task-a"); advanceUntilIdle()
        vm.manageEnvironments(); advanceUntilIdle()
        assertEquals("task-a", vm.state.value.selectedTask)
        vm.setupOpened(); vm.pauseReads()
        environments = listOf(CloudEnvironment("created", "Mobile environment"))
        vm.resumeReads(); advanceUntilIdle()
        assertEquals("created", vm.state.value.cache?.selectedEnvironment)
        assertTrue(vm.state.value.managingEnvironments)
        assertEquals("task-a", vm.state.value.selectedTask)
        coVerify(exactly = 1) { repository.details(identity, "task-a") }
        vm.closeEnvironments()
        assertFalse(vm.state.value.managingEnvironments)
    }
    @Test fun `switching account preserves creation flow while clearing prior environment and draft`() = runTest(dispatcher) {
        val vm = CloudConversationViewModel(app, dispatcher)
        vm.selectAccount(identity.accountId); runCurrent()
        vm.newTask(); vm.manageEnvironments()
        vm.selectAccount("account-b"); runCurrent()
        assertEquals("workspace-b", vm.state.value.identity?.workspaceId)
        assertTrue(vm.state.value.newTask)
        assertTrue(vm.state.value.managingEnvironments)
        assertTrue(vm.state.value.cache?.environments.isNullOrEmpty())
        assertEquals("", vm.state.value.cache?.draft)
    }
    private fun config(version: Int = 1) = DurableCloudWire.config(DurableCloudWire.objectOf("""{"id":"w~asenvcfg_fixture","name":"Fixture","version_id":"w~cecfgver_$version","version_revision":$version,"latest_ready_version_id":"w~cecfgver_$version","thread_id":"setup-thread","repositories":[],"draft":{"id":"w~cecfgdraft_fixture","base_version_id":"w~cecfgver_1","revision":4,"network_policy":{"type":"restricted","presets":["package_managers"],"egress_rules":[]}}}"""))
    @Test fun `create records config before uncertain setup and cannot silently recreate it`() = runTest(dispatcher) {
        coEvery { repository.repositories(identity) } returns listOf(CloudGithubRepository("github-123","owner/repo","dev"))
        coEvery { repository.createConfig(identity,any(),any()) } returns config().copy(threadId = "")
        coEvery { repository.startThread(identity,any(),true) } returns "setup-thread"
        coEvery { repository.prepareTurn(identity,"setup-thread",any(),any()) } throws CloudException(CloudErrorKind.UNCERTAIN)
        every { store.claim(identity,any()) } returns true
        every { store.submission(identity,any()) } returns null
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent()
        vm.configureEnvironment(); advanceUntilIdle(); vm.editorName("Fixture"); vm.editorRepo("github-123")
        vm.createEnvironment(); advanceUntilIdle()
        assertEquals("w~asenvcfg_fixture",vm.state.value.editor?.config?.id)
        assertEquals("setupPending",vm.state.value.editor?.session?.stage)
        assertEquals(CloudErrorKind.UNCERTAIN,vm.state.value.error)
        vm.createEnvironment(); advanceUntilIdle()
        coVerify(exactly = 1) { repository.createConfig(identity,"Fixture",listOf(CloudRepositoryRef("github-123","dev"))) }
        verify { store.submitted(identity,any(),"w~asenvcfg_fixture") }
    }
    @Test fun `publication requires operation success completion and published version readback`() = runTest(dispatcher) {
        var published = false
        coEvery { repository.config(identity,any(),any()) } coAnswers { config(if(published) 2 else 1) }
        coEvery { repository.details(identity,"setup-thread") } returns CloudDetails(CloudTask("setup-thread","Setup","completed"),emptyList())
        coEvery { repository.beginPublish(identity,any(),4,any()) } returns buildJsonObject { put("id","ceop_fixture"); put("state","PENDING") }
        coEvery { repository.operation(identity,"ceop_fixture") } returns buildJsonObject { put("id","ceop_fixture"); put("state","SUCCEEDED") }
        coEvery { repository.completePublish(identity,any()) } coAnswers { published = true; config(2) }
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle()
        vm.publishEnvironment(); advanceUntilIdle()
        assertEquals("published",vm.state.value.editor?.session?.stage)
        assertEquals("w~cecfgver_2",vm.state.value.editor?.config?.versionId)
        assertNull(vm.state.value.error)
        coVerifyOrder {
            repository.beginPublish(identity,any(),4,any())
            repository.operation(identity,"ceop_fixture")
            repository.completePublish(identity,any())
            repository.config(identity,"w~asenvcfg_fixture","")
        }
    }
    @Test fun `accepted completion without version change remains unconfirmed`() = runTest(dispatcher) {
        coEvery { repository.config(identity,any(),any()) } returns config()
        coEvery { repository.details(identity,"setup-thread") } returns CloudDetails(CloudTask("setup-thread","Setup","completed"),emptyList())
        coEvery { repository.beginPublish(identity,any(),4,any()) } returns buildJsonObject { put("id","ceop_fixture") }
        coEvery { repository.operation(identity,"ceop_fixture") } returns buildJsonObject { put("state","SUCCEEDED") }
        coEvery { repository.completePublish(identity,any()) } returns config()
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle()
        vm.publishEnvironment(); advanceUntilIdle()
        assertEquals("completing",vm.state.value.editor?.session?.stage)
        assertEquals(CloudErrorKind.UNCERTAIN,vm.state.value.error)
    }
    @Test fun `silent setup hydration neither shows loading nor erases a typed reply`() = runTest(dispatcher) {
        coEvery { repository.config(identity,any(),any()) } returns config()
        coEvery { repository.details(identity,"setup-thread") } returns CloudDetails(CloudTask("setup-thread","Setup","completed"),emptyList())
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.resumeReads(); advanceUntilIdle()
        vm.manageEnvironments(); advanceUntilIdle(); vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle()
        vm.editorAnswer("Please keep the default setup")
        val gate = CompletableDeferred<CloudConfig>()
        coEvery { repository.config(identity,any(),any()) } coAnswers { gate.await() }
        vm.refresh(showProgress = false); runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals("Please keep the default setup",vm.state.value.editor?.answer)
        gate.complete(config()); advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals("Please keep the default setup",vm.state.value.editor?.answer)
    }
    @Test fun `preparation submits the confirmed model effort and retains project directory`() = runTest(dispatcher) {
        coEvery { repository.config(identity,any(),any()) } returns config()
        coEvery { repository.repositories(identity) } returns listOf(CloudGithubRepository("github-123","owner/repo","main"))
        coEvery { repository.details(identity,"setup-thread") } returns CloudDetails(CloudTask("setup-thread","Setup","completed"),emptyList())
        coEvery { repository.prepareTurn(identity,"setup-thread",any(),any()) } returns buildJsonObject { putJsonObject("turn") { put("id","turn-fixture") } }
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle()
        coVerify(exactly = 0) { repository.prepareTurn(any(),any(),any(),any()) }
        vm.preparationEffort("ultra"); vm.prepareEnvironment(); advanceUntilIdle()
        coVerify(exactly = 1) { repository.prepareTurn(identity,"setup-thread",any(),CloudPreparationChoice("model-a","ultra")) }
        assertEquals("owner/repo",vm.state.value.editor?.repositories?.single()?.name)
        assertEquals("ultra",vm.state.value.editor?.choice?.effort)
        verify { store.savePreparationChoice(identity,CloudPreparationChoice("model-a","ultra")) }
    }
    @Test fun `changing model rejects unsupported effort and account switch clears previous choice`() = runTest(dispatcher) {
        coEvery { repository.repositories(any()) } returns emptyList()
        every { store.preparationChoice(identity) } returns CloudPreparationChoice("model-a","ultra")
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.configureEnvironment(); advanceUntilIdle()
        assertEquals("ultra",vm.state.value.editor?.choice?.effort)
        vm.preparationModel("model-b"); vm.preparationEffort("ultra")
        assertEquals(CloudPreparationChoice("model-b","medium"),vm.state.value.editor?.choice)
        vm.selectAccount("account-b"); runCurrent(); vm.configureEnvironment(); advanceUntilIdle()
        assertEquals(CloudPreparationChoice("model-a","low"),vm.state.value.editor?.choice)
    }
    @Test fun `setup queues mobile input and explicit guidance targets the expected active turn`() = runTest(dispatcher) {
        coEvery { repository.config(identity,any(),any()) } returns config()
        coEvery { repository.repositories(identity) } returns emptyList()
        coEvery { repository.details(identity,"setup-thread") } returns CloudDetails(CloudTask("setup-thread","Setup","running"),emptyList(),activeTurnId = "active-turn")
        coEvery { repository.reply(identity,"setup-thread","Please add a check",any(),"active-turn") } returns "active-turn"
        every { store.claim(identity,any()) } returns true
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.resumeReads(); advanceUntilIdle()
        vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle(); vm.editorAnswer("Please add a check")
        vm.sendSetupAnswer(); advanceUntilIdle()
        coVerify(exactly = 0) { repository.reply(any(),any(),any(),any(),any()) }
        assertEquals("Please add a check",vm.state.value.cache?.pendingReplies?.single()?.text)
        vm.steerQueuedReply(); advanceUntilIdle()
        coVerify(exactly = 1) { repository.reply(identity,"setup-thread","Please add a check",CloudPreparationChoice("model-a","low"),"active-turn") }
        assertEquals("",vm.state.value.editor?.answer); assertNull(vm.state.value.error)
        verify { store.submitted(identity,any(),"active-turn") }
    }
    @Test fun `setup failed send preserves text and uncertain delivery is not replayed`() = runTest(dispatcher) {
        coEvery { repository.config(identity,any(),any()) } returns config()
        coEvery { repository.repositories(identity) } returns emptyList()
        coEvery { repository.details(identity,"setup-thread") } returns CloudDetails(CloudTask("setup-thread","Setup","completed"),emptyList())
        coEvery { repository.reply(identity,"setup-thread",any(),any(),"") } throws CloudException(CloudErrorKind.UNCERTAIN)
        every { store.claim(identity,any()) } returnsMany listOf(true,false)
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle()
        vm.editorAnswer("Keep my input"); vm.sendSetupAnswer(); advanceUntilIdle()
        assertEquals("Keep my input",vm.state.value.editor?.answer)
        vm.sendSetupAnswer(); advanceUntilIdle()
        coVerify(exactly = 1) { repository.reply(identity,"setup-thread","Keep my input",any(),"") }
        verify(exactly = 0) { store.rejected(identity,any()) }
    }
    @Test fun `ordinary cloud conversation supports another message after a completed reply`() = runTest(dispatcher) {
        coEvery { repository.details(identity,"task-a") } returns CloudDetails(CloudTask("task-a","Fixture","completed"),listOf(CloudMessage("assistant","First reply")))
        coEvery { repository.reply(identity,"task-a","Next question",any(),"") } returns "next-turn"
        every { store.claim(identity,any()) } returns true
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); advanceUntilIdle(); vm.resumeReads(); advanceUntilIdle(); vm.open("task-a"); advanceUntilIdle()
        vm.replyDraft("Next question"); vm.sendReply(); advanceUntilIdle()
        coVerify(exactly = 1) { repository.reply(identity,"task-a","Next question",CloudPreparationChoice("model-a","low"),"") }
        assertEquals("",vm.state.value.reply); assertFalse(vm.state.value.sending); assertNull(vm.state.value.error)
    }
    @Test fun `queued input starts once after completion and stopping preserves it without automatic send`() = runTest(dispatcher) {
        coEvery { repository.config(identity,any(),any()) } returns config()
        coEvery { repository.repositories(identity) } returns emptyList()
        var details = CloudDetails(CloudTask("setup-thread","Setup","running"),emptyList(),activeTurnId = "active-turn",latestTurnId = "active-turn")
        coEvery { repository.details(identity,"setup-thread") } coAnswers { details }
        coEvery { repository.reply(identity,"setup-thread","Next request",any(),"") } returns "next-turn"
        every { store.claim(identity,any()) } returns true
        val vm = CloudConversationViewModel(app,dispatcher)
        vm.selectAccount(identity.accountId); runCurrent(); vm.resumeReads(); advanceUntilIdle()
        vm.editEnvironment("w~asenvcfg_fixture"); advanceUntilIdle()
        vm.editorAnswer("Next request"); vm.sendSetupAnswer(); advanceUntilIdle()
        assertEquals(1,vm.state.value.cache?.pendingReplies?.size)
        details = details.copy(task = details.task.copy(status = "completed"),activeTurnId = "")
        vm.refresh(false); advanceUntilIdle(); vm.refresh(false); advanceUntilIdle()
        coVerify(exactly = 1) { repository.reply(identity,"setup-thread","Next request",any(),"") }
        assertTrue(vm.state.value.cache?.pendingReplies.isNullOrEmpty())
        assertEquals("Next request",vm.state.value.editor?.details?.messages?.last()?.text)
        details = CloudDetails(CloudTask("setup-thread","Setup","running"),emptyList(),activeTurnId = "next-turn",latestTurnId = "next-turn")
        vm.editorAnswer("Keep this draft"); vm.sendSetupAnswer(); advanceUntilIdle()
        coEvery { repository.stop(identity,"setup-thread","next-turn") } coAnswers {
            details = details.copy(task = details.task.copy(status = "failed"),activeTurnId = "")
            JsonObject(emptyMap())
        }
        vm.stop(); advanceUntilIdle(); vm.refresh(false); advanceUntilIdle()
        assertEquals("Keep this draft",vm.state.value.cache?.pendingReplies?.single()?.text)
        assertTrue(vm.state.value.cache?.pendingReplies?.single()?.attempted == true)
        coVerify(exactly = 0) { repository.reply(identity,"setup-thread","Keep this draft",any(),any()) }
        vm.editQueuedReply()
        assertEquals("Keep this draft",vm.state.value.editor?.answer)
        assertTrue(vm.state.value.cache?.pendingReplies.isNullOrEmpty())
    }

}
