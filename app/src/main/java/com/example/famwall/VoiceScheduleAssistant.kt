package com.example.famwall

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import java.time.LocalDate

class VoiceScheduleAssistant(context: Context) {
    private val appContext = context.applicationContext

    fun answer(
        query: String,
        currentUserName: String,
        events: List<ScheduleEvent>,
        today: LocalDate,
        onSuccess: (VoiceAssistantAnswer) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            onFailure(IllegalArgumentException("Voice query is blank."))
            return
        }

        val functions = runCatching {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            FirebaseFunctions.getInstance(FUNCTIONS_REGION)
        }.getOrElse { exception ->
            onFailure(exception)
            return
        }

        functions
            .getHttpsCallable(FUNCTION_NAME)
            .call(buildPayload(trimmedQuery, currentUserName, events, today))
            .addOnSuccessListener { result ->
                val data = result.data as? Map<*, *>
                val answerText = data?.get("answerText") as? String
                if (answerText.isNullOrBlank()) {
                    onFailure(IllegalStateException("AI voice assistant returned an empty answer."))
                    return@addOnSuccessListener
                }

                onSuccess(VoiceAssistantAnswer(answerText.trim()))
            }
            .addOnFailureListener(onFailure)
    }

    private fun buildPayload(
        query: String,
        currentUserName: String,
        events: List<ScheduleEvent>,
        today: LocalDate,
    ): Map<String, Any?> {
        val sortedEvents = events
            .sortedBy { it.occurrenceDates().firstOrNull() ?: it.startDate ?: LocalDate.MAX }
            .take(MAX_SCHEDULE_COUNT)

        return mapOf(
            "query" to query,
            "today" to today.toString(),
            "currentUserName" to currentUserName,
            "schedules" to sortedEvents.map(::buildSchedulePayload),
        )
    }

    private fun buildSchedulePayload(event: ScheduleEvent): Map<String, Any?> {
        val occurrenceDates = event.occurrenceDates()
        return mapOf(
            "id" to event.id,
            "title" to event.title.trim(),
            "category" to event.category.label,
            "userName" to event.userName,
            "scheduleType" to event.scheduleType.key,
            "startDate" to event.startDate?.toString(),
            "endDate" to event.endDate?.toString(),
            "occurrenceDates" to occurrenceDates.map { it.toString() },
            "baseContent" to event.content.trim().take(MAX_CONTENT_CHARS),
            "baseOriginalContent" to event.originalContent.trim().take(MAX_CONTENT_CHARS),
            "occurrences" to occurrenceDates
                .take(MAX_OCCURRENCE_COUNT_PER_SCHEDULE)
                .map { date -> buildOccurrencePayload(event, date) },
        )
    }

    private fun buildOccurrencePayload(event: ScheduleEvent, date: LocalDate): Map<String, Any?> {
        return mapOf(
            "date" to date.toString(),
            "category" to event.categoryForDate(date).label,
            "content" to event.contentForDate(date).trim().take(MAX_CONTENT_CHARS),
            "originalContent" to event.originalContentForDate(date).trim().take(MAX_CONTENT_CHARS),
            "materialOrdered" to event.isMaterialOrderedForDate(date),
        )
    }

    companion object {
        private const val FUNCTIONS_REGION = "asia-northeast3"
        private const val FUNCTION_NAME = "answerScheduleAssistant"
        private const val MAX_SCHEDULE_COUNT = 200
        private const val MAX_OCCURRENCE_COUNT_PER_SCHEDULE = 90
        private const val MAX_CONTENT_CHARS = 700
    }
}

data class VoiceAssistantAnswer(
    val answerText: String,
)
