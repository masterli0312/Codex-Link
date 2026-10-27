package com.codex.quota.ui.feature.taskhistory

import android.content.Context
import com.codex.quota.notifications.task.NtfyTaskClient

internal fun historyTaskClient(context: Context) = NtfyTaskClient(context = context.applicationContext)
