package com.example.famwall

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class ScheduleNotification(
    val id: String = UUID.randomUUID().toString(),
    val action: ScheduleNotificationAction = ScheduleNotificationAction.ADDED,
    val actorUserName: String = "",
    val scheduleId: String = "",
    val scheduleTitle: String = "",
    val scheduleContent: String = "",
    val scheduleCategory: ScheduleCategory = ScheduleCategory.WALLPAPER,
    val scheduleType: ScheduleType = ScheduleType.CONTINUOUS,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val selectedDates: List<LocalDate> = emptyList(),
    val selectedDateCategories: Map<LocalDate, ScheduleCategory> = emptyMap(),
    val occurrenceDates: List<LocalDate> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val readByUsers: List<String> = emptyList(),
) {
    fun isVisibleTo(userName: String): Boolean = actorUserName != userName

    fun isReadBy(userName: String): Boolean = readByUsers.contains(userName)

    fun primaryDate(): LocalDate {
        return occurrenceDates.firstOrNull() ?: startDate ?: LocalDate.now()
    }

    fun displayTitle(): String {
        return scheduleTitle.trim()
            .ifEmpty { scheduleContent.trim() }
            .ifEmpty { "일정" }
    }

    fun messageTitle(): String = "[${actorUserName}] 님의 일정이 ${action.messageLabel}."

    fun dateSummary(): String {
        val dates = occurrenceDates.distinct().sorted()
        if (scheduleType == ScheduleType.SELECTED && dates.size > 1) {
            return "${formatFullDate(dates.first())} 외 ${dates.size - 1}일"
        }

        val start = startDate ?: dates.firstOrNull() ?: return "날짜 미정"
        val end = endDate ?: dates.lastOrNull() ?: start
        if (scheduleType == ScheduleType.CONTINUOUS && end != start) {
            return "${formatFullDate(start)} ~ ${formatFullDate(end)}"
        }

        return formatFullDate(start)
    }

    fun categorySummary(): String {
        val categories = if (scheduleType == ScheduleType.SELECTED) {
            occurrenceDates.map { selectedDateCategories[it] ?: scheduleCategory }.distinct()
        } else {
            listOf(scheduleCategory)
        }
        return categories.joinToString(" / ") { it.label }
    }

    fun toJson(): JSONObject {
        val selectedDateArray = JSONArray()
        selectedDates.forEach { selectedDateArray.put(it.toString()) }

        val selectedDateCategoryJson = JSONObject()
        selectedDateCategories.forEach { (date, category) ->
            selectedDateCategoryJson.put(date.toString(), category.key)
        }

        val occurrenceDateArray = JSONArray()
        occurrenceDates.forEach { occurrenceDateArray.put(it.toString()) }

        val readByUserArray = JSONArray()
        readByUsers.forEach { readByUserArray.put(it) }

        return JSONObject()
            .put(FIELD_ID, id)
            .put(FIELD_ACTION, action.key)
            .put(FIELD_ACTOR_USER_NAME, actorUserName)
            .put(FIELD_SCHEDULE_ID, scheduleId)
            .put(FIELD_SCHEDULE_TITLE, scheduleTitle)
            .put(FIELD_SCHEDULE_CONTENT, scheduleContent)
            .put(FIELD_SCHEDULE_CATEGORY, scheduleCategory.key)
            .put(FIELD_SCHEDULE_TYPE, scheduleType.key)
            .put(FIELD_START_DATE, startDate?.toString() ?: JSONObject.NULL)
            .put(FIELD_END_DATE, endDate?.toString() ?: JSONObject.NULL)
            .put(FIELD_SELECTED_DATES, selectedDateArray)
            .put(FIELD_SELECTED_DATE_CATEGORIES, selectedDateCategoryJson)
            .put(FIELD_OCCURRENCE_DATES, occurrenceDateArray)
            .put(FIELD_CREATED_AT, createdAt)
            .put(FIELD_READ_BY_USERS, readByUserArray)
    }

    companion object {
        private val FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)

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

        fun fromEvent(
            action: ScheduleNotificationAction,
            event: ScheduleEvent,
        ): ScheduleNotification {
            val occurrenceDates = event.occurrenceDates()
            return ScheduleNotification(
                action = action,
                actorUserName = event.userName,
                scheduleId = event.id,
                scheduleTitle = event.title,
                scheduleContent = event.content,
                scheduleCategory = event.category,
                scheduleType = event.scheduleType,
                startDate = event.startDate,
                endDate = event.endDate,
                selectedDates = event.selectedDates,
                selectedDateCategories = event.selectedDateCategories,
                occurrenceDates = occurrenceDates,
            )
        }

        fun fromJson(json: JSONObject): ScheduleNotification {
            return ScheduleNotification(
                id = json.optString(FIELD_ID, UUID.randomUUID().toString()),
                action = ScheduleNotificationAction.fromKey(json.optString(FIELD_ACTION)),
                actorUserName = json.optString(FIELD_ACTOR_USER_NAME, ""),
                scheduleId = json.optString(FIELD_SCHEDULE_ID, ""),
                scheduleTitle = json.optString(FIELD_SCHEDULE_TITLE, ""),
                scheduleContent = json.optString(FIELD_SCHEDULE_CONTENT, ""),
                scheduleCategory = ScheduleCategory.fromKey(json.optString(FIELD_SCHEDULE_CATEGORY, ScheduleCategory.OTHER.key)),
                scheduleType = ScheduleType.fromKey(json.optString(FIELD_SCHEDULE_TYPE, ScheduleType.CONTINUOUS.key)),
                startDate = parseDateOrNull(json.optString(FIELD_START_DATE).takeIf { it.isNotBlank() }),
                endDate = parseDateOrNull(json.optString(FIELD_END_DATE).takeIf { it.isNotBlank() }),
                selectedDates = parseDateList(json.optJSONArray(FIELD_SELECTED_DATES)),
                selectedDateCategories = parseSelectedDateCategories(json.optJSONObject(FIELD_SELECTED_DATE_CATEGORIES)),
                occurrenceDates = parseDateList(json.optJSONArray(FIELD_OCCURRENCE_DATES)),
                createdAt = json.optLong(FIELD_CREATED_AT, System.currentTimeMillis()),
                readByUsers = parseStringList(json.optJSONArray(FIELD_READ_BY_USERS)),
            )
        }

        fun fromFcmData(data: Map<String, String>): ScheduleNotification {
            return ScheduleNotification(
                id = data[FIELD_ID].orEmpty().ifBlank { UUID.randomUUID().toString() },
                action = ScheduleNotificationAction.fromKey(data[FIELD_ACTION]),
                actorUserName = data[FIELD_ACTOR_USER_NAME].orEmpty(),
                scheduleId = data[FIELD_SCHEDULE_ID].orEmpty(),
                scheduleTitle = data[FIELD_SCHEDULE_TITLE].orEmpty(),
                scheduleContent = data[FIELD_SCHEDULE_CONTENT].orEmpty(),
                scheduleCategory = ScheduleCategory.fromKey(data[FIELD_SCHEDULE_CATEGORY]),
                scheduleType = ScheduleType.fromKey(data[FIELD_SCHEDULE_TYPE]),
                startDate = parseDateOrNull(data[FIELD_START_DATE]),
                endDate = parseDateOrNull(data[FIELD_END_DATE]),
                selectedDates = parseCommaSeparatedDates(data[FIELD_SELECTED_DATES]),
                selectedDateCategories = parseDateCategoryPairs(data[FIELD_SELECTED_DATE_CATEGORIES]),
                occurrenceDates = parseCommaSeparatedDates(data[FIELD_OCCURRENCE_DATES]),
                createdAt = data[FIELD_CREATED_AT]?.toLongOrNull() ?: System.currentTimeMillis(),
                readByUsers = emptyList(),
            )
        }

        private fun formatFullDate(date: LocalDate): String = date.format(FULL_DATE_FORMATTER)

        private fun parseDateOrNull(rawDate: String?): LocalDate? {
            if (rawDate.isNullOrBlank() || rawDate == "null") {
                return null
            }
            return runCatching { LocalDate.parse(rawDate) }.getOrNull()
        }

        private fun parseDateList(jsonArray: JSONArray?): List<LocalDate> {
            if (jsonArray == null) {
                return emptyList()
            }

            return buildList {
                for (index in 0 until jsonArray.length()) {
                    parseDateOrNull(jsonArray.optString(index))?.let(::add)
                }
            }
        }

        private fun parseSelectedDateCategories(jsonObject: JSONObject?): Map<LocalDate, ScheduleCategory> {
            if (jsonObject == null) {
                return emptyMap()
            }

            return buildMap {
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val rawDate = keys.next()
                    val date = parseDateOrNull(rawDate) ?: continue
                    put(date, ScheduleCategory.fromKey(jsonObject.optString(rawDate)))
                }
            }
        }

        private fun parseStringList(jsonArray: JSONArray?): List<String> {
            if (jsonArray == null) {
                return emptyList()
            }

            return buildList {
                for (index in 0 until jsonArray.length()) {
                    val value = jsonArray.optString(index)
                    if (value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        }

        private fun parseCommaSeparatedDates(rawDates: String?): List<LocalDate> {
            if (rawDates.isNullOrBlank()) {
                return emptyList()
            }

            return rawDates.split(",")
                .mapNotNull { rawDate -> parseDateOrNull(rawDate.trim()) }
        }

        private fun parseDateCategoryPairs(rawCategories: String?): Map<LocalDate, ScheduleCategory> {
            if (rawCategories.isNullOrBlank()) {
                return emptyMap()
            }

            return buildMap {
                rawCategories.split(",").forEach { pair ->
                    val rawDate = pair.substringBefore("=", missingDelimiterValue = "").trim()
                    val rawCategory = pair.substringAfter("=", missingDelimiterValue = "").trim()
                    val date = parseDateOrNull(rawDate) ?: return@forEach
                    put(date, ScheduleCategory.fromKey(rawCategory))
                }
            }
        }
    }
}
