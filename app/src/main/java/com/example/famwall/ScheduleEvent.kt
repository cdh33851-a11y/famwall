package com.example.famwall

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class ScheduleEvent(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var content: String = "",
    var category: ScheduleCategory = ScheduleCategory.WALLPAPER,
    var userName: String = "",
    var startDate: LocalDate? = null,
    var endDate: LocalDate? = null,
    var selectedDates: List<LocalDate> = emptyList(),
    var selectedDateCategories: Map<LocalDate, ScheduleCategory> = emptyMap(),
    var materialOrderedDates: List<LocalDate> = emptyList(),
    var scheduleType: ScheduleType = ScheduleType.CONTINUOUS,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun occurrenceDates(): List<LocalDate> {
        val dates = sortedSetOf<LocalDate>()

        if (scheduleType == ScheduleType.SELECTED) {
            dates.addAll(selectedDates)
            startDate?.let {
                if (dates.isEmpty()) {
                    dates.add(it)
                }
            }
            return dates.toList()
        }

        val start = startDate ?: return emptyList()
        var rangeStart = start
        var rangeEnd = endDate ?: start
        if (rangeEnd.isBefore(rangeStart)) {
            val temporary = rangeStart
            rangeStart = rangeEnd
            rangeEnd = temporary
        }

        var cursor = rangeStart
        while (!cursor.isAfter(rangeEnd)) {
            dates.add(cursor)
            cursor = cursor.plusDays(1)
        }

        return dates.toList()
    }

    fun occursOn(date: LocalDate?): Boolean {
        return date != null && occurrenceDates().contains(date)
    }

    fun categoryForDate(date: LocalDate?): ScheduleCategory {
        if (scheduleType != ScheduleType.SELECTED || date == null) {
            return category
        }
        return selectedDateCategories[date] ?: category
    }

    fun isMaterialOrderedForDate(date: LocalDate?): Boolean {
        return date != null && materialOrderedDates.contains(date)
    }

    fun toJson(): JSONObject {
        val selectedDateArray = JSONArray()
        selectedDates.forEach { selectedDateArray.put(it.toString()) }

        val selectedDateCategoryJson = JSONObject()
        selectedDateCategories.forEach { (date, category) ->
            selectedDateCategoryJson.put(date.toString(), category.key)
        }

        val materialOrderedDateArray = JSONArray()
        materialOrderedDates.forEach { materialOrderedDateArray.put(it.toString()) }

        return JSONObject()
            .put(FIELD_ID, id)
            .put(FIELD_TITLE, title)
            .put(FIELD_CONTENT, content)
            .put(FIELD_CATEGORY, category.key)
            .put(FIELD_USER_NAME, userName)
            .put(FIELD_START_DATE, startDate?.toString() ?: JSONObject.NULL)
            .put(FIELD_END_DATE, endDate?.toString() ?: JSONObject.NULL)
            .put(FIELD_SELECTED_DATES, selectedDateArray)
            .put(FIELD_SELECTED_DATE_CATEGORIES, selectedDateCategoryJson)
            .put(FIELD_MATERIAL_ORDERED_DATES, materialOrderedDateArray)
            .put(FIELD_SCHEDULE_TYPE, scheduleType.key)
            .put(FIELD_CREATED_AT, createdAt)
            .put(FIELD_UPDATED_AT, updatedAt)
    }

    companion object {
        private const val FIELD_ID = "id"
        private const val FIELD_TITLE = "title"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_CATEGORY = "category"
        private const val FIELD_USER_NAME = "userName"
        private const val FIELD_START_DATE = "startDate"
        private const val FIELD_END_DATE = "endDate"
        private const val FIELD_SELECTED_DATES = "selectedDates"
        private const val FIELD_SELECTED_DATE_CATEGORIES = "selectedDateCategories"
        private const val FIELD_MATERIAL_ORDERED_DATES = "materialOrderedDates"
        private const val FIELD_SCHEDULE_TYPE = "scheduleType"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"

        fun fromJson(json: JSONObject): ScheduleEvent {
            val createdAt = json.optLong(FIELD_CREATED_AT, System.currentTimeMillis())
            return ScheduleEvent(
                id = json.optString(FIELD_ID, UUID.randomUUID().toString()),
                title = json.optString(FIELD_TITLE, ""),
                content = json.optString(FIELD_CONTENT, ""),
                category = ScheduleCategory.fromKey(json.optString(FIELD_CATEGORY, ScheduleCategory.OTHER.key)),
                userName = json.optString(FIELD_USER_NAME, ""),
                startDate = parseDateOrNull(json.optString(FIELD_START_DATE).takeIf { it.isNotBlank() }),
                endDate = parseDateOrNull(json.optString(FIELD_END_DATE).takeIf { it.isNotBlank() }),
                selectedDates = parseSelectedDates(json.optJSONArray(FIELD_SELECTED_DATES)),
                selectedDateCategories = parseSelectedDateCategories(json.optJSONObject(FIELD_SELECTED_DATE_CATEGORIES)),
                materialOrderedDates = parseSelectedDates(json.optJSONArray(FIELD_MATERIAL_ORDERED_DATES)),
                scheduleType = ScheduleType.fromKey(json.optString(FIELD_SCHEDULE_TYPE, ScheduleType.CONTINUOUS.key)),
                createdAt = createdAt,
                updatedAt = json.optLong(FIELD_UPDATED_AT, createdAt),
            )
        }

        private fun parseDateOrNull(rawDate: String?): LocalDate? {
            if (rawDate.isNullOrBlank() || rawDate == "null") {
                return null
            }
            return LocalDate.parse(rawDate)
        }

        private fun parseSelectedDates(jsonArray: JSONArray?): List<LocalDate> {
            if (jsonArray == null) {
                return emptyList()
            }

            return buildList {
                for (index in 0 until jsonArray.length()) {
                    add(LocalDate.parse(jsonArray.getString(index)))
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
                    put(LocalDate.parse(rawDate), ScheduleCategory.fromKey(jsonObject.optString(rawDate)))
                }
            }
        }
    }
}
