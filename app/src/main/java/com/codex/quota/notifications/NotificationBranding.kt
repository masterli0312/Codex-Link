package com.codex.quota.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.codex.quota.R

/** Use the launcher artwork on notification cards and its monochrome mark in the status bar. */
internal object NotificationBranding {
    @Volatile private var launcherBitmap: Bitmap? = null

    fun appIcon(context: Context): Bitmap = launcherBitmap ?: synchronized(this) {
        launcherBitmap ?: Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
            val icon = requireNotNull(context.getDrawable(context.applicationInfo.icon))
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(Canvas(bitmap))
            launcherBitmap = bitmap
        }
    }

    fun refreshExisting(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            manager.activeNotifications.forEach { active ->
                val notification = active.notification
                val updated = Notification.Builder.recoverBuilder(context, notification)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setLargeIcon(appIcon(context))
                    .addExtras(Bundle().apply { putParcelable("android.appInfo", context.applicationInfo) })
                    .setOnlyAlertOnce(true)
                notification.publicVersion?.let { public ->
                    updated.setPublicVersion(Notification.Builder.recoverBuilder(context, public)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setLargeIcon(appIcon(context))
                        .addExtras(Bundle().apply { putParcelable("android.appInfo", context.applicationInfo) })
                        .build())
                }
                manager.notify(active.tag, active.id, updated.build())
            }
        }
    }
}

internal fun NotificationCompat.Builder.withAppIcon(context: Context): NotificationCompat.Builder = apply {
    setSmallIcon(R.drawable.ic_notification)
    setLargeIcon(NotificationBranding.appIcon(context))
}
