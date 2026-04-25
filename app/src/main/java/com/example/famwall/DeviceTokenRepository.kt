package com.example.famwall

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest

data class DeviceTokenRecord(
    val id: String,
    val token: String,
    val userName: String,
    val appVersion: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

class DeviceTokenRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val remoteDataSource = FirebaseDeviceTokenDataSource.create(context)

    fun registerCurrentToken(userName: String) {
        if (userName.isBlank()) {
            return
        }

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> saveToken(token, userName) }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to get FCM token.", exception)
            }
    }

    fun saveToken(token: String, userName: String? = getSelectedUserName()) {
        if (token.isBlank() || userName.isNullOrBlank()) {
            return
        }

        preferences.edit()
            .putString(KEY_LAST_FCM_TOKEN, token)
            .putString(KEY_LAST_FCM_TOKEN_USER, userName)
            .apply()

        remoteDataSource?.saveToken(
            DeviceTokenRecord(
                id = token.sha256(),
                token = token,
                userName = userName,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
    }

    private fun getSelectedUserName(): String? {
        return context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_USER, null)
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FamWallFcmToken"
        private const val PREFS_NAME = "famwall_fcm_prefs"
        private const val APP_PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val KEY_LAST_FCM_TOKEN = "last_fcm_token"
        private const val KEY_LAST_FCM_TOKEN_USER = "last_fcm_token_user"
    }
}
