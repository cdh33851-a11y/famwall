package com.example.famwall

enum class ScheduleType(val key: String) {
    CONTINUOUS("continuous"),
    SELECTED("selected");

    companion object {
        fun fromKey(key: String?): ScheduleType {
            return entries.firstOrNull { it.key == key } ?: CONTINUOUS
        }
    }
}
