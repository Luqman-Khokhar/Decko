package com.luqman.bigclock

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale

/** Day/date wording shared by the app screen and the wallpaper. */
object ClockText {
    fun weekday(now: Calendar): CharSequence = DateFormat.format("EEEE", now)

    /** Time of day in the user's 12h/24h setting. */
    fun time(ctx: Context, now: Calendar): CharSequence = DateFormat.getTimeFormat(ctx).format(now.time)

    fun date(now: Calendar): CharSequence =
        DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMMyyyy"), now)

    fun dateShort(now: Calendar): CharSequence =
        DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMyyyy"), now)

    fun dateIso(now: Calendar): CharSequence = DateFormat.format("yyyy-MM-dd", now)
}
