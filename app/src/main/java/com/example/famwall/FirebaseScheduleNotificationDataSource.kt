package com.example.famwall

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.time.LocalDate

class FirebaseScheduleNotificationDataSource private constructor(
    private val firestore: FirebaseFirestore,
) {
    fun fetchNotificationsOnce(onSuccess: (List<ScheduleNotification>) -> Unit) {
        notificationCollection()
            .get()
            .addOnSuccessListener { snapshot ->
                onSuccess(snapshot.documents.mapNotNull { document ->
                    fromFirestoreMap(document.id, document.data)
                })
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to fetch notifications from Firestore.", exception)
            }
    }

    fun listenForNotifications(onNotificationsChanged: (List<ScheduleNotification>) -> Unit): ListenerRegistration {
        return notificationCollection().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                Log.e(TAG, "Firestore notification listener failed.", error)
                return@addSnapshotListener
            }

            onNotificationsChanged(snapshot.documents.mapNotNull { document ->
                fromFirestoreMap(document.id, document.data)
            })
        }
    }

    fun saveNotification(notification: ScheduleNotification) {
        notificationCollection()
            .document(notification.id)
            .set(toFirestoreMap(notification), SetOptions.merge())
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to save notification to Firestore. id=${notification.id}", exception)
            }
    }

    fun markAsRead(notificationId: String, userName: String) {
        notificationCollection()
            .document(notificationId)
            .update(FIELD_READ_BY_USERS, FieldValue.arrayUnion(userName))
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to mark notification as read. id=$notificationId", exception)
            }
    }

    private fun notificationCollection() = firestore.collection(COLLECTION_SCHEDULE_NOTIFICATIONS)

    private fun toFirestoreMap(notification: ScheduleNotification): Map<String, Any?> {
        return mapOf(
            FIELD_ID to notification.id,
            FIELD_ACTION to notification.action.key,
            FIELD_ACTOR_USER_NAME to notification.actorUserName,
            FIELD_SCHEDULE_ID to notification.scheduleId,
            FIELD_SCHEDULE_TITLE to notification.scheduleTitle,
            FIELD_SCHEDULE_CONTENT to notification.scheduleContent,
            FIELD_SCHEDULE_CATEGORY to notification.scheduleCategory.key,
            FIELD_SCHEDULE_TYPE to notification.scheduleType.key,
            FIELD_START_DATE to notification.startDate?.toString(),
            FIELD_END_DATE to notification.endDate?.toString(),
            FIELD_SELECTED_DATES to notification.selectedDates.map { it.toString() },
            FIELD_SELECTED_DATE_CATEGORIES to notification.selectedDateCategories.mapKeys { it.key.toString() }.mapValues { it.value.key },
            FIELD_OCCURRENCE_DATES to notification.occurrenceDates.map { it.toString() },
            FIELD_CREATED_AT to notification.createdAt,
            FIELD_READ_BY_USERS to notification.readByUsers,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun fromFirestoreMap(documentId: String, data: Map<String, Any?>?): ScheduleNotification? {
        if (data == null) {
            return null
        }

        val selectedDateCategories = data[FIELD_SELECTED_DATE_CATEGORIES] as? Map<String, Any?>

        return ScheduleNotification(
            id = data[FIELD_ID] as? String ?: documentId,
            action = ScheduleNotificationAction.fromKey(data[FIELD_ACTION] as? String),
            actorUserName = data[FIELD_ACTOR_USER_NAME] as? String ?: "",
            scheduleId = data[FIELD_SCHEDULE_ID] as? String ?: "",
            scheduleTitle = data[FIELD_SCHEDULE_TITLE] as? String ?: "",
            scheduleContent = data[FIELD_SCHEDULE_CONTENT] as? String ?: "",
            scheduleCategory = ScheduleCategory.fromKey(data[FIELD_SCHEDULE_CATEGORY] as? String),
            scheduleType = ScheduleType.fromKey(data[FIELD_SCHEDULE_TYPE] as? String),
            startDate = parseDate(data[FIELD_START_DATE] as? String),
            endDate = parseDate(data[FIELD_END_DATE] as? String),
            selectedDates = parseDateList(data[FIELD_SELECTED_DATES] as? List<*>),
            selectedDateCategories = parseDateCategoryMap(selectedDateCategories),
            occurrenceDates = parseDateList(data[FIELD_OCCURRENCE_DATES] as? List<*>),
            createdAt = parseLong(data[FIELD_CREATED_AT]),
            readByUsers = parseStringList(data[FIELD_READ_BY_USERS] as? List<*>),
        )
    }

    private fun parseDate(rawDate: String?): LocalDate? {
        return runCatching {
            if (rawDate.isNullOrBlank()) null else LocalDate.parse(rawDate)
        }.getOrNull()
    }

    private fun parseDateList(rawDates: List<*>?): List<LocalDate> {
        if (rawDates == null) {
            return emptyList()
        }

        return rawDates.mapNotNull { rawDate -> parseDate(rawDate as? String) }
    }

    private fun parseDateCategoryMap(rawCategories: Map<String, Any?>?): Map<LocalDate, ScheduleCategory> {
        if (rawCategories == null) {
            return emptyMap()
        }

        return buildMap {
            rawCategories.forEach { (rawDate, rawCategory) ->
                val date = parseDate(rawDate) ?: return@forEach
                put(date, ScheduleCategory.fromKey(rawCategory as? String))
            }
        }
    }

    private fun parseStringList(rawValues: List<*>?): List<String> {
        if (rawValues == null) {
            return emptyList()
        }

        return rawValues.mapNotNull { it as? String }.filter { it.isNotBlank() }
    }

    private fun parseLong(rawValue: Any?): Long {
        return when (rawValue) {
            is Long -> rawValue
            is Int -> rawValue.toLong()
            is Double -> rawValue.toLong()
            is Number -> rawValue.toLong()
            else -> System.currentTimeMillis()
        }
    }

    companion object {
        private const val COLLECTION_SCHEDULE_NOTIFICATIONS = "scheduleNotifications"
        private const val TAG = "FamWallNotifications"

        private const val FIELD_ID = "id"
        private const val FIELD_ACTION = "action"
        private const val FIELD_ACTOR_USER_NAME = "actorUserName"
        private const val FIELD_SCHEDULE_ID = "scheduleId"
        private const val FIELD_SCHEDULE_TITLE = "scheduleTitle"
        private const val FIELD_SCHEDULE_CONTENT = "scheduleContent"
        private const val FIELD_SCHEDULE_CATEGORY = "scheduleCategory"
        private const val FIELD_SCHEDULE_TYPE = "scheduleType"
        private const val FIELD_START_DATE = "startDate"
        private const val FIELD_END_DATE = "endDate"
        private const val FIELD_SELECTED_DATES = "selectedDates"
        private const val FIELD_SELECTED_DATE_CATEGORIES = "selectedDateCategories"
        private const val FIELD_OCCURRENCE_DATES = "occurrenceDates"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_READ_BY_USERS = "readByUsers"

        fun create(context: Context): FirebaseScheduleNotificationDataSource? {
            return runCatching {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context) ?: return null
                }
                FirebaseScheduleNotificationDataSource(FirebaseFirestore.getInstance())
            }.onFailure { exception ->
                Log.e(TAG, "Firebase initialization failed.", exception)
            }.getOrNull()
        }
    }
}
