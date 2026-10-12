package com.luqman.decko

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.graphics.Typeface
import android.os.Build
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs

/**
 * Left pull-down: a large clock and date, now-playing media, then notifications grouped by app on glass
 * cards. Tap opens, swipe sideways dismisses, Clear all clears what can be cleared. Until the user allows
 * notification access, a card explains and asks.
 */
class NotificationCenter(private val activity: Activity, private val onClose: () -> Unit) : GlassPanels.Panel {
    private val density = activity.resources.displayMetrics.density
    private val ink = activity.getColor(R.color.glass_text)
    private val muted = activity.getColor(R.color.glass_text_muted)
    private val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(20)
        setPadding(p, dp(12), p, dp(32))
        // Empty glass between cards closes the panel, like tapping outside a sheet.
        setOnClickListener { onClose() }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val media = MediaCard(activity)
    private val changed: () -> Unit = ::rebuild

    override val view: View = scroll

    init {
        scroll.addView(content)
        content.addView(TextClock(activity).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 64f
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        content.addView(TextClock(activity).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, d MMMM"
            textSize = 17f
            setTextColor(muted)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(20) })
        content.addView(media.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content.addView(list)
    }

    override fun onShow() {
        NotificationHub.observe(changed)
        media.start()
        rebuild()
    }

    override fun onHide() {
        NotificationHub.unobserve(changed)
        media.stop()
    }

    override fun canScrollDown() = scroll.canScrollVertically(1)

    private fun dp(v: Int) = (v * density).toInt()

    private fun rebuild() {
        list.removeAllViews()
        if (!NotificationHub.isGranted(activity)) {
            list.addView(accessCard())
            return
        }
        val items = NotificationHub.items
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(activity.getString(R.string.notifications_title), 15f, muted, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (items.any { it.isClearable }) {
            header.addView(pill(activity.getString(R.string.clear_all)) { NotificationHub.dismissAll() })
        }
        list.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (items.isEmpty()) {
            list.addView(label(activity.getString(R.string.no_notifications), 16f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        items.groupBy { it.packageName }.forEach { (pkg, group) -> list.addView(groupCard(pkg, group), cardParams()) }
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }

    /** Before access: what the panel does and the button that leads to Android's permission screen. */
    private fun accessCard(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassDrawable(activity, 26f, sampleBackdrop = false)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        isClickable = true
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_cc_bell)
            setColorFilter(ink)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(label(activity.getString(R.string.notifications_access_title), 18f, ink, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(label(activity.getString(R.string.notifications_access_body), 15f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(Button(activity).apply {
            setText(R.string.notifications_access_button)
            isAllCaps = false
            textSize = 15f
            setTextColor(activity.getColor(R.color.controls_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(activity.getColor(R.color.glass_accent))
            }
            foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
            setPadding(dp(24), 0, dp(24), 0)
            stateListAnimator = null
            setOnClickListener {
                onClose()
                NotificationHub.requestAccess(activity)
            }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(14) })
    }

    /** One app: header with icon, name and newest time; then its notifications, newest first. */
    private fun groupCard(pkg: String, group: List<StatusBarNotification>): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(activity, 24f, sampleBackdrop = false)
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = runCatching { activity.packageManager.getApplicationIcon(pkg) }.getOrNull()
            ?: group.first().notification.smallIcon?.loadDrawable(activity)?.apply { setTint(ink) }
        head.addView(ImageView(activity).apply {
            setImageDrawable(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        val appName = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        head.addView(label(appName, 13f, muted, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        head.addView(label(ago(group.first().postTime), 12f, muted))
        card.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        for (sbn in group.take(MAX_PER_APP)) card.addView(item(sbn))
        if (group.size > MAX_PER_APP) {
            card.addView(label(activity.getString(R.string.more_notifications, group.size - MAX_PER_APP), 13f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        }
        return card
    }

    private fun item(sbn: StatusBarNotification): View {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(8))
            isClickable = true
            isFocusable = true
            foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
            contentDescription = listOf(title, body).filter { it.isNotEmpty() }.joinToString(". ")
        }
        if (title.isNotEmpty()) row.addView(label(title, 15f, ink, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (body.isNotEmpty()) row.addView(label(body, 14f, muted).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        row.setOnClickListener { open(sbn) }
        if (sbn.isClearable) swipeToDismiss(row) { NotificationHub.dismiss(sbn) }
        return row
    }

    /** Sends the notification's tap action; Android 14+ needs the launcher to lend its right to start activities. */
    private fun open(sbn: StatusBarNotification) {
        val intent = sbn.notification.contentIntent ?: return
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        } else null
        runCatching { intent.send(activity, 0, null, null, null, null, options) }
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) NotificationHub.dismiss(sbn)
        onClose()
    }

    /** Horizontal drag past a third of the width (or a quick flick) slides it out and dismisses. */
    @SuppressLint("ClickableViewAccessibility") // Taps still go through performClick; TalkBack users use Clear all.
    private fun swipeToDismiss(v: View, onDismiss: () -> Unit) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downTime = 0L
        var swiping = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downTime = e.eventTime; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!swiping && abs(dx) > slop) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.isPressed = false
                    }
                    if (swiping) {
                        v.translationX = dx
                        v.alpha = 1 - (abs(dx) / v.width).coerceAtMost(1f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                    val dx = e.rawX - downX
                    val fast = abs(dx) / ((e.eventTime - downTime).coerceAtLeast(1)) > 1.2f * density
                    if (e.actionMasked == MotionEvent.ACTION_UP && (abs(dx) > v.width / 3f || fast)) {
                        v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f).setDuration(180)
                            .withEndAction(onDismiss).start()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(200).start()
                    }
                    swiping = false
                    return@setOnTouchListener true
                }
            }
            swiping
        }
    }

    private fun ago(time: Long) = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun pill(s: String, onClick: () -> Unit) = TextView(activity).apply {
        text = s
        textSize = 13f
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GlassDrawable(activity, 16f, sampleBackdrop = false)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private companion object {
        const val MAX_PER_APP = 4
    }
}
