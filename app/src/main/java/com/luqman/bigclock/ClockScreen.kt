package com.luqman.bigclock

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.util.Calendar
import kotlin.random.Random

/** Full-screen clock + day/date text. Shared by the app and the screensaver. */
class ClockScreen(private val root: View) {
    private val content: View = root.findViewById(R.id.content)
    private val clock: AnalogClockView = root.findViewById(R.id.clock)
    private val time: TextView = root.findViewById(R.id.time)
    private val weekday: TextView = root.findViewById(R.id.weekday)
    private val date: TextView = root.findViewById(R.id.date)
    private var current = ClockTheme.CLASSIC
    private val handler = Handler(Looper.getMainLooper())

    private val minuteTick = object : Runnable {
        override fun run() {
            refreshText()
            shiftPixels()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    fun apply(theme: ClockTheme) {
        current = theme
        root.setBackgroundColor(theme.background)
        clock.clockTheme = theme
        for (tv in listOf(time, weekday, date)) tv.setTextColor(theme.text)
        val style = theme.dateStyle
        weekday.typeface = style.weekdayTypeface
        weekday.letterSpacing = style.spacing
        date.typeface = style.dateTypeface
        date.letterSpacing = style.spacing * 0.5f
        time.typeface = style.weekdayTypeface
    }

    fun start() {
        time.visibility = if (Prefs.digitalTime(root.context) && current.digital == null) View.VISIBLE else View.GONE
        handler.removeCallbacks(minuteTick)
        minuteTick.run()
    }

    fun stop() = handler.removeCallbacks(minuteTick)

    private fun refreshText() {
        val now = Calendar.getInstance()
        time.text = ClockText.time(root.context, now)
        weekday.text = current.dateStyle.weekday(now)
        date.text = current.dateStyle.date(now)
    }

    /** Nudges everything a few pixels each minute so a 24/7 screen doesn't burn in. */
    private fun shiftPixels() {
        val max = 6 * root.resources.displayMetrics.density
        content.translationX = Random.nextFloat() * 2 * max - max
        content.translationY = Random.nextFloat() * 2 * max - max
    }
}
