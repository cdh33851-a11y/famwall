package com.example.famwall

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import java.time.LocalDate

class ScheduleRepository(
    context: Context,
    private val onRemoteEventsChanged: (() -> Unit)? = null,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val events = mutableListOf<ScheduleEvent>()
    private val remoteDataSource = FirebaseScheduleDataSource.create(context)
    private var remoteListener: ListenerRegistration? = null

    init {
        loadEvents()
        startRemoteSync()
    }

    fun getAllEvents(): List<ScheduleEvent> = events.map { it.copy() }

    fun getEventById(eventId: String?): ScheduleEvent? {
        if (eventId == null) {
            return null
        }
        return events.firstOrNull { it.id == eventId }?.copy()
    }

    fun getEventsForDate(date: LocalDate): List<ScheduleEvent> {
        return events
            .filter { it.occursOn(date) }
            .sortedWith(compareBy<ScheduleEvent> { it.categoryForDate(date).label }.thenBy { it.title })
            .map { it.copy() }
    }

    fun addEvent(event: ScheduleEvent) {
        saveEvent(event)
    }

    fun updateEvent(event: ScheduleEvent) {
        saveEvent(event)
    }

    fun setMaterialOrdered(eventId: String, date: LocalDate, isOrdered: Boolean) {
        val existingIndex = events.indexOfFirst { it.id == eventId }
        if (existingIndex < 0) {
            return
        }

        val event = events[existingIndex]
        if (!event.occursOn(date)) {
            return
        }

        val updatedDates = event.materialOrderedDates.toMutableSet()
        if (isOrdered) {
            updatedDates.add(date)
        } else {
            updatedDates.remove(date)
        }

        events[existingIndex] = event.copy(
            materialOrderedDates = updatedDates.toSortedSet().toList(),
            updatedAt = System.currentTimeMillis(),
        )
        persistEvents()
        remoteDataSource?.saveEvent(events[existingIndex])
    }

    fun saveEvent(event: ScheduleEvent) {
        val eventToSave = event.copy(updatedAt = System.currentTimeMillis())
        val existingIndex = events.indexOfFirst { it.id == event.id }
        if (existingIndex >= 0) {
            events[existingIndex] = eventToSave
        } else {
            events.add(eventToSave)
        }
        persistEvents()
        remoteDataSource?.saveEvent(eventToSave)
    }

    fun deleteEvent(eventId: String) {
        events.removeAll { it.id == eventId }
        persistEvents()
        remoteDataSource?.deleteEvent(eventId)
    }

    fun close() {
        remoteListener?.remove()
        remoteListener = null
    }

    private fun startRemoteSync() {
        val dataSource = remoteDataSource ?: return
        if (onRemoteEventsChanged == null) {
            return
        }

        dataSource.fetchEventsOnce { remoteEvents ->
            val hasCompletedInitialSync = preferences.getBoolean(KEY_FIREBASE_INITIAL_SYNC_COMPLETED, false)
            if (hasCompletedInitialSync) {
                replaceEvents(remoteEvents.sortedWith(compareBy<ScheduleEvent> { it.startDate }.thenBy { it.title }))
                onRemoteEventsChanged.invoke()
                startRemoteListener(dataSource)
                return@fetchEventsOnce
            }

            val localEvents = events.toList()
            val mergedEvents = mergeEventsByLatestUpdate(localEvents, remoteEvents)
            val remoteEventsById = remoteEvents.associateBy { it.id }
            replaceEvents(mergedEvents)
            preferences.edit().putBoolean(KEY_FIREBASE_INITIAL_SYNC_COMPLETED, true).apply()

            mergedEvents.forEach { event ->
                val remoteUpdatedAt = remoteEventsById[event.id]?.updatedAt ?: Long.MIN_VALUE
                if (event.updatedAt > remoteUpdatedAt) {
                    dataSource.saveEvent(event)
                }
            }

            onRemoteEventsChanged.invoke()
            startRemoteListener(dataSource)
        }
    }

    private fun startRemoteListener(dataSource: FirebaseScheduleDataSource) {
        remoteListener?.remove()
        remoteListener = dataSource.listenForEvents { remoteEvents ->
            replaceEvents(remoteEvents.sortedWith(compareBy<ScheduleEvent> { it.startDate }.thenBy { it.title }))
            onRemoteEventsChanged?.invoke()
        }
    }

    private fun mergeEventsByLatestUpdate(
        localEvents: List<ScheduleEvent>,
        remoteEvents: List<ScheduleEvent>,
    ): List<ScheduleEvent> {
        val mergedEvents = linkedMapOf<String, ScheduleEvent>()
        (remoteEvents + localEvents).forEach { event ->
            val currentEvent = mergedEvents[event.id]
            if (currentEvent == null || event.updatedAt >= currentEvent.updatedAt) {
                mergedEvents[event.id] = event
            }
        }

        return mergedEvents.values.sortedWith(compareBy<ScheduleEvent> { it.startDate }.thenBy { it.title })
    }

    private fun replaceEvents(newEvents: List<ScheduleEvent>) {
        events.clear()
        events.addAll(newEvents)
        persistEvents()
    }

    private fun loadEvents() {
        events.clear()
        val rawEvents = preferences.getString(KEY_SCHEDULE_EVENTS, "[]") ?: "[]"
        runCatching {
            val jsonArray = JSONArray(rawEvents)
            for (index in 0 until jsonArray.length()) {
                events.add(ScheduleEvent.fromJson(jsonArray.getJSONObject(index)))
            }
        }.onFailure {
            events.clear()
        }
    }

    private fun persistEvents() {
        val jsonArray = JSONArray()
        events.forEach { event ->
            runCatching {
                jsonArray.put(event.toJson())
            }
        }
        preferences.edit().putString(KEY_SCHEDULE_EVENTS, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SCHEDULE_EVENTS = "schedule_events"
        private const val KEY_FIREBASE_INITIAL_SYNC_COMPLETED = "firebase_initial_sync_completed"
    }
}
