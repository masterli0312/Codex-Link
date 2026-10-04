package com.codex.quota.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.codex.quota.R
import com.codex.quota.notifications.withAppIcon
import com.codex.quota.domain.model.CodexAccount
import com.codex.quota.ui.MainActivity
import com.codex.quota.ui.util.localizedAccountNickname

class AccountReminderNotificationManager(private val context: Context) {
    private val localizedContext = ContextCompat.getContextForLanguage(context)
    private val manager = NotificationManagerCompat.from(context)

    fun canNotify(): Boolean = manager.areNotificationsEnabled()

    fun showWeeklyReset(account: CodexAccount) = show(account, "weekly_reset", R.string.weekly_reset_reminder,
        localizedContext.getString(R.string.weekly_reset_notification_title),
        localizedContext.getString(R.string.weekly_reset_notification_text, localizedAccountNickname(localizedContext, account)))

    fun showResetOpportunity(account: CodexAccount, count: Int) = show(account, "reset_opportunity", R.string.reset_alert,
        localizedContext.getString(R.string.reset_opportunity_notification_title),
        localizedContext.getString(R.string.reset_opportunity_notification_text, localizedAccountNickname(localizedContext, account), count))

    private fun show(account: CodexAccount, kind: String, channelName: Int, title: String, text: String): Boolean {
        if (!canNotify()) return false
        val channelId = "channel_" + kind
        val channel = NotificationChannel(channelId, localizedContext.getString(channelName), NotificationManager.IMPORTANCE_DEFAULT)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("codexquota://account/" + account.id), context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val key = kind + "_" + account.id
        val pending = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId).withAppIcon(context)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(pending).build()
        return try { manager.notify(key, 3100, notification); true } catch (_: SecurityException) { false }
    }
}
