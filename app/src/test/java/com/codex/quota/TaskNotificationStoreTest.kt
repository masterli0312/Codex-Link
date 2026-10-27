package com.codex.quota

import android.content.Context
import android.content.SharedPreferences
import com.codex.quota.notifications.task.TaskNotificationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotificationStoreTest {
    @Test fun savingTheSameAddressDoesNotDisableNotificationsOrRewriteSettings() {
        val endpoint = "https://ntfy.sh/test-topic"
        val preferences = mockk<SharedPreferences>()
        val context = mockk<Context>()
        every { context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean("enabled", false) } returns true
        every { preferences.getBoolean(any(), false) } returns false
        every { preferences.getBoolean("enabled", false) } returns true
        every { preferences.getString("endpoint", "") } returns endpoint
        every { preferences.getString(any(), "") } returns ""
        every { preferences.getString("endpoint", "") } returns endpoint
        every { preferences.getLong("enabled_at", 0L) } returns 123L
        val store = TaskNotificationStore(context)
        store.saveEndpoint(endpoint)
        assertTrue(store.read().enabled)
        verify(exactly = 0) { preferences.edit() }
    }
}
