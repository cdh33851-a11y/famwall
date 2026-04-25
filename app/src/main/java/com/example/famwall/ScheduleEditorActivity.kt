package com.example.famwall

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScheduleEditorActivity : AppCompatActivity() {
    private lateinit var scheduleRepository: ScheduleRepository
    private var editingEvent: ScheduleEvent? = null
    private lateinit var clickedDate: LocalDate
    private lateinit var activeUserName: String

    private lateinit var titleInput: TextInputEditText
    private lateinit var contentInput: TextInputEditText
    private lateinit var categoryGroup: ChipGroup
    private lateinit var continuousSection: LinearLayout
    private lateinit var selectedSection: LinearLayout
    private lateinit var selectedDateList: LinearLayout
    private lateinit var startDateButton: TextView
    private lateinit var endDateButton: TextView

    private lateinit var startDate: LocalDate
    private lateinit var endDate: LocalDate
    private var scheduleType = ScheduleType.CONTINUOUS
    private val selectedDates = mutableListOf<LocalDate>()
    private val selectedDateCategories = mutableMapOf<LocalDate, ScheduleCategory>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoreanLocaleContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        scheduleRepository = ScheduleRepository(this)
        val preferences: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        activeUserName = preferences.getString(KEY_SELECTED_USER, getString(R.string.user_choi))
            ?: getString(R.string.user_choi)
        clickedDate = readIntentDate()
        editingEvent = scheduleRepository.getEventById(intent.getStringExtra(EXTRA_EVENT_ID))

        initializeDraft()
        setContentView(createPage())
    }

    override fun onDestroy() {
        if (::scheduleRepository.isInitialized) {
            scheduleRepository.close()
        }
        super.onDestroy()
    }

    private fun readIntentDate(): LocalDate {
        val rawDate = intent.getStringExtra(EXTRA_DATE)
        return runCatching {
            if (rawDate.isNullOrBlank()) LocalDate.now() else LocalDate.parse(rawDate)
        }.getOrDefault(LocalDate.now())
    }

    private fun initializeDraft() {
        val draft = editingEvent?.copy() ?: createNewDraftEvent(clickedDate)
        startDate = draft.startDate ?: clickedDate
        endDate = draft.endDate ?: startDate
        scheduleType = draft.scheduleType
        selectedDates.clear()
        selectedDates.addAll(draft.selectedDates)
        if (selectedDates.isEmpty()) {
            selectedDates.add(clickedDate)
        }
        selectedDateCategories.clear()
        selectedDateCategories.putAll(draft.selectedDateCategories)
        selectedDates.forEach { date ->
            selectedDateCategories.putIfAbsent(date, draft.category)
        }
    }

    private fun createPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.dark_background))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = if (editingEvent == null) "\uC0C8 \uC77C\uC815 \uB4F1\uB85D" else "\uC77C\uC815 \uC218\uC815"
            subtitle = formatFullDate(clickedDate)
            setTitleTextColor(color(R.color.text_primary))
            setSubtitleTextColor(getUserAccentColor(activeUserName))
            setNavigationIcon(R.drawable.ic_chevron_left)
            navigationContentDescription = "\uB4A4\uB85C"
            setNavigationOnClickListener { finish() }
            setPadding(dp(6), dp(10), dp(18), dp(10))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scrollView = ScrollView(this).apply { isFillViewport = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            addView(createIntroCard())
        }
        addFormContent(content)
        scrollView.addView(content)
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottomBar = createBottomBar()
        root.addView(bottomBar)
        applyInsets(root, toolbar, bottomBar)
        return root
    }

    private fun createIntroCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            strokeColor = getUserAccentColor(activeUserName)
            strokeWidth = dp(1)
            radius = dp(18).toFloat()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = if (editingEvent == null) "\uC77C\uC815\uC744 \uB4F1\uB85D\uD560\uAC8C\uC694" else "\uC77C\uC815\uC744 \uC218\uC815\uD560\uAC8C\uC694"
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
        })
        content.addView(TextView(this).apply {
            text = "\uC5F0\uC18D \uC77C\uC815\uC740 \uC2DC\uC791\uC77C\uACFC \uC885\uB8CC\uC77C\uB9CC, \uBE44\uC5F0\uC18D \uC77C\uC815\uC740 \uC6D0\uD558\uB294 \uB0A0\uC9DC\uB9CC \uC120\uD0DD\uD558\uBA74 \uB429\uB2C8\uB2E4."
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        card.addView(content)
        return card
    }

    private fun addFormContent(content: LinearLayout) {
        val draft = editingEvent ?: createNewDraftEvent(clickedDate)

        titleInput = createTextInput("\uC608: \uAC70\uC2E4 \uB3C4\uBC30", multiLine = false).apply {
            setText(draft.title)
        }
        content.addView(createTextInputLayout("\uC77C\uC815 \uC81C\uBAA9", titleInput, multiLine = false))

        contentInput = createTextInput("\uBA54\uBAA8\uB098 \uC0C1\uC138 \uB0B4\uC6A9\uC744 \uC801\uC5B4\uC8FC\uC138\uC694", multiLine = true).apply {
            setText(draft.content)
        }
        content.addView(createTextInputLayout("\uC77C\uC815 \uB0B4\uC6A9", contentInput, multiLine = true))

        content.addView(createSectionTitle("\uCE74\uD14C\uACE0\uB9AC"))
        categoryGroup = createSingleChoiceChipGroup()
        ScheduleCategory.entries.forEach { category ->
            categoryGroup.addView(
                createChoiceChip(
                    text = category.label,
                    tag = category,
                    checked = category == draft.category,
                    checkedColor = getUserAccentColor(activeUserName),
                )
            )
        }
        content.addView(categoryGroup)

        content.addView(createSectionTitle("\uB0A0\uC9DC \uBC29\uC2DD"))
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val continuousRadio = createRadioButton("\uC5F0\uC18D \uC77C\uC815")
        val selectedRadio = createRadioButton("\uC120\uD0DD \uB0A0\uC9DC \uC77C\uC815")
        typeGroup.addView(continuousRadio)
        typeGroup.addView(selectedRadio)
        typeGroup.check(if (scheduleType == ScheduleType.SELECTED) selectedRadio.id else continuousRadio.id)
        typeGroup.setOnCheckedChangeListener { _, checkedId ->
            scheduleType = if (checkedId == selectedRadio.id) ScheduleType.SELECTED else ScheduleType.CONTINUOUS
            updateDateSections()
        }
        content.addView(typeGroup)

        continuousSection = createInnerSection()
        startDateButton = createDateSelectorText("\uC2DC\uC791\uC77C", startDate).apply {
            setOnClickListener {
                showDatePicker(startDate) { pickedDate ->
                    startDate = pickedDate
                    if (endDate.isBefore(startDate)) {
                        endDate = startDate
                        setDateSelectorText(endDateButton, "\uC885\uB8CC\uC77C", endDate)
                    }
                    setDateSelectorText(this, "\uC2DC\uC791\uC77C", startDate)
                }
            }
        }
        continuousSection.addView(startDateButton)

        endDateButton = createDateSelectorText("\uC885\uB8CC\uC77C", endDate).apply {
            setOnClickListener {
                showDatePicker(endDate) { pickedDate ->
                    endDate = pickedDate
                    setDateSelectorText(this, "\uC885\uB8CC\uC77C", endDate)
                }
            }
        }
        continuousSection.addView(endDateButton)
        content.addView(continuousSection)

        selectedSection = createInnerSection()
        selectedSection.addView(createBodyText("\uC120\uD0DD\uD55C \uB0A0\uC9DC\uB9C8\uB2E4 \uCE74\uD14C\uACE0\uB9AC\uB97C \uB530\uB85C \uC9C0\uC815\uD560 \uC218 \uC788\uC5B4\uC694."))
        selectedDateList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        refreshSelectedDateRows()
        selectedSection.addView(selectedDateList)

        val selectedButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        selectedButtonRow.addView(createSmallActionButton("\uB0A0\uC9DC \uCD94\uAC00").apply {
            setOnClickListener {
                showDatePicker(clickedDate) { pickedDate ->
                    if (!selectedDates.contains(pickedDate)) {
                        selectedDates.add(pickedDate)
                        selectedDateCategories[pickedDate] = getDefaultSelectedDateCategory()
                    }
                    refreshSelectedDateRows()
                }
            }
        })
        selectedButtonRow.addView(createSmallActionButton("\uCD08\uAE30\uD654").apply {
            setOnClickListener {
                selectedDates.clear()
                selectedDateCategories.clear()
                selectedDates.add(clickedDate)
                selectedDateCategories[clickedDate] = getDefaultSelectedDateCategory()
                refreshSelectedDateRows()
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, 0, 0) })
        selectedSection.addView(selectedButtonRow)
        content.addView(selectedSection)
        updateDateSections()
    }

    private fun createBottomBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(color(R.color.dark_background))

            addView(createSmallActionButton("\uCDE8\uC18C").apply {
                setOnClickListener { finish() }
            })

            addView(createSmallActionButton(if (editingEvent == null) "\uB4F1\uB85D" else "\uC218\uC815 \uC644\uB8CC").apply {
                setTextColor(color(R.color.dark_background))
                backgroundTintList = ColorStateList.valueOf(getUserAccentColor(activeUserName))
                setOnClickListener { saveSchedule() }
            }, LinearLayout.LayoutParams(WRAP, dp(48)).apply { setMargins(dp(10), 0, 0, 0) })
        }
    }

    private fun saveSchedule() {
        val title = titleInput.text?.toString()?.trim().orEmpty()
        val body = contentInput.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            titleInput.error = "\uC81C\uBAA9\uC744 \uC785\uB825\uD574\uC8FC\uC138\uC694."
            return
        }

        val selectedCategory = findCheckedTag(categoryGroup, ScheduleCategory::class.java, ScheduleCategory.WALLPAPER)
        val eventToSave = editingEvent?.copy() ?: ScheduleEvent()
        eventToSave.title = title
        eventToSave.content = body
        eventToSave.category = selectedCategory
        eventToSave.userName = activeUserName
        eventToSave.scheduleType = scheduleType

        if (scheduleType == ScheduleType.CONTINUOUS) {
            if (endDate.isBefore(startDate)) {
                Toast.makeText(this, "\uC885\uB8CC\uC77C\uC740 \uC2DC\uC791\uC77C\uBCF4\uB2E4 \uBE60\uB97C \uC218 \uC5C6\uC5B4\uC694.", Toast.LENGTH_SHORT).show()
                return
            }
            eventToSave.startDate = startDate
            eventToSave.endDate = endDate
            eventToSave.selectedDates = emptyList()
            eventToSave.selectedDateCategories = emptyMap()
        } else {
            val normalizedDates = normalizeDates(selectedDates)
            if (normalizedDates.isEmpty()) {
                Toast.makeText(this, "\uC120\uD0DD \uB0A0\uC9DC\uB97C \uD558\uB098 \uC774\uC0C1 \uCD94\uAC00\uD574\uC8FC\uC138\uC694.", Toast.LENGTH_SHORT).show()
                return
            }
            eventToSave.startDate = normalizedDates.first()
            eventToSave.endDate = normalizedDates.last()
            eventToSave.selectedDates = normalizedDates
            eventToSave.selectedDateCategories = normalizedDates.associateWith { date ->
                selectedDateCategories[date] ?: selectedCategory
            }
        }
        eventToSave.materialOrderedDates = eventToSave.materialOrderedDates
            .filter { eventToSave.occursOn(it) }
            .toSortedSet()
            .toList()

        val notificationAction = if (editingEvent == null) {
            scheduleRepository.addEvent(eventToSave)
            ScheduleNotificationAction.ADDED
        } else {
            scheduleRepository.updateEvent(eventToSave)
            ScheduleNotificationAction.UPDATED
        }
        ScheduleNotificationRepository.recordScheduleChange(this, notificationAction, eventToSave)
        setResult(RESULT_OK)
        finish()
    }

    private fun createNewDraftEvent(date: LocalDate): ScheduleEvent {
        return ScheduleEvent(
            userName = activeUserName,
            startDate = date,
            endDate = date,
            scheduleType = ScheduleType.CONTINUOUS,
        )
    }

    private fun updateDateSections() {
        continuousSection.visibility = if (scheduleType == ScheduleType.CONTINUOUS) View.VISIBLE else View.GONE
        selectedSection.visibility = if (scheduleType == ScheduleType.SELECTED) View.VISIBLE else View.GONE
    }

    private fun refreshSelectedDateRows() {
        selectedDateList.removeAllViews()
        normalizeDates(selectedDates).forEach { date ->
            selectedDateList.addView(createSelectedDateRow(date))
        }
    }

    private fun createSelectedDateRow(date: LocalDate): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.calendar_day_background))
            strokeColor = color(R.color.calendar_day_stroke)
            strokeWidth = dp(1)
            radius = dp(12).toFloat()
            useCompatPadding = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = formatFullDate(date)
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
            setTextColor(color(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        topRow.addView(createSmallActionButton("\uC0AD\uC81C").apply {
            setTextColor(color(R.color.calendar_weekend_sunday))
            setOnClickListener {
                selectedDates.remove(date)
                selectedDateCategories.remove(date)
                refreshSelectedDateRows()
            }
        })
        content.addView(topRow)

        val dateCategoryGroup = createSingleChoiceChipGroup()
        val checkedCategory = selectedDateCategories[date] ?: getDefaultSelectedDateCategory()
        ScheduleCategory.entries.forEach { category ->
            dateCategoryGroup.addView(
                createChoiceChip(
                    text = category.label,
                    tag = category,
                    checked = category == checkedCategory,
                    checkedColor = getUserAccentColor(activeUserName),
                )
            )
        }
        dateCategoryGroup.setOnCheckedChangeListener { group, _ ->
            selectedDateCategories[date] = findCheckedTag(group, ScheduleCategory::class.java, checkedCategory)
        }
        content.addView(dateCategoryGroup, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        card.addView(content)
        return card
    }

    private fun getDefaultSelectedDateCategory(): ScheduleCategory {
        return if (::categoryGroup.isInitialized) {
            findCheckedTag(categoryGroup, ScheduleCategory::class.java, ScheduleCategory.WALLPAPER)
        } else {
            ScheduleCategory.WALLPAPER
        }
    }

    private fun <T> findCheckedTag(chipGroup: ChipGroup, expectedType: Class<T>, fallback: T): T {
        val checkedChipId = chipGroup.checkedChipId
        if (checkedChipId == View.NO_ID) return fallback
        val tag = chipGroup.findViewById<View>(checkedChipId)?.tag
        return if (expectedType.isInstance(tag)) expectedType.cast(tag) ?: fallback else fallback
    }

    private fun normalizeDates(dates: List<LocalDate>): List<LocalDate> = dates.toSortedSet().toList()

    private fun createTextInput(placeholder: String, multiLine: Boolean): TextInputEditText {
        return TextInputEditText(this).apply {
            hint = placeholder
            textSize = 16f
            background = createRoundedBackground(color(R.color.calendar_day_background), color(R.color.calendar_day_stroke), dp(12))
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_muted))
            if (multiLine) {
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(14), dp(14), dp(14), dp(14))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                setSingleLine(true)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), 0, dp(14), 0)
                inputType = InputType.TYPE_CLASS_TEXT
            }
        }
    }

    private fun createTextInputLayout(label: String, editText: TextInputEditText, multiLine: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) }
            addView(TextView(this@ScheduleEditorActivity).apply {
                text = label
                setTextAppearance(R.style.TextAppearance_FamWall_SectionTitle)
                textSize = 14f
            })
            addView(editText, LinearLayout.LayoutParams(MATCH, if (multiLine) dp(132) else dp(54)).apply {
                setMargins(0, dp(8), 0, 0)
            })
        }
    }

    private fun createInnerSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextAppearance(R.style.TextAppearance_FamWall_SectionTitle)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(20), 0, dp(6)) }
        }
    }

    private fun createBodyText(body: String): TextView {
        return TextView(this).apply {
            text = body
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
        }
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

    private fun createSingleChoiceChipGroup(): ChipGroup {
        return ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = false
        }
    }

    private fun createChoiceChip(text: String, tag: Any, checked: Boolean, checkedColor: Int): Chip {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        return Chip(this).apply {
            id = View.generateViewId()
            this.tag = tag
            this.text = text
            isCheckable = true
            isChecked = checked
            setTextColor(ColorStateList(states, intArrayOf(color(R.color.dark_background), color(R.color.text_primary))))
            chipBackgroundColor = ColorStateList(states, intArrayOf(checkedColor, color(R.color.day_chip_background)))
            chipStrokeColor = ColorStateList(states, intArrayOf(checkedColor, color(R.color.calendar_day_stroke)))
            chipStrokeWidth = dp(1).toFloat()
            isCheckedIconVisible = false
        }
    }

    private fun createRadioButton(text: String): RadioButton {
        return RadioButton(this).apply {
            id = View.generateViewId()
            this.text = text
            setTextColor(color(R.color.text_primary))
            textSize = 16f
        }
    }

    private fun createDateSelectorText(label: String, date: LocalDate): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(color(R.color.text_primary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = createRoundedBackground(color(R.color.day_chip_background), color(R.color.calendar_day_stroke), dp(12))
            setDateSelectorText(this, label, date)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply { setMargins(0, dp(10), 0, 0) }
        }
    }

    private fun setDateSelectorText(textView: TextView, label: String, date: LocalDate) {
        textView.text = "$label · ${formatFullDate(date)}"
    }

    private fun showDatePicker(initialDate: LocalDate, callback: (LocalDate) -> Unit) {
        val datePicker = DatePicker(this).apply {
            init(initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth, null)
            val horizontalPadding = dp(12)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("\uB0A0\uC9DC \uC120\uD0DD")
            .setView(datePicker)
            .setNegativeButton("\uCDE8\uC18C", null)
            .setPositiveButton("\uC644\uB8CC") { _, _ ->
                callback(LocalDate.of(datePicker.year, datePicker.month + 1, datePicker.dayOfMonth))
            }
            .show()
    }

    private fun formatFullDate(date: LocalDate?): String {
        return date?.format(DateTimeFormatter.ofPattern("yyyy\uB144 M\uC6D4 d\uC77C EEEE", Locale.KOREAN))
            ?: "\uB0A0\uC9DC \uC5C6\uC74C"
    }

    private fun formatShortDate(date: LocalDate?): String {
        return date?.format(DateTimeFormatter.ofPattern("M.d(E)", Locale.KOREAN)) ?: "\uB0A0\uC9DC \uC5C6\uC74C"
    }

    private fun createRoundedBackground(fillColor: Int, strokeColor: Int, cornerRadiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx.toFloat()
            setStroke(dp(1), strokeColor)
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

    companion object {
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_EVENT_ID = "extra_event_id"
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
