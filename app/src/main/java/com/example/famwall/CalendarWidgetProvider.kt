package com.example.famwall

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class CalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context.applicationContext, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_PREVIOUS_MONTH -> {
                moveWidgetMonth(appContext, -1)
                updateWidgets(appContext)
                return
            }
            ACTION_NEXT_MONTH -> {
                moveWidgetMonth(appContext, 1)
                updateWidgets(appContext)
                return
            }
            ACTION_TODAY -> {
                saveWidgetMonth(appContext, YearMonth.now())
                updateWidgets(appContext)
                return
            }
            ACTION_OPEN_DATE -> {
                openDateFromWidget(appContext, intent.getStringExtra(EXTRA_WIDGET_DATE))
                return
            }
        }

        super.onReceive(context, intent)
    }

    companion object {
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val KEY_WIDGET_MONTH = "calendar_widget_month"
        private const val ACTION_PREVIOUS_MONTH = "com.example.famwall.widget.PREVIOUS_MONTH"
        private const val ACTION_NEXT_MONTH = "com.example.famwall.widget.NEXT_MONTH"
        private const val ACTION_TODAY = "com.example.famwall.widget.TODAY"
        private const val ACTION_OPEN_DATE = "com.example.famwall.widget.OPEN_DATE"
        private const val EXTRA_WIDGET_DATE = "com.example.famwall.widget.extra.DATE"
        private const val MAX_WIDGET_EVENTS = 2

        private val dayViewIds = intArrayOf(
            R.id.widget_day_0,
            R.id.widget_day_1,
            R.id.widget_day_2,
            R.id.widget_day_3,
            R.id.widget_day_4,
            R.id.widget_day_5,
            R.id.widget_day_6,
            R.id.widget_day_7,
            R.id.widget_day_8,
            R.id.widget_day_9,
            R.id.widget_day_10,
            R.id.widget_day_11,
            R.id.widget_day_12,
            R.id.widget_day_13,
            R.id.widget_day_14,
            R.id.widget_day_15,
            R.id.widget_day_16,
            R.id.widget_day_17,
            R.id.widget_day_18,
            R.id.widget_day_19,
            R.id.widget_day_20,
            R.id.widget_day_21,
            R.id.widget_day_22,
            R.id.widget_day_23,
            R.id.widget_day_24,
            R.id.widget_day_25,
            R.id.widget_day_26,
            R.id.widget_day_27,
            R.id.widget_day_28,
            R.id.widget_day_29,
            R.id.widget_day_30,
            R.id.widget_day_31,
            R.id.widget_day_32,
            R.id.widget_day_33,
            R.id.widget_day_34,
            R.id.widget_day_35,
            R.id.widget_day_36,
            R.id.widget_day_37,
            R.id.widget_day_38,
            R.id.widget_day_39,
            R.id.widget_day_40,
            R.id.widget_day_41,
        )

        fun updateWidgets(context: Context) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(appContext, CalendarWidgetProvider::class.java),
            )
            if (appWidgetIds.isEmpty()) {
                return
            }

            appWidgetIds.forEach { appWidgetId ->
                updateWidget(appContext, appWidgetManager, appWidgetId)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            val palette = WidgetPalette.from(context)
            val selectedUser = getSelectedUserName(context)
            val userAccent = getUserAccentColor(context, selectedUser, palette.isDark)
            val displayedMonth = getWidgetMonth(context)
            val today = LocalDate.now()
            val repository = ScheduleRepository(context)
            val unreadDates = ScheduleNotificationRepository.getLocalUnreadDates(context, selectedUser)

            views.setInt(R.id.widget_root, "setBackgroundColor", palette.background)
            views.setInt(R.id.widget_header, "setBackgroundColor", userAccent)
            views.setTextColor(R.id.widget_month_title, palette.onAccent)
            views.setTextColor(R.id.widget_user_label, palette.onAccent)
            views.setTextViewText(
                R.id.widget_month_title,
                displayedMonth.format(DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN)),
            )
            views.setTextViewText(R.id.widget_user_label, selectedUser)
            views.setTextColor(R.id.widget_today_button, palette.buttonText)
            views.setTextColor(R.id.widget_previous_month_button, palette.buttonText)
            views.setTextColor(R.id.widget_next_month_button, palette.buttonText)
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context, null))
            views.setOnClickPendingIntent(R.id.widget_today_button, widgetActionPendingIntent(context, ACTION_TODAY))
            views.setOnClickPendingIntent(R.id.widget_previous_month_button, widgetActionPendingIntent(context, ACTION_PREVIOUS_MONTH))
            views.setOnClickPendingIntent(R.id.widget_next_month_button, widgetActionPendingIntent(context, ACTION_NEXT_MONTH))

            val leadingDays = displayedMonth.atDay(1).dayOfWeek.value % 7
            val firstVisibleDate = displayedMonth.atDay(1).minusDays(leadingDays.toLong())

            dayViewIds.forEachIndexed { index, viewId ->
                val cellDate = firstVisibleDate.plusDays(index.toLong())
                val isCurrentMonth = YearMonth.from(cellDate) == displayedMonth
                val isToday = cellDate == today
                val dateEvents = repository.getEventsForDate(cellDate)
                val materialOrderedEvents = dateEvents.filter { it.isMaterialOrderedForDate(cellDate) }
                val hasUnreadNotification = unreadDates.contains(cellDate)
                val textColor = when {
                    isToday -> userAccent
                    !isCurrentMonth -> palette.mutedText
                    cellDate.dayOfWeek.value == 7 -> palette.sundayText
                    cellDate.dayOfWeek.value == 6 -> palette.saturdayText
                    else -> palette.primaryText
                }
                val backgroundColor = when {
                    isToday -> palette.todayBackground
                    !isCurrentMonth -> palette.outsideDayBackground
                    dateEvents.isNotEmpty() -> palette.eventDayBackground
                    else -> palette.dayBackground
                }

                views.setInt(viewId, "setBackgroundColor", backgroundColor)
                views.setTextColor(viewId, textColor)
                views.setTextViewText(
                    viewId,
                    buildDayCellText(
                        context = context,
                        date = cellDate,
                        events = dateEvents,
                        materialOrderedEvents = materialOrderedEvents,
                        hasUnreadNotification = hasUnreadNotification,
                        palette = palette,
                    ),
                )
                views.setOnClickPendingIntent(viewId, openDatePendingIntent(context, cellDate))
            }

            repository.close()
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun buildDayCellText(
            context: Context,
            date: LocalDate,
            events: List<ScheduleEvent>,
            materialOrderedEvents: List<ScheduleEvent>,
            hasUnreadNotification: Boolean,
            palette: WidgetPalette,
        ): CharSequence {
            val builder = SpannableStringBuilder()
            val dateStart = builder.length
            builder.append(date.dayOfMonth.toString())
            builder.setSpan(StyleSpan(Typeface.BOLD), dateStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            materialOrderedEvents.take(3).forEach { event ->
                appendColored(
                    builder = builder,
                    text = " \u2713",
                    color = getUserAccentColor(context, event.userName, palette.isDark),
                    bold = true,
                )
            }

            if (materialOrderedEvents.size > 3) {
                appendColored(builder, " +${materialOrderedEvents.size - 3}", palette.secondaryText, bold = true)
            }

            if (hasUnreadNotification) {
                appendColored(builder, " \u25CF", palette.unreadDot, bold = true)
            }

            events.take(MAX_WIDGET_EVENTS).forEach { event ->
                builder.append('\n')
                appendColored(
                    builder = builder,
                    text = "\u25CF ",
                    color = getUserAccentColor(context, event.userName, palette.isDark),
                    bold = true,
                )
                val eventText = buildEventTitle(event, date)
                val titleStart = builder.length
                builder.append(eventText)
                builder.setSpan(
                    ForegroundColorSpan(palette.secondaryText),
                    titleStart,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            val hiddenCount = events.size - MAX_WIDGET_EVENTS
            if (hiddenCount > 0) {
                appendColored(builder, "\n+${hiddenCount}개", palette.secondaryText, bold = true)
            }

            return builder
        }

        private fun appendColored(
            builder: SpannableStringBuilder,
            text: String,
            color: Int,
            bold: Boolean = false,
        ) {
            val start = builder.length
            builder.append(text)
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun buildEventTitle(event: ScheduleEvent, date: LocalDate): String {
            return event.title.trim()
                .ifEmpty { event.contentForDate(date).trim() }
                .ifEmpty { "일정" }
        }

        private fun openAppPendingIntent(context: Context, date: LocalDate?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                date?.let { putExtra(MainActivity.EXTRA_OPEN_DATE, it.toString()) }
            }
            val requestCode = date?.toEpochDay()?.hashCode() ?: 0
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openDatePendingIntent(context: Context, date: LocalDate): PendingIntent {
            val intent = Intent(context, CalendarWidgetProvider::class.java).apply {
                action = ACTION_OPEN_DATE
                putExtra(EXTRA_WIDGET_DATE, date.toString())
            }
            return PendingIntent.getBroadcast(
                context,
                date.toEpochDay().hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openDateFromWidget(context: Context, rawDate: String?) {
            val selectedDate = runCatching {
                if (rawDate.isNullOrBlank()) LocalDate.now() else LocalDate.parse(rawDate)
            }.getOrDefault(LocalDate.now())
            val currentUserName = getSelectedUserName(context)
            val notificationRepository = ScheduleNotificationRepository(context, currentUserName)
            notificationRepository.markNotificationsOnDateAsRead(selectedDate)
            notificationRepository.close()

            val scheduleRepository = ScheduleRepository(context)
            val hasEvents = scheduleRepository.getEventsForDate(selectedDate).isNotEmpty()
            scheduleRepository.close()

            val destination = if (hasEvents) {
                Intent(context, ScheduleDetailActivity::class.java).apply {
                    putExtra(ScheduleDetailActivity.EXTRA_DATE, selectedDate.toString())
                }
            } else {
                Intent(context, ScheduleEditorActivity::class.java).apply {
                    putExtra(ScheduleEditorActivity.EXTRA_DATE, selectedDate.toString())
                }
            }
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(destination)
            updateWidgets(context)
        }

        private fun widgetActionPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, CalendarWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun moveWidgetMonth(context: Context, offset: Long) {
            saveWidgetMonth(context, getWidgetMonth(context).plusMonths(offset))
        }

        private fun getWidgetMonth(context: Context): YearMonth {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawMonth = preferences.getString(KEY_WIDGET_MONTH, null)
            return runCatching { YearMonth.parse(rawMonth) }.getOrDefault(YearMonth.now())
        }

        private fun saveWidgetMonth(context: Context, month: YearMonth) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WIDGET_MONTH, month.toString())
                .apply()
        }

        private fun getSelectedUserName(context: Context): String {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return preferences.getString(KEY_SELECTED_USER, context.getString(R.string.user_choi))
                ?: context.getString(R.string.user_choi)
        }

        private fun getUserAccentColor(context: Context, userName: String, isDark: Boolean): Int {
            return when (userName) {
                context.getString(R.string.user_han) -> if (isDark) Color.parseColor("#FF6EE7B7") else Color.parseColor("#FF047857")
                context.getString(R.string.user_lee) -> if (isDark) Color.parseColor("#FFFF8AC8") else Color.parseColor("#FFC02675")
                else -> if (isDark) Color.parseColor("#FF63B3FF") else Color.parseColor("#FF1769D2")
            }
        }
    }

    private data class WidgetPalette(
        val isDark: Boolean,
        val background: Int,
        val dayBackground: Int,
        val todayBackground: Int,
        val eventDayBackground: Int,
        val outsideDayBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val mutedText: Int,
        val onAccent: Int,
        val sundayText: Int,
        val saturdayText: Int,
        val unreadDot: Int,
        val buttonText: Int,
    ) {
        companion object {
            fun from(context: Context): WidgetPalette {
                val isDark = FamWallThemeManager.getSelectedTheme(context) == FamWallThemePreference.DARK
                return if (isDark) {
                    WidgetPalette(
                        isDark = true,
                        background = Color.parseColor("#F20B0D10"),
                        dayBackground = Color.parseColor("#FF11161C"),
                        todayBackground = Color.parseColor("#FF203143"),
                        eventDayBackground = Color.parseColor("#FF17202A"),
                        outsideDayBackground = Color.parseColor("#FF0D1117"),
                        primaryText = Color.parseColor("#FFF7FAFF"),
                        secondaryText = Color.parseColor("#FFB5C0CF"),
                        mutedText = Color.parseColor("#FF5B6472"),
                        onAccent = Color.parseColor("#FF081018"),
                        sundayText = Color.parseColor("#FFFF6B7A"),
                        saturdayText = Color.parseColor("#FF6EA8FF"),
                        unreadDot = Color.parseColor("#FFFF4D5E"),
                        buttonText = Color.parseColor("#FFF7FAFF"),
                    )
                } else {
                    WidgetPalette(
                        isDark = false,
                        background = Color.parseColor("#F7FFF7F1"),
                        dayBackground = Color.parseColor("#FFFFFFFF"),
                        todayBackground = Color.parseColor("#FFE4F2FF"),
                        eventDayBackground = Color.parseColor("#FFEAF2FB"),
                        outsideDayBackground = Color.parseColor("#FFF1F4F8"),
                        primaryText = Color.parseColor("#FF18202A"),
                        secondaryText = Color.parseColor("#FF526070"),
                        mutedText = Color.parseColor("#FF9AA5B1"),
                        onAccent = Color.WHITE,
                        sundayText = Color.parseColor("#FFC83248"),
                        saturdayText = Color.parseColor("#FF2563EB"),
                        unreadDot = Color.parseColor("#FFE11D48"),
                        buttonText = Color.parseColor("#FF18202A"),
                    )
                }
            }
        }
    }
}
