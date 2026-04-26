package com.example.famwall

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import java.time.LocalDate

class AiScheduleContentOrganizer(context: Context) {
    private val appContext = context.applicationContext

    fun organize(
        event: ScheduleEvent,
        selectedDate: LocalDate,
        onSuccess: (AiOrganizedScheduleContent) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
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
            .call(buildPayload(event, selectedDate))
            .addOnSuccessListener { result ->
                val data = result.data as? Map<*, *>
                val formattedText = data?.get("formattedText") as? String
                if (formattedText.isNullOrBlank()) {
                    onFailure(IllegalStateException("AI 정리 결과가 비어 있습니다."))
                    return@addOnSuccessListener
                }

                val warnings = (data["warnings"] as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()
                onSuccess(AiOrganizedScheduleContent(formattedText.trim(), warnings))
            }
            .addOnFailureListener(onFailure)
    }

    private fun buildPayload(event: ScheduleEvent, selectedDate: LocalDate): Map<String, Any?> {
        val selectedContent = event.contentForDate(selectedDate).trim()
        val fallbackContent = event.content.trim()
        val allDateContents = event.dateContents
            .mapKeys { it.key.toString() }
            .filterValues { it.isNotBlank() }

        return mapOf(
            "scheduleId" to event.id,
            "title" to event.title,
            "selectedDate" to selectedDate.toString(),
            "selectedContent" to selectedContent.ifBlank { fallbackContent },
            "baseContent" to fallbackContent,
            "category" to event.categoryForDate(selectedDate).label,
            "userName" to event.userName,
            "scheduleType" to event.scheduleType.key,
            "startDate" to event.startDate?.toString(),
            "endDate" to event.endDate?.toString(),
            "occurrenceDates" to event.occurrenceDates().map { it.toString() },
            "dateContents" to allDateContents,
        )
    }

    companion object {
        private const val FUNCTIONS_REGION = "asia-northeast3"
        private const val FUNCTION_NAME = "organizeScheduleContent"
    }
}

data class AiOrganizedScheduleContent(
    val formattedText: String,
    val warnings: List<String>,
)
