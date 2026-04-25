package com.example.famwall

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import java.time.LocalDate

class ScheduleNotificationRepository(
    private val context: Context,
    private val currentUserName: String,
    private val onNotificationsChanged: (() -> Unit)? = null,
    private val onNewNotification: ((ScheduleNotification) -> Unit)? = null,
) {
    private val notifications = mutableListOf<ScheduleNotification>()
    private val remoteDataSource = FirebaseScheduleNotificationDataSource.create(context)
    private var remoteListener: ListenerRegistration? = null
    private var listenerStartedAt: Long = System.currentTimeMillis()

    init {
        loadNotifications()
        startRemoteSync()
    }

    fun getVisibleNotifications(): List<ScheduleNotification> {
        return notifications
            .filter { it.isVisibleTo(currentUserName) }
            .sortedByDescending { it.createdAt }
            .map { it.copy() }
    }

    fun getUnreadNotifications(): List<ScheduleNotification> {
        return getVisibleNotifications().filterNot { it.isReadBy(currentUserName) }
    }

    fun getUnreadDates(): Set<LocalDate> {
        return getUnreadNotifications()
            .flatMap { notification -> notification.occurrenceDates.ifEmpty { listOf(notification.primaryDate()) } }
            .toSet()
    }

    fun getNotificationById(notificationId: String?): ScheduleNotification? {
        if (notificationId == null) {
            return null
        }
        return notifications.firstOrNull { it.id == notificationId }?.copy()
    }

    fun markAsRead(notificationId: String) {
        val index = notifications.indexOfFirst { it.id == notificationId }
        if (index < 0) {
            return
        }

        val notification = notifications[index]
        if (notification.isReadBy(currentUserName)) {
            return
        }

        val updatedReadUsers = (notification.readByUsers + currentUserName).distinct()
        notifications[index] = notification.copy(readByUsers = updatedReadUsers)
        persistNotifications()
        remoteDataSource?.markAsRead(notificationId, currentUserName)
        onNotificationsChanged?.invoke()
    }

    fun close() {
        remoteListener?.remove()
        remoteListener = null
    }

    private fun startRemoteSync() {
        val dataSource = remoteDataSource ?: return
        dataSource.fetchNotificationsOnce { remoteNotifications ->
            mergeAndReplace(remoteNotifications)
            onNotificationsChanged?.invoke()
            startRemoteListener(dataSource)
        }
    }

    private fun startRemoteListener(dataSource: FirebaseScheduleNotificationDataSource) {
        remoteListener?.remove()
        listenerStartedAt = System.currentTimeMillis()
        remoteListener = dataSource.listenForNotifications { remoteNotifications ->
            val previousIds = notifications.map { it.id }.toSet()
            mergeAndReplace(remoteNotifications)

            val newNotifications = notifications
                .filter { notification -> notification.id !in previousIds }
                .filter { notification -> notification.createdAt >= listenerStartedAt - NEW_NOTIFICATION_GRACE_MS }
                .filter { notification -> notification.isVisibleTo(currentUserName) }
                .filterNot { notification -> notification.isReadBy(currentUserName) }
                .sortedBy { it.createdAt }

            onNotificationsChanged?.invoke()
            newNotifications.forEach { notification -> onNewNotification?.invoke(notification.copy()) }
        }
    }

    private fun mergeAndReplace(remoteNotifications: List<ScheduleNotification>) {
        val mergedNotifications = linkedMapOf<String, ScheduleNotification>()
        (notifications + remoteNotifications).forEach { notification ->
            val current = mergedNotifications[notification.id]
            if (current == null) {
                mergedNotifications[notification.id] = notification
                return@forEach
            }

            mergedNotifications[notification.id] = current.copy(
                readByUsers = (current.readByUsers + notification.readByUsers).distinct(),
            )
        }

        notifications.clear()
        notifications.addAll(
            mergedNotifications.values
                .sortedByDescending { it.createdAt }
                .take(MAX_LOCAL_NOTIFICATIONS),
        )
        persistNotifications()
    }

    private fun loadNotifications() {
        notifications.clear()
        notifications.addAll(loadLocalNotifications(context))
    }

    private fun persistNotifications() {
        persistLocalNotifications(context, notifications)
    }

    companion object {
        private const val PREFS_NAME = "famwall_notification_prefs"
        private const val KEY_NOTIFICATIONS = "schedule_notifications"
        private const val MAX_LOCAL_NOTIFICATIONS = 300
        private const val NEW_NOTIFICATION_GRACE_MS = 2_000L

        fun recordScheduleChange(
            context: Context,
            action: ScheduleNotificationAction,
            event: ScheduleEvent,
        ) {
            val notification = ScheduleNotification.fromEvent(action, event)
            saveLocalNotification(context, notification)
            FirebaseScheduleNotificationDataSource.create(context)?.saveNotification(notification)
        }

        fun recordRemoteNotification(
            context: Context,
            notification: ScheduleNotification,
        ) {
            saveLocalNotification(context, notification)
        }

        private fun saveLocalNotification(
            context: Context,
            notification: ScheduleNotification,
        ) {
            val notifications = loadLocalNotifications(context).toMutableList()
            notifications.removeAll { it.id == notification.id }
            notifications.add(0, notification)
            persistLocalNotifications(context, notifications.take(MAX_LOCAL_NOTIFICATIONS))
        }

        private fun loadLocalNotifications(context: Context): List<ScheduleNotification> {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawNotifications = preferences.getString(KEY_NOTIFICATIONS, "[]") ?: "[]"
            return runCatching {
                val jsonArray = JSONArray(rawNotifications)
                buildList {
                    for (index in 0 until jsonArray.length()) {
                        add(ScheduleNotification.fromJson(jsonArray.getJSONObject(index)))
                    }
                }
            }.getOrDefault(emptyList())
                .sortedByDescending { it.createdAt }
                .take(MAX_LOCAL_NOTIFICATIONS)
        }

        private fun persistLocalNotifications(
            context: Context,
            notifications: List<ScheduleNotification>,
        ) {
            val jsonArray = JSONArray()
            notifications
                .sortedByDescending { it.createdAt }
                .take(MAX_LOCAL_NOTIFICATIONS)
                .forEach { notification ->
                    runCatching { jsonArray.put(notification.toJson()) }
                }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NOTIFICATIONS, jsonArray.toString())
                .apply()
        }
    }
}
