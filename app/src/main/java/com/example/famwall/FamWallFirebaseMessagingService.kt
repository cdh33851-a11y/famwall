package com.example.famwall

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FamWallFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DeviceTokenRepository(this).saveToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data.isEmpty()) {
            return
        }

        runCatching {
            val notification = ScheduleNotification.fromFcmData(message.data)
            ScheduleNotificationRepository.recordRemoteNotification(this, notification)
            FamWallSystemNotifier(this).showRemoteScheduleNotification(notification)
        }.onFailure { exception ->
            Log.e(TAG, "Failed to handle FCM schedule notification.", exception)
        }
    }

    companion object {
        private const val TAG = "FamWallFcmService"
    }
}
