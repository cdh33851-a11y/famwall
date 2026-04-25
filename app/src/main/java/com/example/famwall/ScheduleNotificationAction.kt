package com.example.famwall

enum class ScheduleNotificationAction(
    val key: String,
    val label: String,
    val messageLabel: String,
) {
    ADDED("added", "추가", "추가되었습니다"),
    UPDATED("updated", "수정", "수정되었습니다"),
    DELETED("deleted", "삭제", "삭제되었습니다");

    companion object {
        fun fromKey(key: String?): ScheduleNotificationAction {
            return entries.firstOrNull { it.key == key } ?: ADDED
        }
    }
}
