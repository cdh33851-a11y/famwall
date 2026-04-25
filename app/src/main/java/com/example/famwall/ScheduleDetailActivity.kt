package com.example.famwall

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScheduleDetailActivity : AppCompatActivity() {
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var listContainer: LinearLayout
    private lateinit var selectedDate: LocalDate
    private lateinit var activeUserName: String
    private var focusedEventId: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoreanLocaleContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        val preferences: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        activeUserName = preferences.getString(KEY_SELECTED_USER, getString(R.string.user_choi))
            ?: getString(R.string.user_choi)
        selectedDate = readIntentDate()
        focusedEventId = intent.getStringExtra(EXTRA_FOCUS_EVENT_ID)
        scheduleRepository = createScheduleRepository()

        setContentView(createPage())
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) {
            scheduleRepository.close()
            scheduleRepository = createScheduleRepository()
            renderScheduleList()
        }
    }

    override fun onDestroy() {
        if (::scheduleRepository.isInitialized) {
            scheduleRepository.close()
        }
        super.onDestroy()
    }

    private fun createScheduleRepository(): ScheduleRepository {
        return ScheduleRepository(this) {
            if (::listContainer.isInitialized) {
                renderScheduleList()
            }
        }
    }

    private fun readIntentDate(): LocalDate {
        val rawDate = intent.getStringExtra(EXTRA_DATE)
        return runCatching {
            if (rawDate.isNullOrBlank()) LocalDate.now() else LocalDate.parse(rawDate)
        }.getOrDefault(LocalDate.now())
    }

    private fun createPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.dark_background))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "\uC77C\uC815 \uC0C1\uC138"
            subtitle = formatFullDate(selectedDate)
            setTitleTextColor(color(R.color.text_primary))
            setSubtitleTextColor(getUserAccentColor(activeUserName))
            setNavigationIcon(R.drawable.ic_chevron_left)
            navigationContentDescription = "\uB4A4\uB85C"
            setNavigationOnClickListener { finish() }
            setPadding(dp(6), dp(10), dp(18), dp(10))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scrollView = ScrollView(this).apply { isFillViewport = false }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        scrollView.addView(listContainer)
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottomBar = createBottomBar()
        root.addView(bottomBar)
        applyInsets(root, toolbar, bottomBar)
        renderScheduleList()
        return root
    }

    private fun createBottomBar(): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(color(R.color.dark_background))

            addView(createSmallActionButton("\uB2EB\uAE30").apply {
                setOnClickListener { finish() }
            })

            addView(createSmallActionButton("\uC77C\uC815 \uCD94\uAC00").apply {
                setTextColor(color(R.color.dark_background))
                backgroundTintList = ColorStateList.valueOf(getUserAccentColor(activeUserName))
                setOnClickListener { openScheduleEditorPage(null) }
            }, LinearLayout.LayoutParams(WRAP, dp(48)).apply { setMargins(dp(10), 0, 0, 0) })
        }
    }

    private fun renderScheduleList() {
        listContainer.removeAllViews()
        val allDateEvents = scheduleRepository.getEventsForDate(selectedDate)
        val dateEvents = focusedEventId?.let { eventId ->
            allDateEvents.filter { it.id == eventId }.ifEmpty { allDateEvents }
        } ?: allDateEvents

        listContainer.addView(createHeaderCard(dateEvents.size))
        if (dateEvents.isEmpty()) {
            listContainer.addView(createEmptyCard())
            return
        }

        dateEvents.forEach { event ->
            listContainer.addView(createEventCard(event))
        }
    }

    private fun createHeaderCard(eventCount: Int): MaterialCardView {
        val card = createBaseCard(getUserAccentColor(activeUserName), dp(18))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        content.addView(TextView(this).apply {
            text = eventCount.toString() + "\uAC1C\uC758 \uC77C\uC815\uC774 \uC788\uC5B4\uC694"
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
        })
        content.addView(TextView(this).apply {
            text = "\uC774 \uB0A0\uC9DC\uC5D0 \uD3EC\uD568\uB41C \uC77C\uC815\uC744 \uD655\uC778\uD558\uACE0, \uD544\uC694\uD558\uBA74 \uC218\uC815\uD558\uAC70\uB098 \uC0AD\uC81C\uD560 \uC218 \uC788\uC5B4\uC694."
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        card.addView(content)
        return card
    }

    private fun createEmptyCard(): MaterialCardView {
        return createBaseCard(color(R.color.calendar_day_stroke), dp(16)).apply {
            addView(TextView(this@ScheduleDetailActivity).apply {
                text = "\uC544\uC9C1 \uC77C\uC815\uC774 \uC5C6\uC5B4\uC694. \uC544\uB798 \uBC84\uD2BC\uC73C\uB85C \uC77C\uC815\uC744 \uCD94\uAC00\uD574\uBCF4\uC138\uC694."
                setTextAppearance(R.style.TextAppearance_FamWall_Body)
                setPadding(dp(16), dp(18), dp(16), dp(18))
            })
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) }
        }
    }

    private fun createEventCard(event: ScheduleEvent): MaterialCardView {
        val card = createBaseCard(getUserAccentColor(event.userName), dp(16)).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(createCategoryLabel(event))
            addView(TextView(this@ScheduleDetailActivity).apply {
                text = getDisplayTitle(event)
                setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(10), 0, dp(8), 0) })
            addView(createMaterialOrderCheckbox(event))
        }
        content.addView(titleRow)

        addDetailLine(content, "\uB0B4\uC6A9", event.content.trim().ifEmpty { "\uB0B4\uC6A9 \uC5C6\uC74C" })
        if (event.scheduleType == ScheduleType.CONTINUOUS) {
            addDetailLine(content, "\uC77C\uC815 \uAE30\uAC04", formatContinuousDateRange(event.startDate, event.endDate))
        } else {
            addDetailLine(content, "\uC77C\uC815 \uAE30\uAC04", formatSelectedDateGroups(event))
        }

        val buttonRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        content.addView(buttonRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) })

        buttonRow.addView(TextView(this).apply {
            text = "\uB4F1\uB85D \u00B7 ${event.userName}"
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(getUserAccentColor(event.userName))
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        buttonRow.addView(createSmallActionButton("\uC218\uC815").apply {
            setOnClickListener { openScheduleEditorPage(event) }
        })

        buttonRow.addView(createSmallActionButton("\uC0AD\uC81C").apply {
            setTextColor(color(R.color.calendar_weekend_sunday))
            setOnClickListener { confirmDeleteSchedule(event) }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, 0, 0) })

        card.addView(content)
        return card
    }

    private fun createBaseCard(strokeColor: Int, radiusPx: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            this.strokeColor = strokeColor
            strokeWidth = dp(1)
            radius = radiusPx.toFloat()
            useCompatPadding = false
        }
    }

    private fun createCategoryLabel(event: ScheduleEvent): TextView {
        return TextView(this).apply {
            text = event.categoryForDate(selectedDate).label
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(color(R.color.dark_background))
            background = createRoundedBackground(getUserAccentColor(event.userName), dp(8))
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(28))
        }
    }

    private fun createMaterialOrderCheckbox(event: ScheduleEvent): MaterialCheckBox {
        val accentColor = getUserAccentColor(event.userName)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        return MaterialCheckBox(this).apply {
            text = "\uC790\uC7AC"
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(ColorStateList(states, intArrayOf(accentColor, color(R.color.text_secondary))))
            buttonTintList = ColorStateList(states, intArrayOf(accentColor, color(R.color.text_muted)))
            isChecked = event.isMaterialOrderedForDate(selectedDate)
            contentDescription = "\uC790\uC7AC\uC8FC\uBB38 \uC644\uB8CC"
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(38)
            minimumHeight = dp(38)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, isChecked ->
                scheduleRepository.setMaterialOrdered(event.id, selectedDate, isChecked)
            }
        }
    }

    private fun addDetailLine(container: LinearLayout, label: String, value: CharSequence) {
        container.addView(TextView(this).apply {
            text = SpannableStringBuilder().apply {
                append(label)
                append(" · ")
                append(value)
            }
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(9), 0, 0) })
    }

    private fun confirmDeleteSchedule(event: ScheduleEvent) {
        MaterialAlertDialogBuilder(this)
            .setTitle("\uC77C\uC815 \uC0AD\uC81C")
            .setMessage("'${getDisplayTitle(event)}' \uC77C\uC815\uC744 \uC0AD\uC81C\uD560\uAE4C\uC694?")
            .setNegativeButton("\uCDE8\uC18C", null)
            .setPositiveButton("\uC0AD\uC81C") { _, _ ->
                ScheduleNotificationRepository.recordScheduleChange(this, ScheduleNotificationAction.DELETED, event)
                scheduleRepository.deleteEvent(event.id)
                renderScheduleList()
            }
            .show()
    }

    private fun openScheduleEditorPage(editingEvent: ScheduleEvent?) {
        val intent = Intent(this, ScheduleEditorActivity::class.java).apply {
            putExtra(ScheduleEditorActivity.EXTRA_DATE, selectedDate.toString())
            editingEvent?.let { putExtra(ScheduleEditorActivity.EXTRA_EVENT_ID, it.id) }
        }
        startActivity(intent)
    }

    private fun createSmallActionButton(text: String): MaterialButton {
        return MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minHeight = dp(42)
            minimumHeight = dp(42)
            insetTop = 0
            insetBottom = 0
        }
    }

    private fun getDisplayTitle(event: ScheduleEvent): String {
        return event.title.trim()
            .ifEmpty { event.content.trim() }
            .ifEmpty { event.categoryForDate(selectedDate).label + " \uC77C\uC815" }
    }

    private fun formatFullDate(date: LocalDate?): String {
        return date?.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C EEEE", Locale.KOREAN))
            ?: "\uB0A0\uC9DC \uC5C6\uC74C"
    }

    private fun formatContinuousDateRange(start: LocalDate?, end: LocalDate?): String {
        if (start == null) {
            return "\uB0A0\uC9DC \uC5C6\uC74C"
        }

        var startDate = start
        var safeEndDate = end ?: start
        if (safeEndDate.isBefore(startDate)) {
            val temporary = startDate
            startDate = safeEndDate
            safeEndDate = temporary
        }

        if (startDate == safeEndDate) {
            return startDate.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN))
        }

        if (startDate.year == safeEndDate.year && startDate.monthValue == safeEndDate.monthValue) {
            return startDate.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN)) +
                " ~ ${safeEndDate.dayOfMonth}\uC77C"
        }

        if (startDate.year == safeEndDate.year) {
            return startDate.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN)) +
                " ~ ${safeEndDate.monthValue}\uC6D4 ${safeEndDate.dayOfMonth}\uC77C"
        }

        return startDate.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN)) +
            " ~ " + safeEndDate.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN))
    }

    private fun formatSelectedDateGroups(event: ScheduleEvent): CharSequence {
        val sortedDates = event.occurrenceDates().sorted()
        if (sortedDates.isEmpty()) {
            return "\uB0A0\uC9DC \uC5C6\uC74C"
        }

        val dateGroups = buildSelectedDateGroups(event, sortedDates)
        return SpannableStringBuilder().apply {
            dateGroups.forEachIndexed { index, dateGroup ->
                if (index > 0) {
                    append(", ")
                }

                appendBold(dateGroup.category.label)
                append(" ")
                append(formatSelectedDateGroup(dateGroup, dateGroups.getOrNull(index - 1)))
            }
        }
    }

    private fun SpannableStringBuilder.appendBold(text: String) {
        val start = length
        append(text)
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun buildSelectedDateGroups(
        event: ScheduleEvent,
        sortedDates: List<LocalDate>,
    ): List<SelectedDateGroup> {
        val dateGroups = mutableListOf<SelectedDateGroup>()
        var groupStartDate = sortedDates.first()
        var groupEndDate = groupStartDate
        var groupCategory = event.categoryForDate(groupStartDate)

        sortedDates.drop(1).forEach { date ->
            val dateCategory = event.categoryForDate(date)
            val canJoinCurrentGroup = date == groupEndDate.plusDays(1) && dateCategory == groupCategory
            if (canJoinCurrentGroup) {
                groupEndDate = date
            } else {
                dateGroups.add(SelectedDateGroup(groupCategory, groupStartDate, groupEndDate))
                groupStartDate = date
                groupEndDate = date
                groupCategory = dateCategory
            }
        }

        dateGroups.add(SelectedDateGroup(groupCategory, groupStartDate, groupEndDate))
        return dateGroups
    }

    private fun formatSelectedDateGroup(
        dateGroup: SelectedDateGroup,
        previousGroup: SelectedDateGroup?,
    ): String {
        val startText = formatDateWithPreviousContext(dateGroup.startDate, previousGroup?.endDate)
        if (dateGroup.startDate == dateGroup.endDate) {
            return startText
        }

        return "$startText ~ ${formatRangeEndDate(dateGroup.startDate, dateGroup.endDate)}"
    }

    private fun formatDateWithPreviousContext(date: LocalDate, previousDate: LocalDate?): String {
        return when {
            previousDate == null || previousDate.year != date.year ->
                date.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN))
            previousDate.monthValue != date.monthValue -> "${date.monthValue}\uC6D4 ${date.dayOfMonth}\uC77C"
            else -> "${date.dayOfMonth}\uC77C"
        }
    }

    private fun formatRangeEndDate(startDate: LocalDate, endDate: LocalDate): String {
        return when {
            startDate.year != endDate.year ->
                endDate.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C", Locale.KOREAN))
            startDate.monthValue != endDate.monthValue -> "${endDate.monthValue}\uC6D4 ${endDate.dayOfMonth}\uC77C"
            else -> "${endDate.dayOfMonth}\uC77C"
        }
    }

    private fun createRoundedBackground(fillColor: Int, cornerRadiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx.toFloat()
        }
    }

    private fun getUserAccentColor(userName: String): Int = color(getUserAccentColorRes(userName))

    private fun getUserAccentColorRes(userName: String): Int {
        return when (userName) {
            getString(R.string.user_han) -> R.color.user_han_accent
            getString(R.string.user_lee) -> R.color.user_lee_accent
            else -> R.color.user_choi_accent
        }
    }

    private fun applyInsets(root: View, toolbar: MaterialToolbar, bottomBar: LinearLayout) {
        val toolbarTopPadding = toolbar.paddingTop
        val bottomBarBottomPadding = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBarsInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, toolbarTopPadding + systemBarsInsets.top, toolbar.paddingRight, toolbar.paddingBottom)
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bottomBarBottomPadding + systemBarsInsets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun color(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class SelectedDateGroup(
        val category: ScheduleCategory,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    companion object {
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_FOCUS_EVENT_ID = "extra_focus_event_id"
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
