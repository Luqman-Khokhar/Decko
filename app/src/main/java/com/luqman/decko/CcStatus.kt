package com.luqman.decko

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Top of the control center: large time and date on the left; on the right the phone's own status (signal,
 * Wi-Fi, battery, since the panel covers the status bar) above round edit and settings buttons.
 */
class CcStatus(private val kit: CcKit, onEdit: () -> Unit, onSettings: () -> Unit) {
    private val ui = kit.ui
    private val bars = BarsDrawable(kit.ink, ui.getColor(R.color.cc_track), ui.resources.displayMetrics.density)
    private val signal = ImageView(ui).apply { setImageDrawable(bars) }
    private val wifi = kit.icon(R.drawable.ic_cc_wifi, 0, null)
    private val batteryFill = ClipDrawable(ui.getDrawable(R.drawable.ic_cc_battery_fill)!!.mutate(), Gravity.START, ClipDrawable.HORIZONTAL)
    private val battery = ImageView(ui).apply {
        setImageDrawable(LayerDrawable(arrayOf(ui.getDrawable(R.drawable.ic_cc_battery_frame)!!.mutate().apply { setTint(kit.ink) }, batteryFill)))
    }
    private val percent = kit.text(13f, medium = true)
    private val icons = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(signal, LinearLayout.LayoutParams(kit.dp(18), kit.dp(14)))
        addView(wifi, LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)).apply { marginStart = kit.dp(8) })
        addView(battery, LinearLayout.LayoutParams(kit.dp(26), kit.dp(26)).apply { marginStart = kit.dp(8) })
        addView(percent, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(4) })
    }
    private var batteryText = ""

    val view: View = LinearLayout(ui).apply {
        addView(kit.column(
            TextClock(ui).apply {
                format12Hour = "h:mm"
                format24Hour = "H:mm"
                textSize = 46f
                setTextColor(kit.ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = -0.02f
                includeFontPadding = false
            },
            TextClock(ui).apply {
                format12Hour = "EEE, d MMM"
                format24Hour = "EEE, d MMM"
                textSize = 17f
                setTextColor(kit.muted)
            },
        ), LinearLayout.LayoutParams(0, -2, 1f))
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(icons)
            addView(LinearLayout(ui).apply {
                addView(button(R.drawable.ic_cc_edit, R.string.cc_edit, onEdit), LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)))
                addView(button(R.drawable.ic_cc_gear, R.string.cc_settings, onSettings), LinearLayout.LayoutParams(kit.dp(46), kit.dp(46)).apply { marginStart = kit.dp(12) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(14) })
        })
    }

    fun refresh() {
        val tm = ui.getSystemService(TelephonyManager::class.java)
        val level = if (Build.VERSION.SDK_INT >= 28 && tm?.simState == TelephonyManager.SIM_STATE_READY) runCatching { tm.signalStrength?.level }.getOrNull() else null
        signal.visibility = if (level == null) View.GONE else View.VISIBLE
        bars.level = level ?: 0
        val cm = ui.getSystemService(ConnectivityManager::class.java)
        val onWifi = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }.getOrDefault(false)
        wifi.visibility = if (onWifi) View.VISIBLE else View.GONE
        icons.contentDescription = listOfNotNull(
            level?.let { ui.getString(R.string.cc_signal_status, it) },
            ui.getString(R.string.cc_wifi_status).takeIf { onWifi },
            batteryText,
        ).joinToString(", ")
    }

    fun bindBattery(pct: Int, charging: Boolean) {
        percent.text = ui.getString(R.string.cc_battery_line, pct)
        batteryFill.level = pct * 100
        batteryFill.setTint(if (charging) ui.getColor(R.color.cc_charge) else kit.ink)
        batteryText = ui.getString(R.string.cc_battery_status, pct)
        refresh()
    }

    private fun button(icon: Int, label: Int, onClick: () -> Unit) = kit.icon(icon, 12).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = ui.getString(label)
        kit.pressable(this, borderless = true, onClick = onClick)
    }

    /** Four rising bars, lit up to [getLevel] (0..4). */
    private class BarsDrawable(private val on: Int, private val off: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val gap = 2 * density
            val w = (b.width() - gap * 3) / 4
            for (i in 0 until 4) {
                val left = b.left + i * (w + gap)
                r.set(left, b.bottom - b.height() * (i + 2) / 5f, left + w, b.bottom.toFloat())
                paint.color = if (i < level) on else off
                canvas.drawRoundRect(r, w / 3, w / 3, paint)
            }
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Required override.")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
