package com.example.famwall

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.time.LocalDate

class FirebaseScheduleDataSource private constructor(
    private val firestore: FirebaseFirestore,
) {
    fun fetchEventsOnce(onSuccess: (List<ScheduleEvent>) -> Unit) {
        scheduleCollection()
            .get()
            .addOnSuccessListener { snapshot ->
                onSuccess(snapshot.documents.mapNotNull { document ->
                    fromFirestoreMap(document.id, document.data)
                })
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to fetch schedules from Firestore.", exception)
            }
    }

    fun listenForEvents(onEventsChanged: (List<ScheduleEvent>) -> Unit): ListenerRegistration {
        return scheduleCollection().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                Log.e(TAG, "Firestore schedule listener failed.", error)
                return@addSnapshotListener
            }

            onEventsChanged(snapshot.documents.mapNotNull { document ->
                fromFirestoreMap(document.id, document.data)
            })
        }
    }

    fun saveEvent(event: ScheduleEvent) {
        scheduleCollection()
            .document(event.id)
            .set(toFirestoreMap(event))
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to save schedule to Firestore. id=${event.id}", exception)
            }
    }

    fun deleteEvent(eventId: String) {
        scheduleCollection()
            .document(eventId)
            .delete()
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to delete schedule from Firestore. id=$eventId", exception)
            }
    }

    private fun scheduleCollection() = firestore.collection(COLLECTION_SCHEDULE_EVENTS)

    private fun toFirestoreMap(event: ScheduleEvent): Map<String, Any?> {
        return mapOf(
            FIELD_ID to event.id,
            FIELD_TITLE to event.title,
            FIELD_CONTENT to event.content,
            FIELD_CATEGORY to event.category.key,
            FIELD_USER_NAME to event.userName,
            FIELD_START_DATE to event.startDate?.toString(),
            FIELD_END_DATE to event.endDate?.toString(),
            FIELD_SELECTED_DATES to event.selectedDates.map { it.toString() },
            FIELD_SELECTED_DATE_CATEGORIES to event.selectedDateCategories.mapKeys { it.key.toString() }.mapValues { it.value.key },
            FIELD_DATE_CONTENTS to event.dateContents.mapKeys { it.key.toString() },
            FIELD_MATERIAL_ORDERED_DATES to event.materialOrderedDates.map { it.toString() },
            FIELD_SCHEDULE_TYPE to event.scheduleType.key,
            FIELD_CREATED_AT to event.createdAt,
            FIELD_UPDATED_AT to event.updatedAt,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun fromFirestoreMap(documentId: String, data: Map<String, Any?>?): ScheduleEvent? {
        if (data == null) {
            return null
        }

        val id = data[FIELD_ID] as? String ?: documentId
        val categoryKey = data[FIELD_CATEGORY] as? String
        val scheduleTypeKey = data[FIELD_SCHEDULE_TYPE] as? String
        val selectedDateCategories = data[FIELD_SELECTED_DATE_CATEGORIES] as? Map<String, Any?>
        val dateContents = data[FIELD_DATE_CONTENTS] as? Map<String, Any?>

        return ScheduleEvent(
            id = id,
            title = data[FIELD_TITLE] as? String ?: "",
            content = data[FIELD_CONTENT] as? String ?: "",
            category = ScheduleCategory.fromKey(categoryKey),
            userName = data[FIELD_USER_NAME] as? String ?: "",
            startDate = parseDate(data[FIELD_START_DATE] as? String),
            endDate = parseDate(data[FIELD_END_DATE] as? String),
            selectedDates = parseDateList(data[FIELD_SELECTED_DATES] as? List<*>),
            selectedDateCategories = parseDateCategoryMap(selectedDateCategories),
            dateContents = parseDateContentMap(dateContents),
            materialOrderedDates = parseDateList(data[FIELD_MATERIAL_ORDERED_DATES] as? List<*>),
            scheduleType = ScheduleType.fromKey(scheduleTypeKey),
            createdAt = parseLong(data[FIELD_CREATED_AT]),
            updatedAt = parseLong(data[FIELD_UPDATED_AT]),
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

    private fun parseDateContentMap(rawContents: Map<String, Any?>?): Map<LocalDate, String> {
        if (rawContents == null) {
            return emptyMap()
        }

        return buildMap {
            rawContents.forEach { (rawDate, rawContent) ->
                val date = parseDate(rawDate) ?: return@forEach
                put(date, rawContent as? String ?: "")
            }
        }
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
        private const val COLLECTION_SCHEDULE_EVENTS = "scheduleEvents"
        private const val TAG = "FamWallFirebase"
        private const val FIELD_ID = "id"
        private const val FIELD_TITLE = "title"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_CATEGORY = "category"
        private const val FIELD_USER_NAME = "userName"
        private const val FIELD_START_DATE = "startDate"
        private const val FIELD_END_DATE = "endDate"
        private const val FIELD_SELECTED_DATES = "selectedDates"
        private const val FIELD_SELECTED_DATE_CATEGORIES = "selectedDateCategories"
        private const val FIELD_DATE_CONTENTS = "dateContents"
        private const val FIELD_MATERIAL_ORDERED_DATES = "materialOrderedDates"
        private const val FIELD_SCHEDULE_TYPE = "scheduleType"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"

        fun create(context: Context): FirebaseScheduleDataSource? {
            return runCatching {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context) ?: return null
                }
                FirebaseScheduleDataSource(FirebaseFirestore.getInstance())
            }.onFailure { exception ->
                Log.e(TAG, "Firebase initialization failed.", exception)
            }.getOrNull()
        }
    }
}
