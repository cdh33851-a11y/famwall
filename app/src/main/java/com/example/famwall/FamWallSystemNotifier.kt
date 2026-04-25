package com.example.famwall

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class FamWallSystemNotifier(private val context: Context) {
    init {
        createNotificationChannel()
    }

    fun showScheduleNotification(notification: ScheduleNotification) {
        if (!canPostNotifications()) {
            return
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            Intent(context, NotificationListActivity::class.java).apply {
                putExtra(NotificationListActivity.EXTRA_NOTIFICATION_ID, notification.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val systemNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(notification.messageTitle())
            .setContentText(notification.dateSummary())
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${notification.dateSummary()}\n${notification.displayTitle()}"),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context)
            .notify(notification.id.hashCode(), systemNotification)
    }

    fun showRemoteScheduleNotification(notification: ScheduleNotification) {
        showScheduleNotification(notification)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "일정 알림",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "가족도배 일정 추가, 수정, 삭제 알림"
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "schedule_changes"
    }
}
