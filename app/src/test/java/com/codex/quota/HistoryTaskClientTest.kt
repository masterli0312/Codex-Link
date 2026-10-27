package com.codex.quota

import android.content.Context
import com.codex.quota.notifications.task.RelayRouting
import com.codex.quota.notifications.task.TaskAttachment
import com.codex.quota.ui.feature.taskhistory.historyTaskClient
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTaskClientTest {
    @Test fun manualDownloadUsesApplicationContextForMigratedRelayValidation() = runTest {
        val context = mockk<Context>()
        val appContext = mockk<Context>()
        every { context.applicationContext } returns appContext
        mockkObject(RelayRouting)
        try {
            every { RelayRouting.interceptor(any()) } answers { callOriginal() }
            every { RelayRouting.attachmentUrl(appContext, "https://old.example/topic", "https://new.example/file/x") } returns null
            val client = historyTaskClient(context)
            assertNull(client.downloadSnapshot("https://old.example/topic", TaskAttachment("https://new.example/file/x", 0, Long.MAX_VALUE), "key", "id"))
            verify(exactly = 1) { RelayRouting.attachmentUrl(appContext, "https://old.example/topic", "https://new.example/file/x") }
        } finally { unmockkObject(RelayRouting) }
    }
}
