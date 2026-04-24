package com.example.famwall

enum class ScheduleCategory(
    val key: String,
    val label: String,
    val initial: String,
) {
    WALLPAPER("wallpaper", "\uB3C4\uBC30", "\uB3C4"),
    FLOORING("flooring", "\uC7A5\uD310", "\uC7A5"),
    FILM("film", "\uD544\uB984", "\uD544"),
    OTHER("other", "\uAE30\uD0C0", "\uAE30");

    companion object {
        fun fromKey(key: String?): ScheduleCategory {
            return entries.firstOrNull { it.key == key } ?: OTHER
        }
    }
}
