package com.example.famwall

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object KoreanLocaleContext {
    private val koreanLocale = Locale.KOREA

    fun wrap(context: Context): Context {
        Locale.setDefault(koreanLocale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(koreanLocale)
            setLayoutDirection(koreanLocale)
        }
        return context.createConfigurationContext(configuration)
    }
}
