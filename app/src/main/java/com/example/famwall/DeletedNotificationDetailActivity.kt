package com.example.famwall

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
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
import com.google.android.material.card.MaterialCardView

class DeletedNotificationDetailActivity : AppCompatActivity() {
    private lateinit var notificationRepository: ScheduleNotificationRepository
    private lateinit var activeUserName: String
    private var notification: ScheduleNotification? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KoreanLocaleContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        val preferences: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        activeUserName = preferences.getString(KEY_SELECTED_USER, getString(R.string.user_choi))
            ?: getString(R.string.user_choi)
        notificationRepository = ScheduleNotificationRepository(this, activeUserName)
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        notification = notificationRepository.getNotificationById(notificationId)
        notificationId?.let(notificationRepository::markAsRead)

        setContentView(createPage())
    }

    override fun onDestroy() {
        if (::notificationRepository.isInitialized) {
            notificationRepository.close()
        }
        super.onDestroy()
    }

    private fun createPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.dark_background))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "삭제된 일정"
            subtitle = notification?.dateSummary() ?: "알림 정보 없음"
            setTitleTextColor(color(R.color.text_primary))
            setSubtitleTextColor(getUserAccentColor(activeUserName))
            setNavigationIcon(R.drawable.ic_chevron_left)
            navigationContentDescription = "뒤로"
            setNavigationOnClickListener { finish() }
            setPadding(dp(6), dp(10), dp(18), dp(10))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))

        val scrollView = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(22))
        }
        scrollView.addView(content)
        root.addView(scrollView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        content.addView(createDeletedSnapshotCard())
        applyInsets(root, toolbar, scrollView)
        return root
    }

    private fun createDeletedSnapshotCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.card_surface))
            strokeColor = color(R.color.notification_unread_dot)
            strokeWidth = dp(1)
            radius = dp(18).toFloat()
            useCompatPadding = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val currentNotification = notification
        if (currentNotification == null) {
            content.addView(TextView(this).apply {
                text = "알림 정보를 찾을 수 없어요."
                setTextAppearance(R.style.TextAppearance_FamWall_Body)
            })
            card.addView(content)
            return card
        }

        content.addView(TextView(this).apply {
            text = "이 일정은 삭제되었습니다."
            setTextAppearance(R.style.TextAppearance_FamWall_CardTitle)
            setTextColor(color(R.color.notification_unread_dot))
        })
        content.addView(TextView(this).apply {
            text = "삭제 전 일정 내용"
            setTextAppearance(R.style.TextAppearance_FamWall_SectionTitle)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(18), 0, 0) })

        addDetailLine(content, "일정명", currentNotification.displayTitle())
        addDetailLine(content, "날짜", currentNotification.dateSummary())
        addDetailLine(content, "카테고리", currentNotification.categorySummary())
        addDetailLine(content, "등록자", currentNotification.actorUserName)
        addDetailLine(content, "내용", currentNotification.scheduleContent.trim().ifEmpty { "내용 없음" })

        card.addView(content)
        return card
    }

    private fun addDetailLine(container: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.TOP
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(TextView(this).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
            setTextColor(color(R.color.text_muted))
        }, LinearLayout.LayoutParams(dp(74), WRAP))
        row.addView(TextView(this).apply {
            text = value
            setTextAppearance(R.style.TextAppearance_FamWall_Body)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        container.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })
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
