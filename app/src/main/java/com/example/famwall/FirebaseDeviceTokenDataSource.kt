package com.example.famwall

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class FirebaseDeviceTokenDataSource private constructor(
    private val firestore: FirebaseFirestore,
) {
    fun saveToken(tokenRecord: DeviceTokenRecord) {
        tokenCollection()
            .document(tokenRecord.id)
            .set(toFirestoreMap(tokenRecord), SetOptions.merge())
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to save FCM token.", exception)
            }
    }

    private fun tokenCollection() = firestore.collection(COLLECTION_DEVICE_TOKENS)

    private fun toFirestoreMap(tokenRecord: DeviceTokenRecord): Map<String, Any?> {
        return mapOf(
            FIELD_ID to tokenRecord.id,
            FIELD_TOKEN to tokenRecord.token,
            FIELD_USER_NAME to tokenRecord.userName,
            FIELD_PLATFORM to "android",
            FIELD_APP_VERSION to tokenRecord.appVersion,
            FIELD_UPDATED_AT to tokenRecord.updatedAt,
        )
    }

    companion object {
        private const val COLLECTION_DEVICE_TOKENS = "deviceTokens"
        private const val TAG = "FamWallFcmToken"
        private const val FIELD_ID = "id"
        private const val FIELD_TOKEN = "token"
        private const val FIELD_USER_NAME = "userName"
        private const val FIELD_PLATFORM = "platform"
        private const val FIELD_APP_VERSION = "appVersion"
        private const val FIELD_UPDATED_AT = "updatedAt"

        fun create(context: Context): FirebaseDeviceTokenDataSource? {
            return runCatching {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context) ?: return null
                }
                FirebaseDeviceTokenDataSource(FirebaseFirestore.getInstance())
            }.onFailure { exception ->
                Log.e(TAG, "Firebase initialization failed.", exception)
            }.getOrNull()
        }
    }
}
