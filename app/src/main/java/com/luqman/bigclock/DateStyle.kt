package com.luqman.bigclock

import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale

/** How a design writes the weekday and date: fonts, casing, spacing and wording. */
class DateStyle(
    weekdayFont: String,
    weekdayFace: Int,
    dateFont: String,
    dateFace: Int,
    val spacing: Float,
    val weekday: (Calendar) -> CharSequence,
    val date: (Calendar) -> CharSequence,
) {
    val weekdayTypeface: Typeface = Typeface.create(weekdayFont, weekdayFace)
    val dateTypeface: Typeface = Typeface.create(dateFont, dateFace)
}

object DateStyles {
    private val N = Typeface.NORMAL
    private val B = Typeface.BOLD
    private val I = Typeface.ITALIC

    private val plain = { n: Calendar -> ClockText.weekday(n) }
    private val caps = { n: Calendar -> ClockText.weekday(n).toString().uppercase(Locale.getDefault()) }
    private val full = { n: Calendar -> ClockText.date(n) }
    private val short = { n: Calendar -> ClockText.dateShort(n) }
    private val shortCaps = { n: Calendar -> ClockText.dateShort(n).toString().uppercase(Locale.getDefault()) }
    private val prompt = { n: Calendar -> "> " + ClockText.weekday(n).toString().lowercase(Locale.getDefault()) }
    private val iso = { n: Calendar -> ClockText.dateIso(n) }

    private val styles = mapOf(
        ClockTheme.CLASSIC to DateStyle("sans-serif-medium", N, "sans-serif", N, 0f, plain, full),
        ClockTheme.EASY to DateStyle("sans-serif-medium", N, "sans-serif", N, 0.08f, caps, full),
        ClockTheme.GIANT to DateStyle("sans-serif-black", N, "sans-serif-medium", N, 0f, plain, full),
        ClockTheme.CONTRAST to DateStyle("sans-serif-medium", N, "sans-serif-medium", N, 0.1f, caps, short),
        ClockTheme.NIGHT to DateStyle("sans-serif-light", N, "sans-serif-light", N, 0.06f, plain, full),
        ClockTheme.ROMAN to DateStyle("serif", B, "serif", I, 0f, plain, full),
        ClockTheme.D_CLASSIC to DateStyle("serif", B, "serif", I, 0.08f, caps, full),
        ClockTheme.D_MODERN to DateStyle("sans-serif-medium", N, "sans-serif-light", N, 0.2f, caps, short),
        ClockTheme.D_MINIMAL to DateStyle("sans-serif-light", N, "sans-serif-light", N, 0f, plain, short),
        ClockTheme.D_HACKER to DateStyle("monospace", B, "monospace", N, 0.02f, prompt, iso),
        ClockTheme.D_PUNK to DateStyle("sans-serif-condensed", B or I, "sans-serif-condensed", B or I, 0.06f, caps, shortCaps),
        ClockTheme.D_NEON to DateStyle("sans-serif-medium", N, "sans-serif-light", N, 0.2f, caps, short),
    )

    fun of(t: ClockTheme): DateStyle = styles.getValue(t)
}
