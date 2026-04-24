package com.example.famwall

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var preferences: SharedPreferences
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var calendarGrid: GridLayout
    private lateinit var calendarHeader: View
    private lateinit var scrollContent: View
    private lateinit var calendarMonthTitle: TextView
    private lateinit var calendarGestureDetector: GestureDetector
    private lateinit var updateManager: GitHubUpdateManager

    private var displayedMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()
    private var activeUserName: String = ""
    private var todayText: String = ""
    private var isMonthAnimating = false
    private var didCalendarSwipe = false
    private var touchDownX = 0f
    private var touchDownY = 0f

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoreanLocaleContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.top_toolbar)
        todayText = LocalDate.now().format(DateTimeFormatter.ofPattern("M'\uC6D4' d'\uC77C' EEEE", Locale.KOREAN))
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        calendarGrid = findViewById(R.id.calendar_grid)
        calendarHeader = findViewById(R.id.calendar_header)
        scrollContent = findViewById(R.id.scroll_content)
        calendarMonthTitle = findViewById(R.id.calendar_month_title)
        scheduleRepository = createScheduleRepository()
        updateManager = GitHubUpdateManager(this)

        setSupportActionBar(toolbar)
        setupCalendar()
        setupCurrentUser()
        applySystemBarInsets()
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        if (::calendarGrid.isInitialized && activeUserName.isNotEmpty()) {
            scheduleRepository.close()
            scheduleRepository = createScheduleRepository()
            renderCalendar(activeUserName)
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
            if (::calendarGrid.isInitialized && activeUserName.isNotEmpty()) {
                renderCalendar(activeUserName)
            }
        }
    }

    private fun checkForUpdate() {
        if (!::updateManager.isInitialized) {
            return
        }

        updateManager.checkForUpdate(object : GitHubUpdateManager.UpdateCallback {
            override fun onChecking() {
                Toast.makeText(this@MainActivity, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()
            }

            override fun onUpdateAvailable(release: GitHubUpdateManager.GitHubRelease) {
                showUpdateDialog(release)
            }

            override fun onNoUpdate() = Unit

            override fun onError(error: Throwable) {
                Toast.makeText(this@MainActivity, "업데이트 확인에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showUpdateDialog(release: GitHubUpdateManager.GitHubRelease) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }

        content.addView(TextView(this).apply {
            text = "현재 버전: ${updateManager.getCurrentVersion()}\n최신 버전: ${release.version}"
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(color(R.color.text_primary))
        })

        content.addView(TextView(this).apply {
            text = release.changeLog.ifBlank { release.body.ifBlank { "업데이트 내용이 없습니다." } }
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setPadding(0, dp(12), 0, 0)
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (release.forceUpdate) "필수 업데이트" else "새 업데이트가 있습니다")
            .setView(content)
            .setPositiveButton("업데이트", null)
            .apply {
                if (!release.forceUpdate) {
                    setNegativeButton("나중에 하기", null)
                }
            }
            .setCancelable(!release.forceUpdate)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startUpdateDownload(dialog, release)
            }
        }
        dialog.show()
    }

    private fun startUpdateDownload(
        updateDialog: AlertDialog,
        release: GitHubUpdateManager.GitHubRelease,
    ) {
        updateDialog.setCancelable(false)
        updateDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        updateDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

        val progressContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val progressText = TextView(this).apply {
            text = "업데이트 파일을 준비하고 있어요."
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        progressContent.addView(progressText)
        progressContent.addView(progressBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(12), 0, 0) })

        updateDialog.dismiss()
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("업데이트 다운로드")
            .setView(progressContent)
            .setCancelable(false)
            .create()
        progressDialog.show()

        updateManager.downloadUpdateFile(release, object : GitHubUpdateManager.DownloadCallback {
            override fun onStarted(fileName: String) {
                progressText.text = "$fileName 다운로드를 시작합니다."
            }

            override fun onProgress(progress: Int) {
                progressBar.progress = progress
                progressText.text = "다운로드 중... $progress%"
            }

            override fun onCompleted(file: java.io.File) {
                progressDialog.dismiss()
                val installStarted = updateManager.installUpdateFile(file)
                if (installStarted) {
                    Toast.makeText(this@MainActivity, "설치 화면에서 업데이트를 승인해주세요.", Toast.LENGTH_LONG).show()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("설치 권한 필요")
                        .setMessage("알 수 없는 앱 설치 권한을 허용한 뒤 다시 업데이트를 진행해주세요.")
                        .setPositiveButton("확인", null)
                        .show()
                }
            }

            override fun onError(error: Throwable) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("업데이트 실패")
                    .setMessage("업데이트 다운로드에 실패했습니다. 나중에 다시 시도해주세요.")
                    .setPositiveButton("확인", null)
                    .show()
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_change_user) {
            showUserDialog(canCancel = true)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupCurrentUser() {
        if (preferences.contains(KEY_SELECTED_USER)) {
            updateToolbarUser(preferences.getString(KEY_SELECTED_USER, getString(R.string.user_choi)) ?: getString(R.string.user_choi))
            return
        }

        activeUserName = getString(R.string.user_choi)
        renderCalendar(activeUserName)
        toolbar.subtitle = getString(R.string.toolbar_subtitle_select_user, todayText)
        showUserDialog(canCancel = false)
    }

    private fun setupCalendar() {
        setupCalendarSwipe()
        findViewById<MaterialButton>(R.id.search_schedule_button).setOnClickListener { openScheduleSearchPage() }
        findViewById<MaterialButton>(R.id.today_button).setOnClickListener { goToToday() }
        findViewById<MaterialButton>(R.id.previous_month_button).setOnClickListener { showMonthOffset(-1) }
        findViewById<MaterialButton>(R.id.next_month_button).setOnClickListener { showMonthOffset(1) }
    }

    private fun openScheduleSearchPage() {
        startActivity(Intent(this, ScheduleSearchActivity::class.java))
    }

    private fun setupCalendarSwipe() {
        calendarGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(start: MotionEvent?, end: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (start == null) return false

                val deltaX = end.rawX - start.rawX
                val deltaY = end.rawY - start.rawY
                val isHorizontalSwipe = kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)

                if (isHorizontalSwipe) {
                    val isLongEnough = kotlin.math.abs(deltaX) > dp(64)
                    val isFastEnough = kotlin.math.abs(velocityX) > 160
                    if (!isLongEnough || !isFastEnough) return false

                    markCalendarSwiped()
                    showMonthOffset(if (deltaX < 0) 1 else -1)
                    return true
                }

                return false
            }
        })

        calendarGrid.setOnTouchListener(::handleCalendarTouch)
        scrollContent.setOnTouchListener(::handleCalendarTouch)
    }

    private fun markCalendarSwiped() {
        didCalendarSwipe = true
        calendarGrid.postDelayed({ didCalendarSwipe = false }, 350)
    }

    private fun showUserDialog(canCancel: Boolean) {
        val users = arrayOf(
            getString(R.string.user_choi),
            getString(R.string.user_han),
            getString(R.string.user_lee),
        )
        val currentUser = preferences.getString(KEY_SELECTED_USER, users[0]) ?: users[0]
        var selectedIndex = users.indexOf(currentUser).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_user_title)
            .setSingleChoiceItems(users, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(R.string.select_user_confirm) { _, _ ->
                val selectedUser = users[selectedIndex]
                preferences.edit().putString(KEY_SELECTED_USER, selectedUser).apply()
                updateToolbarUser(selectedUser)
            }
            .setCancelable(canCancel)
            .show()
    }

    private fun updateToolbarUser(userName: String) {
        toolbar.subtitle = getString(R.string.toolbar_subtitle_format, userName, todayText)
        applyUserColor(userName)
    }

    private fun applyUserColor(userName: String) {
        activeUserName = userName
        toolbar.setSubtitleTextColor(getUserAccentColor(userName))
        renderCalendar(userName)
    }

    private fun renderCalendar(userName: String) {
        val accentColor = getUserAccentColor(userName)
        val mutedText = color(R.color.text_muted)
        val sundayText = color(R.color.calendar_weekend_sunday)
        val saturdayText = color(R.color.calendar_weekend_saturday)
        val darkText = color(R.color.dark_background)

        calendarGrid.removeAllViews()
        calendarHeader.background = createCalendarCellBackground(accentColor, accentColor, 0)
        calendarMonthTitle.text = displayedMonth.format(DateTimeFormatter.ofPattern("yyyy  M'\uC6D4'", Locale.KOREAN))
        calendarMonthTitle.setTextColor(darkText)

        val weekdays = arrayOf(
            getString(R.string.day_sun),
            getString(R.string.day_mon),
            getString(R.string.day_tue),
            getString(R.string.day_wed),
            getString(R.string.day_thu),
            getString(R.string.day_fri),
            getString(R.string.day_sat),
        )

        weekdays.forEachIndexed { index, weekday ->
            calendarGrid.addView(createCalendarHeaderCell().apply {
                text = weekday
                setTextAppearance(R.style.TextAppearance_FamWall_CalendarWeekday)
                setTextColor(
                    when (index) {
                        0 -> sundayText
                        6 -> saturdayText
                        else -> mutedText
                    }
                )
            })
        }

        val today = LocalDate.now()
        val leadingDays = displayedMonth.atDay(1).dayOfWeek.value % 7
        val firstVisibleDate = displayedMonth.atDay(1).minusDays(leadingDays.toLong())

        repeat(42) { index ->
            val cellDate = firstVisibleDate.plusDays(index.toLong())
            val dateEvents = scheduleRepository.getEventsForDate(cellDate)
            val dayCell = createCalendarDayCell(
                cellDate = cellDate,
                isCurrentMonth = YearMonth.from(cellDate) == displayedMonth,
                isToday = cellDate == today,
                isSelected = cellDate == selectedDate,
                dateEvents = dateEvents,
            ).apply {
                setOnClickListener { handleDateCellClick(cellDate) }
                setOnTouchListener(::handleCalendarTouch)
            }
            calendarGrid.addView(dayCell)
        }
    }

    private fun createCalendarHeaderCell(): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(42)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 0, 0)
            }
        }
    }

    private fun createCalendarDayCell(
        cellDate: LocalDate,
        isCurrentMonth: Boolean,
        isToday: Boolean,
        isSelected: Boolean,
        dateEvents: List<ScheduleEvent>,
    ): View {
        val accentColor = getUserAccentColor(activeUserName)
        val dayBackground = color(R.color.calendar_day_background)
        val dayStroke = color(R.color.calendar_day_stroke)
        val primaryText = color(R.color.text_primary)
        val secondaryText = color(R.color.text_secondary)
        val sundayText = color(R.color.calendar_weekend_sunday)
        val saturdayText = color(R.color.calendar_weekend_saturday)
        val outsideMonthText = color(R.color.calendar_outside_month)
        val darkText = color(R.color.dark_background)

        val dayCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            isClickable = true
            setPadding(dp(5), dp(7), dp(4), dp(5))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(112)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 0, 0)
            }
        }

        var dateTextColor = primaryText
        var summaryTextColor = secondaryText
        var background = createCalendarCellBackground(dayBackground, dayStroke, 0)
        val materialOrderedEvents = dateEvents.filter { it.isMaterialOrderedForDate(cellDate) }
        when {
            isSelected -> background = createCalendarCellBackground(dayBackground, accentColor, 0, 3)
            isToday -> {
                dateTextColor = darkText
                summaryTextColor = darkText
                background = createCalendarCellBackground(accentColor, accentColor, 0)
            }
            !isCurrentMonth -> {
                dateTextColor = outsideMonthText
                summaryTextColor = outsideMonthText
            }
            cellDate.dayOfWeek.value == 7 -> dateTextColor = sundayText
            cellDate.dayOfWeek.value == 6 -> dateTextColor = saturdayText
        }
        dayCell.background = background

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dayCell.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        topRow.addView(TextView(this).apply {
            text = cellDate.dayOfMonth.toString()
            setTextAppearance(R.style.TextAppearance_FamWall_CalendarDay)
            setTextColor(dateTextColor)
            setTypeface(Typeface.DEFAULT, if (isSelected || isToday) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP, dp(22)))

        topRow.addView(createMaterialOrderStatusRow(materialOrderedEvents), LinearLayout.LayoutParams(WRAP, dp(22)))

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        dayCell.addView(badgeRow, LinearLayout.LayoutParams(MATCH, dp(20)).apply { setMargins(0, dp(2), 0, 0) })
        addEventBadges(badgeRow, dateEvents, cellDate)
        addEventSummaryLines(dayCell, dateEvents, summaryTextColor, cellDate)

        return dayCell
    }

    private fun createMaterialOrderStatusRow(materialOrderedEvents: List<ScheduleEvent>): View {
        return LinearLayout(this).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL

            materialOrderedEvents.take(MAX_MATERIAL_CHECKS_IN_CELL).forEach { materialOrderedEvent ->
                addView(createMaterialOrderCheckBadge(materialOrderedEvent))
            }

            val hiddenCount = materialOrderedEvents.size - MAX_MATERIAL_CHECKS_IN_CELL
            if (hiddenCount > 0) {
                addView(createMaterialOrderMoreBadge(hiddenCount))
            }
        }
    }

    private fun createMaterialOrderCheckBadge(materialOrderedEvent: ScheduleEvent): TextView {
        return TextView(this).apply {
            text = "\u2713"
            textSize = 10f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(getUserAccentColor(materialOrderedEvent.userName))
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(18)).apply { setMargins(dp(1), 0, 0, 0) }
        }
    }

    private fun createMaterialOrderMoreBadge(hiddenCount: Int): TextView {
        return TextView(this).apply {
            text = "+$hiddenCount"
            textSize = 8.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(dp(1), 0, 0, 0) }
        }
    }

    private fun addEventBadges(badgeRow: LinearLayout, dateEvents: List<ScheduleEvent>, cellDate: LocalDate) {
        val visibleBadgeCount = minOf(dateEvents.size, MAX_EVENT_BADGES_IN_CELL)
        dateEvents.take(visibleBadgeCount).forEach { badgeRow.addView(createEventBadge(it, cellDate)) }

        val hiddenCount = dateEvents.size - visibleBadgeCount
        if (hiddenCount > 0) {
            badgeRow.addView(createMoreBadge(hiddenCount))
        }
    }

    private fun createEventBadge(event: ScheduleEvent, cellDate: LocalDate): TextView {
        return TextView(this).apply {
            text = event.categoryForDate(cellDate).initial
            textSize = 9f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color(R.color.dark_background))
            background = createOvalBackground(getUserAccentColor(event.userName))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(0, 0, dp(2), 0) }
        }
    }

    private fun createMoreBadge(hiddenCount: Int): TextView {
        return TextView(this).apply {
            text = "+$hiddenCount"
            textSize = 9f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color(R.color.text_primary))
            background = createCalendarCellBackground(color(R.color.day_chip_background), color(R.color.calendar_day_stroke), 8)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(18)).apply { setMargins(0, 0, dp(2), 0) }
        }
    }

    private fun addEventSummaryLines(dayCell: LinearLayout, dateEvents: List<ScheduleEvent>, textColor: Int, cellDate: LocalDate) {
        val lineCount = minOf(dateEvents.size, MAX_EVENT_SUMMARY_LINES_IN_CELL)
        dateEvents.take(lineCount).forEach { event ->
            dayCell.addView(TextView(this).apply {
                text = buildEventCellSummary(event, cellDate)
                textSize = 10f
                setTextColor(textColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.START
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(4), 0, 0) })
        }

        val hiddenLineCount = dateEvents.size - lineCount
        if (hiddenLineCount > 0) {
            dayCell.addView(TextView(this).apply {
                text = "\uC678 ${hiddenLineCount}\uAC1C"
                textSize = 10f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(textColor)
                isSingleLine = true
                includeFontPadding = false
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(5), 0, 0) })
        }
    }

    private fun buildEventCellSummary(event: ScheduleEvent, cellDate: LocalDate): String {
        val title = event.title.trim()
            .ifEmpty { event.content.trim() }
            .ifEmpty { "\uC77C\uC815" }
        return "${event.categoryForDate(cellDate).initial} $title"
    }

    private fun handleDateCellClick(cellDate: LocalDate) {
        if (didCalendarSwipe) {
            didCalendarSwipe = false
            return
        }

        selectedDate = cellDate
        val cellMonth = YearMonth.from(cellDate)
        if (cellMonth != displayedMonth) {
            showMonth(cellMonth)
            return
        }

        val dateEvents = scheduleRepository.getEventsForDate(cellDate)
        renderCalendar(activeUserName)
        if (dateEvents.isEmpty()) {
            openScheduleEditorPage(cellDate, null)
        } else {
            openScheduleDetailPage(cellDate)
        }
    }

    private fun openScheduleDetailPage(clickedDate: LocalDate) {
        startActivity(Intent(this, ScheduleDetailActivity::class.java).apply {
            putExtra(ScheduleDetailActivity.EXTRA_DATE, clickedDate.toString())
        })
    }

    private fun openScheduleEditorPage(clickedDate: LocalDate, editingEvent: ScheduleEvent?) {
        startActivity(Intent(this, ScheduleEditorActivity::class.java).apply {
            putExtra(ScheduleEditorActivity.EXTRA_DATE, clickedDate.toString())
            editingEvent?.let { putExtra(ScheduleEditorActivity.EXTRA_EVENT_ID, it.id) }
        })
    }

    private fun handleCalendarTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.rawX
                touchDownY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - touchDownX
                val deltaY = event.rawY - touchDownY
                if (kotlin.math.abs(deltaX) > dp(8) || kotlin.math.abs(deltaY) > dp(8)) {
                    calendarGrid.parent.requestDisallowInterceptTouchEvent(true)
                    scrollContent.parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                calendarGrid.parent.requestDisallowInterceptTouchEvent(false)
                scrollContent.parent.requestDisallowInterceptTouchEvent(false)
            }
        }

        calendarGestureDetector.onTouchEvent(event)
        return false
    }

    private fun goToToday() {
        val today = LocalDate.now()
        val todayMonth = YearMonth.from(today)
        selectedDate = today

        if (todayMonth == displayedMonth) {
            renderCalendar(activeUserName)
            return
        }

        val direction = if (todayMonth.isAfter(displayedMonth)) 1 else -1
        animateCalendarTransition(direction, vertical = false) {
            displayedMonth = todayMonth
            renderCalendar(activeUserName)
        }
    }

    private fun showMonthOffset(monthOffset: Int) {
        showMonth(displayedMonth.plusMonths(monthOffset.toLong()))
    }

    private fun showMonth(targetMonth: YearMonth) {
        if (targetMonth == displayedMonth) {
            renderCalendar(activeUserName)
            return
        }

        val direction = if (targetMonth.isAfter(displayedMonth)) 1 else -1
        animateCalendarTransition(direction, vertical = false) {
            displayedMonth = targetMonth
            renderCalendar(activeUserName)
        }
    }

    private fun animateCalendarTransition(direction: Int, vertical: Boolean, updateMonth: () -> Unit) {
        if (isMonthAnimating) return

        val travelDistance = if (vertical) calendarGrid.height else calendarGrid.width
        if (travelDistance == 0) {
            updateMonth()
            return
        }

        isMonthAnimating = true
        val exitOffset = (-direction * travelDistance).toFloat()
        val enterOffset = (direction * travelDistance).toFloat()

        calendarGrid.animate().cancel()
        calendarMonthTitle.animate().cancel()

        if (vertical) {
            calendarMonthTitle.animate()
                .translationY(exitOffset * 0.12f)
                .alpha(0f)
                .setDuration(120)
                .start()

            calendarGrid.animate()
                .translationY(exitOffset)
                .alpha(0.25f)
                .setDuration(140)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        calendarGrid.animate().setListener(null)
                        calendarGrid.translationY = enterOffset
                        calendarGrid.alpha = 0.25f
                        calendarMonthTitle.translationY = enterOffset * 0.12f
                        calendarMonthTitle.alpha = 0f
                        updateMonth()

                        calendarMonthTitle.animate().translationY(0f).alpha(1f).setDuration(160).start()
                        calendarGrid.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(180)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    calendarGrid.animate().setListener(null)
                                    isMonthAnimating = false
                                }
                            })
                            .start()
                    }
                })
                .start()
            return
        }

        calendarMonthTitle.animate()
            .translationX(exitOffset * 0.25f)
            .alpha(0f)
            .setDuration(120)
            .start()

        calendarGrid.animate()
            .translationX(exitOffset)
            .alpha(0.25f)
            .setDuration(140)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    calendarGrid.animate().setListener(null)
                    calendarGrid.translationX = enterOffset
                    calendarGrid.alpha = 0.25f
                    calendarMonthTitle.translationX = enterOffset * 0.25f
                    calendarMonthTitle.alpha = 0f
                    updateMonth()

                    calendarMonthTitle.animate().translationX(0f).alpha(1f).setDuration(160).start()
                    calendarGrid.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(180)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                calendarGrid.animate().setListener(null)
                                isMonthAnimating = false
                            }
                        })
                        .start()
                }
            })
            .start()
    }

    private fun createCalendarCellBackground(fillColor: Int, strokeColor: Int, cornerRadiusDp: Int, strokeWidthDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(cornerRadiusDp).toFloat()
            setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun createOvalBackground(fillColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }
    }

    private fun createOvalBackground(fillColor: Int, strokeColor: Int, strokeWidthDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(dp(strokeWidthDp), strokeColor)
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

    private fun applySystemBarInsets() {
        val root: View = findViewById(R.id.root_layout)
        val appBarLayout: AppBarLayout = findViewById(R.id.app_bar)
        val scrollContent: View = findViewById(R.id.scroll_content)

        val appBarTopPadding = appBarLayout.paddingTop
        val scrollLeftPadding = scrollContent.paddingLeft
        val scrollTopPadding = scrollContent.paddingTop
        val scrollRightPadding = scrollContent.paddingRight
        val scrollBottomPadding = scrollContent.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBarsInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            appBarLayout.setPadding(
                appBarLayout.paddingLeft,
                appBarTopPadding + systemBarsInsets.top,
                appBarLayout.paddingRight,
                appBarLayout.paddingBottom,
            )
            scrollContent.setPadding(
                scrollLeftPadding + systemBarsInsets.left,
                scrollTopPadding,
                scrollRightPadding + systemBarsInsets.right,
                scrollBottomPadding + systemBarsInsets.bottom,
            )

            WindowInsetsCompat.CONSUMED
        }
    }

    private fun color(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val MAX_MATERIAL_CHECKS_IN_CELL = 3
        private const val MAX_EVENT_BADGES_IN_CELL = 2
        private const val MAX_EVENT_SUMMARY_LINES_IN_CELL = 2
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
