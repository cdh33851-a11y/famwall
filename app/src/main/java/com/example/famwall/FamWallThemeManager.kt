package com.example.famwall

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class FamWallThemePreference(
    val key: String,
    val nightMode: Int,
) {
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromKey(key: String?): FamWallThemePreference {
            return entries.firstOrNull { it.key == key } ?: DARK
        }
    }
}

object FamWallThemeManager {
    private const val PREFS_NAME = "famwall_prefs"
    private const val KEY_APP_THEME = "app_theme"

    fun applySavedTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getSelectedTheme(context).nightMode)
    }

    fun getSelectedTheme(context: Context): FamWallThemePreference {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return FamWallThemePreference.fromKey(preferences.getString(KEY_APP_THEME, null))
    }

    fun saveTheme(context: Context, themePreference: FamWallThemePreference) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_THEME, themePreference.key)
            .apply()
        AppCompatDelegate.setDefaultNightMode(themePreference.nightMode)
        CalendarWidgetProvider.updateWidgets(context.applicationContext)
    }
}
