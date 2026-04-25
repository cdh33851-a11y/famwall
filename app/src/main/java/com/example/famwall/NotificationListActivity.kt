package com.example.famwall

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class NotificationListActivity : AppCompatActivity() {
    private lateinit var notificationRepository: ScheduleNotificationRepository
    private lateinit var listContainer: LinearLayout
    private lateinit var activeUserName: String

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoreanLocaleContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        FamWallThemeManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)

        val preferences: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        activeUserName = preferences.getString(KEY_SELECTED_USER, getString(R.string.user_choi))
            ?: getString(R.string.user_choi)
        notificationRepository = createNotificationRepository()

        setContentView(createPage())
        intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.let { notificationId ->
            notificationRepository.getNotificationById(notificationId)?.let(::openNotification)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) {
            notificationRepository.close()
            notificationRepository = createNotificationRepository()
            renderNotificationList()
        }
    }

    override fun onDestroy() {
        if (::notificationRepository.isInitialized) {
            notificationRepository.close()
        }
        super.onDestroy()
    }

    private fun createNotificationRepository(): ScheduleNotificationRepository {
        return ScheduleNotificationRepository(this, activeUserName) {
            if (::listContainer.isInitialized) {
                renderNotificationList()
            }
        }
    }

    private fun createPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.app_background))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "알림"
            subtitle = activeUserName
            setTitleTextColor(color(R.color.text_primary))
            setSubtitleTextColor(getUserAccentColor(activeUserName))
            setNavigationIcon(R.drawable.ic_chevron_left)
            navigationContentDescription = "뒤로"
            setNavigationOnClickListener { finish() }
            setPadding(dp(6), dp(10), dp(18), dp(10))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scrollView = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(22))
        }
        scrollView.addView(listContainer)
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        applyInsets(root, toolbar, scrollView)
        renderNotificationList()
        return root
    }

    private fun renderNotificationList() {
        listContainer.removeAllViews()
        val notifications = notificationRepository.getVisibleNotifications()
        if (notifications.isEmpty()) {
            listContainer.addView(createEmptyCard())
            return
        }

        notifications.forEach { notification ->
            listContainer.addView(createNotificationCard(notification))
        }
    }

    private fun createEmptyCard(): MaterialCardView {
        return createBaseCard(color(R.color.calendar_day_stroke)).apply {
            addView(TextView(this@NotificationListActivity).apply {
                text = "아직 알림이 없어요."
                setTextAppearance(R.style.TextAppearance_FamWall_Body)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            })
        }
    }

    private fun createNotificationCard(notification: ScheduleNotification): MaterialCardView {
        val isUnread = !notification.isReadBy(activeUserName)
        val card = createBaseCard(if (isUnread) color(R.color.notification_unread_dot) else color(R.color.calendar_day_stroke)).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(12)) }
            isClickable = true
            foreground = ContextCompat.getDrawable(this@NotificationListActivity, android.R.drawable.list_selector_background)
            setOnClickListener { openNotification(notification) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        if (isUnread) {
            titleRow.addView(createUnreadDot(), LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(0, 0, dp(9), 0) })
        }
        titleRow.addView(TextView(this).apply {
            text = notification.messageTitle()
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
            textSize = 17f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(createActionChip(notification.action.label), LinearLayout.LayoutParams(WRAP, dp(28)).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        content.addView(titleRow)

        content.addView(TextView(this).apply {
            text = "${notification.dateSummary()} · ${notification.displayTitle()}"
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        content.addView(TextView(this).apply {
            text = formatRelativeTime(notification.createdAt)
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(color(R.color.text_muted))
            textSize = 13f
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, 0) })

        card.addView(content)
        return card
    }

    private fun openNotification(notification: ScheduleNotification) {
        notificationRepository.markAsRead(notification.id)
        if (notification.action == ScheduleNotificationAction.DELETED) {
            startActivity(Intent(this, DeletedNotificationDetailActivity::class.java).apply {
                putExtra(DeletedNotificationDetailActivity.EXTRA_NOTIFICATION_ID, notification.id)
            })
            return
        }

        startActivity(Intent(this, ScheduleDetailActivity::class.java).apply {
            putExtra(ScheduleDetailActivity.EXTRA_DATE, notification.primaryDate().toString())
            putExtra(ScheduleDetailActivity.EXTRA_FOCUS_EVENT_ID, notification.scheduleId)
        })
    }

    private fun createBaseCard(strokeColor: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            this.strokeColor = strokeColor
            strokeWidth = dp(1)
            radius = dp(18).toFloat()
            useCompatPadding = false
        }
    }

    private fun createUnreadDot(): View {
        return View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.color.notification_unread_dot))
            }
        }
    }

    private fun createActionChip(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(color(R.color.on_accent_text))
            backgroundTintList = ColorStateList.valueOf(getUserAccentColor(activeUserName))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(getUserAccentColor(activeUserName))
            }
        }
    }

    private fun formatRelativeTime(createdAt: Long): String {
        val elapsedMillis = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
        val elapsedMinutes = elapsedMillis / 60_000L
        return when {
            elapsedMinutes < 1 -> "방금 전"
            elapsedMinutes < 60 -> "${elapsedMinutes}분 전"
            elapsedMinutes < 60 * 24 -> "${elapsedMinutes / 60}시간 전"
            else -> Instant.ofEpochMilli(createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN))
        }
    }

    private fun applyInsets(root: View, toolbar: MaterialToolbar, scrollView: ScrollView) {
        val toolbarTopPadding = toolbar.paddingTop
        val scrollLeftPadding = scrollView.paddingLeft
        val scrollRightPadding = scrollView.paddingRight
        val scrollBottomPadding = scrollView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val systemBarsInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbar.paddingLeft,
                toolbarTopPadding + systemBarsInsets.top,
                toolbar.paddingRight,
                toolbar.paddingBottom,
            )
            scrollView.setPadding(
                scrollLeftPadding + systemBarsInsets.left,
                scrollView.paddingTop,
                scrollRightPadding + systemBarsInsets.right,
                scrollBottomPadding + systemBarsInsets.bottom,
            )
            WindowInsetsCompat.CONSUMED
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

    private fun color(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        private const val PREFS_NAME = "famwall_prefs"
        private const val KEY_SELECTED_USER = "selected_user"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
