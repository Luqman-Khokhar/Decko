package com.luqman.bigclock

enum class Numerals { ARABIC, ROMAN, QUARTERS }

/**
 * Clock designs. Colors are duplicated in tools/gen_widget_res.py, which generates the
 * widget hand drawables and layouts — change both together, then rerun the script.
 */
enum class ClockTheme(
    val id: String,
    val title: Int,
    val widgetLayout: Int,
    val numerals: Numerals,
    val minuteTicks: Boolean,
    val seconds: Boolean,
    background: Long,
    face: Long,
    rim: Long,
    marks: Long,
    hourHand: Long,
    minuteHand: Long,
    secondHand: Long,
    text: Long,
    val digital: DigitalStyle? = null,
) {
    CLASSIC(
        "classic", R.string.theme_classic, R.layout.widget_clock_classic, Numerals.ARABIC, true, true,
        0xFFEDEBE6, 0xFFFAF9F6, 0xFF2B2B2B, 0xFF1F1F1F, 0xFF1F1F1F, 0xFF1F1F1F, 0xFFC8452C, 0xFF1F1F1F,
    ),
    EASY(
        "easy", R.string.theme_easy, R.layout.widget_clock_easy, Numerals.ARABIC, true, false,
        0xFFE8EEF3, 0xFFFBFCFD, 0xFF274B6D, 0xFF274B6D, 0xFF274B6D, 0xFFB8452E, 0xFFB8452E, 0xFF1E3A56,
    ),
    GIANT(
        "giant", R.string.theme_giant, R.layout.widget_clock_giant, Numerals.QUARTERS, false, true,
        0xFFF3EDE0, 0xFFFBF7EC, 0xFF1F2A44, 0xFF1F2A44, 0xFF1F2A44, 0xFF1F2A44, 0xFFC2570C, 0xFF1F2A44,
    ),
    CONTRAST(
        "contrast", R.string.theme_contrast, R.layout.widget_clock_contrast, Numerals.ARABIC, true, true,
        0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFF5D547, 0xFFFFFFFF, 0xFFFF6B5B, 0xFFFFFFFF,
    ),
    NIGHT(
        "night", R.string.theme_night, R.layout.widget_clock_night, Numerals.ARABIC, false, false,
        0xFF000000, 0xFF0B0B0A, 0xFF7A5A1F, 0xFFE8A93A, 0xFFE8A93A, 0xFFF0BE5C, 0xFFF0BE5C, 0xFFE8A93A,
    ),
    ROMAN(
        "roman", R.string.theme_roman, R.layout.widget_clock_roman, Numerals.ROMAN, true, true,
        0xFFEAE4DA, 0xFFF8F4EA, 0xFF5A4636, 0xFF3A2D24, 0xFF3A2D24, 0xFF3A2D24, 0xFF9C2B2B, 0xFF3A2D24,
    ),

    // Digital designs reuse the color slots: face = card, rim = border, marks = decoration,
    // hourHand = digits, minuteHand = colon, secondHand = accent, text = date text.
    D_CLASSIC(
        "d_classic", R.string.theme_d_classic, R.layout.widget_clock_d_classic, Numerals.ARABIC, false, false,
        0xFFE9E4D8, 0xFFFAF6EB, 0xFF3A2D24, 0xFF8A7A66, 0xFF2B2118, 0xFF2B2118, 0xFF9C2B2B, 0xFF3A2D24,
        DigitalStyle.CLASSIC,
    ),
    D_MODERN(
        "d_modern", R.string.theme_d_modern, R.layout.widget_clock_d_modern, Numerals.ARABIC, false, false,
        0xFF0E1013, 0xFF1B1F25, 0xFF1B1F25, 0xFF1B1F25, 0xFFF3F5F7, 0xFF8B95A3, 0xFF6C9BFF, 0xFFC9D1DB,
        DigitalStyle.MODERN,
    ),
    D_MINIMAL(
        "d_minimal", R.string.theme_d_minimal, R.layout.widget_clock_d_minimal, Numerals.ARABIC, false, false,
        0xFFFAFAF8, 0xFFFAFAF8, 0xFFFAFAF8, 0xFFFAFAF8, 0xFF161616, 0xFF86847E, 0xFF161616, 0xFF5C5B57,
        DigitalStyle.MINIMAL,
    ),
    D_HACKER(
        "d_hacker", R.string.theme_d_hacker, R.layout.widget_clock_d_hacker, Numerals.ARABIC, false, false,
        0xFF000000, 0xFF050A06, 0xFF1F7A3A, 0xFF1F7A3A, 0xFF39FF6A, 0xFF39FF6A, 0xFF39FF6A, 0xFF39FF6A,
        DigitalStyle.HACKER,
    ),
    D_PUNK(
        "d_punk", R.string.theme_d_punk, R.layout.widget_clock_d_punk, Numerals.ARABIC, false, false,
        0xFF0A0A0A, 0xFF111111, 0xFFFF2E93, 0xFFE6FF00, 0xFFFF2E93, 0xFFFFFFFF, 0xFFE6FF00, 0xFFF5F5F5,
        DigitalStyle.PUNK,
    ),
    D_NEON(
        "d_neon", R.string.theme_d_neon, R.layout.widget_clock_d_neon, Numerals.ARABIC, false, false,
        0xFF080414, 0xFF12082A, 0xFFFF3DDC, 0xFFFF3DDC, 0xFF4DF3FF, 0xFFFF3DDC, 0xFFFF3DDC, 0xFFBFEFFF,
        DigitalStyle.NEON,
    );

    val background = background.toInt()
    val face = face.toInt()
    val rim = rim.toInt()
    val marks = marks.toInt()
    val hourHand = hourHand.toInt()
    val minuteHand = minuteHand.toInt()
    val secondHand = secondHand.toInt()
    val text = text.toInt()

    val dateStyle get() = DateStyles.of(this)

    /** Face height / width: digital cards are wide, analog dials are square. */
    val aspect get() = if (digital != null) DigitalRenderer.ASPECT else 1f

    /** Center cap sits on top of the last hand drawn. */
    val cap get() = if (seconds) secondHand else minuteHand

    companion object {
        fun byId(id: String?): ClockTheme? = entries.firstOrNull { it.id == id }
    }
}
