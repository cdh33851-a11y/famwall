package com.example.famwall

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class ScheduleSearchActivity : AppCompatActivity() {
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var activeUserName: String

    private lateinit var keywordInput: TextInputEditText
    private lateinit var startDateButton: MaterialButton
    private lateinit var endDateButton: MaterialButton
    private lateinit var categoryGroup: ChipGroup
    private lateinit var resultSummaryText: TextView
    private lateinit var resultContainer: LinearLayout
    private lateinit var previousPageButton: MaterialButton
    private lateinit var nextPageButton: MaterialButton
    private lateinit var pageInfoText: TextView

    private val searchState = SearchState()
    private var allSearchResults: List<ScheduleSearchResult> = emptyList()
    private var allCategoryChipId: Int = View.NO_ID

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoreanLocaleContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        FamWallThemeManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)

        val preferences: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        activeUserName = preferences.getString(KEY_SELECTED_USER, getString(R.string.user_choi))
            ?: getString(R.string.user_choi)
        scheduleRepository = createScheduleRepository()

        setContentView(createPage())
    }

    override fun onResume() {
        super.onResume()
        if (::resultContainer.isInitialized) {
            scheduleRepository.close()
            scheduleRepository = createScheduleRepository()
            performSearch(resetPage = false)
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
            if (::resultContainer.isInitialized) {
                performSearch(resetPage = false)
            }
        }
    }

    private fun createPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.app_background))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "일정검색"
            subtitle = "$activeUserName · 전체 일정"
            setTitleTextColor(color(R.color.text_primary))
            setSubtitleTextColor(getUserAccentColor(activeUserName))
            setNavigationIcon(R.drawable.ic_chevron_left)
            navigationContentDescription = "뒤로"
            setNavigationOnClickListener { finish() }
            setPadding(dp(6), dp(10), dp(18), dp(10))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            addView(createSearchConditionCard())

            resultSummaryText = TextView(this@ScheduleSearchActivity).apply {
                setTextAppearance(R.style.TextAppearance_FamWall_SectionTitle)
                text = "검색 결과"
            }
            addView(resultSummaryText, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(18), 0, dp(10)) })

            resultContainer = LinearLayout(this@ScheduleSearchActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(resultContainer)
        }
        scrollView.addView(content)
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val paginationBar = createPaginationBar()
        root.addView(paginationBar)
        applyInsets(root, toolbar, paginationBar)
        return root
    }

    private fun createSearchConditionCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            strokeColor = getUserAccentColor(activeUserName)
            strokeWidth = dp(1)
            radius = dp(18).toFloat()
            useCompatPadding = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }

        content.addView(TextView(this).apply {
            text = "검색 조건"
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
        })

        keywordInput = createTextInput("제목 또는 내용 검색")
        content.addView(createInputSection("검색어", keywordInput))

        content.addView(createSectionTitle("날짜 범위"))
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        startDateButton = createDateButton("시작일", searchState.startDate).apply {
            setOnClickListener {
                showDatePicker(searchState.startDate ?: LocalDate.now()) { pickedDate ->
                    searchState.startDate = pickedDate
                    setDateButtonText(this, "시작일", pickedDate)
                }
            }
        }
        endDateButton = createDateButton("종료일", searchState.endDate).apply {
            setOnClickListener {
                showDatePicker(searchState.endDate ?: searchState.startDate ?: LocalDate.now()) { pickedDate ->
                    searchState.endDate = pickedDate
                    setDateButtonText(this, "종료일", pickedDate)
                }
            }
        }
        dateRow.addView(startDateButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        dateRow.addView(endDateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        content.addView(dateRow)

        content.addView(createSectionTitle("카테고리"))
        categoryGroup = createSingleChoiceChipGroup()
        allCategoryChipId = View.generateViewId()
        categoryGroup.addView(createChoiceChip("전체", CATEGORY_ALL_TAG, checked = true, checkedColor = getUserAccentColor(activeUserName)).apply {
            id = allCategoryChipId
        })
        ScheduleCategory.entries.forEach { category ->
            categoryGroup.addView(
                createChoiceChip(
                    text = category.label,
                    tag = category,
                    checked = false,
                    checkedColor = getUserAccentColor(activeUserName),
                )
            )
        }
        categoryGroup.setOnCheckedChangeListener { _, _ ->
            searchState.category = findSelectedCategory()
        }
        content.addView(categoryGroup)

        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow.addView(createActionButton("초기화", primary = false).apply {
            setOnClickListener { resetSearchConditions() }
        })
        actionRow.addView(createActionButton("검색", primary = true).apply {
            setOnClickListener { performSearch(resetPage = true) }
        }, LinearLayout.LayoutParams(WRAP, dp(46)).apply { setMargins(dp(10), 0, 0, 0) })
        content.addView(actionRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) })

        card.addView(content)
        return card
    }

    private fun createPaginationBar(): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(color(R.color.app_background))

            previousPageButton = createActionButton("이전", primary = false).apply {
                setOnClickListener { movePage(-1) }
            }
            addView(previousPageButton, LinearLayout.LayoutParams(WRAP, dp(46)))

            pageInfoText = TextView(this@ScheduleSearchActivity).apply {
                gravity = Gravity.CENTER
                setTextAppearance(R.style.TextAppearance_FamWall_Body)
                setTextColor(color(R.color.text_primary))
            }
            addView(pageInfoText, LinearLayout.LayoutParams(0, WRAP, 1f))

            nextPageButton = createActionButton("다음", primary = false).apply {
                setOnClickListener { movePage(1) }
            }
            addView(nextPageButton, LinearLayout.LayoutParams(WRAP, dp(46)))
        }
    }

    private fun resetSearchConditions() {
        searchState.keyword = ""
        searchState.startDate = null
        searchState.endDate = null
        searchState.category = null
        searchState.currentPage = 1

        keywordInput.setText("")
        setDateButtonText(startDateButton, "시작일", null)
        setDateButtonText(endDateButton, "종료일", null)
        categoryGroup.check(allCategoryChipId)
        performSearch(resetPage = false)
    }

    private fun performSearch(resetPage: Boolean) {
        searchState.keyword = keywordInput.text?.toString()?.trim().orEmpty()
        searchState.category = findSelectedCategory()

        if (searchState.startDate != null && searchState.endDate != null && searchState.endDate!!.isBefore(searchState.startDate)) {
            Toast.makeText(this, "종료일은 시작일보다 빠를 수 없어요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (resetPage) {
            searchState.currentPage = 1
        }
        allSearchResults = searchSchedules(scheduleRepository.getAllEvents(), searchState)
        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        val totalPages = calculatePageCount(allSearchResults.size)
        if (searchState.currentPage > totalPages) {
            searchState.currentPage = totalPages
        }
        if (searchState.currentPage < 1) {
            searchState.currentPage = 1
        }

        val pageResults = paginateResults(allSearchResults, searchState)
        resultContainer.removeAllViews()
        resultSummaryText.text = "검색 결과 ${allSearchResults.size}개"

        if (pageResults.isEmpty()) {
            resultContainer.addView(createEmptyResultCard())
        } else {
            pageResults.forEach { result ->
                resultContainer.addView(createResultCard(result))
            }
        }

        pageInfoText.text = "${searchState.currentPage} / $totalPages"
        previousPageButton.isEnabled = searchState.currentPage > 1
        nextPageButton.isEnabled = searchState.currentPage < totalPages
        previousPageButton.alpha = if (previousPageButton.isEnabled) 1f else 0.45f
        nextPageButton.alpha = if (nextPageButton.isEnabled) 1f else 0.45f
    }

    private fun searchSchedules(events: List<ScheduleEvent>, state: SearchState): List<ScheduleSearchResult> {
        val normalizedKeyword = state.keyword.lowercase(Locale.ROOT)
        return events.mapNotNull { event ->
            if (!matchesKeyword(event, normalizedKeyword)) {
                return@mapNotNull null
            }

            val rangedDates = filterDatesByRange(event, state)
            val categoryDates = filterDatesByCategory(event, rangedDates, state.category)
            if (categoryDates.isEmpty()) {
                null
            } else {
                ScheduleSearchResult(event, categoryDates)
            }
        }.sortedWith(compareBy<ScheduleSearchResult> { it.primaryDate }.thenBy { getDisplayTitle(it.event) })
    }

    private fun matchesKeyword(event: ScheduleEvent, normalizedKeyword: String): Boolean {
        if (normalizedKeyword.isBlank()) {
            return true
        }

        val title = event.title.lowercase(Locale.ROOT)
        val content = event.content.lowercase(Locale.ROOT)
        return title.contains(normalizedKeyword) || content.contains(normalizedKeyword)
    }

    private fun filterDatesByRange(event: ScheduleEvent, state: SearchState): List<LocalDate> {
        return event.occurrenceDates().filter { date ->
            (state.startDate == null || !date.isBefore(state.startDate)) &&
                (state.endDate == null || !date.isAfter(state.endDate))
        }
    }

    private fun filterDatesByCategory(
        event: ScheduleEvent,
        dates: List<LocalDate>,
        category: ScheduleCategory?,
    ): List<LocalDate> {
        if (category == null) {
            return dates
        }

        return dates.filter { date -> event.categoryForDate(date) == category }
    }

    private fun paginateResults(
        results: List<ScheduleSearchResult>,
        state: SearchState,
    ): List<ScheduleSearchResult> {
        val startIndex = ((state.currentPage - 1) * state.pageSize).coerceAtMost(results.size)
        val endIndex = (startIndex + state.pageSize).coerceAtMost(results.size)
        return results.subList(startIndex, endIndex)
    }

    private fun movePage(offset: Int) {
        searchState.currentPage += offset
        renderCurrentPage()
    }

    private fun calculatePageCount(resultCount: Int): Int {
        return maxOf(1, (resultCount + searchState.pageSize - 1) / searchState.pageSize)
    }

    private fun createResultCard(result: ScheduleSearchResult): MaterialCardView {
        val event = result.event
        val primaryDate = result.primaryDate
        val userAccent = getUserAccentColor(event.userName)

        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            strokeColor = userAccent
            strokeWidth = dp(1)
            radius = dp(16).toFloat()
            useCompatPadding = false
            isClickable = true
            setOnClickListener { openScheduleDetail(result) }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(12)) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }

        content.addView(TextView(this).apply {
            text = formatResultDates(result.matchingDates)
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(userAccent)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = getDisplayTitle(event)
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        content.addView(titleRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        content.addView(TextView(this).apply {
            text = event.content.trim().ifEmpty { "내용 없음" }
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        content.addView(TextView(this).apply {
            text = "카테고리 · ${formatResultCategories(result)}"
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(userAccent)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        content.addView(TextView(this).apply {
            text = "등록 · ${event.userName}"
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(color(R.color.text_muted))
            isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        card.addView(content)
        return card
    }

    private fun createEmptyResultCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            strokeColor = color(R.color.calendar_day_stroke)
            strokeWidth = dp(1)
            radius = dp(16).toFloat()
            useCompatPadding = false
            addView(TextView(this@ScheduleSearchActivity).apply {
                text = "검색 결과가 없습니다."
                setTextAppearance(R.style.TextAppearance_FamWall_Body)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(22), dp(16), dp(22))
            })
        }
    }

    private fun openScheduleDetail(result: ScheduleSearchResult) {
        startActivity(Intent(this, ScheduleDetailActivity::class.java).apply {
            putExtra(ScheduleDetailActivity.EXTRA_DATE, result.primaryDate.toString())
            putExtra(ScheduleDetailActivity.EXTRA_FOCUS_EVENT_ID, result.event.id)
        })
    }

    private fun createInputSection(label: String, editText: TextInputEditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(14), 0, 0) }
            addView(TextView(this@ScheduleSearchActivity).apply {
                text = label
                setTextAppearance(R.style.TextAppearance_FamWall_SectionTitle)
                textSize = 14f
            })
            addView(editText, LinearLayout.LayoutParams(MATCH, dp(52)).apply { setMargins(0, dp(8), 0, 0) })
        }
    }

    private fun createTextInput(placeholder: String): TextInputEditText {
        return TextInputEditText(this).apply {
            hint = placeholder
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_muted))
            background = createRoundedBackground(color(R.color.calendar_day_background), color(R.color.calendar_day_stroke), dp(12))
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextAppearance(R.style.TextAppearance_FamWall_SectionTitle)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(16), 0, dp(8)) }
        }
    }

    private fun createDateButton(label: String, date: LocalDate?): MaterialButton {
        return createActionButton("", primary = false).apply {
            setDateButtonText(this, label, date)
        }
    }

    private fun setDateButtonText(button: MaterialButton, label: String, date: LocalDate?) {
        button.text = if (date == null) {
            "$label: 전체"
        } else {
            "$label: ${date.format(DateTimeFormatter.ofPattern("M.d", Locale.KOREAN))}"
        }
    }

    private fun showDatePicker(initialDate: LocalDate, callback: (LocalDate) -> Unit) {
        val datePicker = DatePicker(this).apply {
            init(initialDate.year, initialDate.monthValue - 1, initialDate.dayOfMonth, null)
            setPadding(dp(12), 0, dp(12), 0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("날짜 선택")
            .setView(datePicker)
            .setNegativeButton("취소", null)
            .setPositiveButton("완료") { _, _ ->
                callback(LocalDate.of(datePicker.year, datePicker.month + 1, datePicker.dayOfMonth))
            }
            .show()
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
            setTextColor(ColorStateList(states, intArrayOf(color(R.color.on_accent_text), color(R.color.text_primary))))
            chipBackgroundColor = ColorStateList(states, intArrayOf(checkedColor, color(R.color.day_chip_background)))
            chipStrokeColor = ColorStateList(states, intArrayOf(checkedColor, color(R.color.calendar_day_stroke)))
            chipStrokeWidth = dp(1).toFloat()
            isCheckedIconVisible = false
        }
    }

    private fun findSelectedCategory(): ScheduleCategory? {
        val checkedChipId = categoryGroup.checkedChipId
        if (checkedChipId == View.NO_ID) {
            return null
        }

        return categoryGroup.findViewById<View>(checkedChipId)?.tag as? ScheduleCategory
    }

    private fun formatResultCategories(result: ScheduleSearchResult): String {
        return result.matchingDates
            .map { result.event.categoryForDate(it) }
            .distinct()
            .joinToString(" / ") { it.label }
    }

    private fun createActionButton(text: String, primary: Boolean): MaterialButton {
        return MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minHeight = dp(42)
            minimumHeight = dp(42)
            insetTop = 0
            insetBottom = 0
            setTextColor(if (primary) color(R.color.on_accent_text) else color(R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(
                if (primary) getUserAccentColor(activeUserName) else color(R.color.day_chip_background)
            )
        }
    }

    private fun getDisplayTitle(event: ScheduleEvent): String {
        return event.title.trim()
            .ifEmpty { event.content.trim() }
            .ifEmpty { "일정" }
    }

    private fun formatResultDates(dates: List<LocalDate>): String {
        if (dates.isEmpty()) {
            return "날짜 없음"
        }

        val sortedDates = dates.sorted()
        if (sortedDates.size == 1) {
            return formatFullDate(sortedDates.first())
        }

        if (isConsecutive(sortedDates)) {
            return formatDateRange(sortedDates.first(), sortedDates.last())
        }

        if (sortedDates.size == 2) {
            return "${formatFullDate(sortedDates.first())}, ${formatMonthDay(sortedDates[1])}"
        }

        return "${formatFullDate(sortedDates.first())}, ${formatMonthDay(sortedDates[1])} 외 ${sortedDates.size - 2}일"
    }

    private fun isConsecutive(dates: List<LocalDate>): Boolean {
        return dates.zipWithNext().all { (previous, next) -> previous.plusDays(1) == next }
    }

    private fun formatDateRange(start: LocalDate, end: LocalDate): String {
        if (start.year == end.year && start.monthValue == end.monthValue) {
            return "${formatFullDate(start)} ~ ${end.dayOfMonth}일"
        }

        if (start.year == end.year) {
            return "${formatFullDate(start)} ~ ${end.monthValue}월 ${end.dayOfMonth}일"
        }

        return "${formatFullDate(start)} ~ ${formatFullDate(end)}"
    }

    private fun formatFullDate(date: LocalDate): String {
        return date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN))
    }

    private fun formatMonthDay(date: LocalDate): String {
        return date.format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN))
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

    private data class SearchState(
        var keyword: String = "",
        var startDate: LocalDate? = null,
        var endDate: LocalDate? = null,
        var category: ScheduleCategory? = null,
        var currentPage: Int = 1,
        val pageSize: Int = 10,
    )

    private data class ScheduleSearchResult(
        val event: ScheduleEvent,
        val matchingDates: List<LocalDate>,
    ) {
        val primaryDate: LocalDate
            get() = matchingDates.first()
    }

    companion object {
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val CATEGORY_ALL_TAG = "category_all"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
