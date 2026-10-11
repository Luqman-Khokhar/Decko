package com.luqman.bigclock

/** Settings for the animated overlay drawn over the phone's status-bar strip. */
class HeaderConfig(
    val enabled: Boolean,
    val itemId: String?,
    /** Character height in dp. */
    val sizeDp: Int,
    /** Horizontal position (0–100) when the character stays put. */
    val xPercent: Int,
    val movement: Int,
    /** 1–10; walking speed. */
    val speed: Int,
    val effect: Int,
    val color: Int,
    /** 1–10. */
    val effectSpeed: Int,
    val trigger: Int,
    val hideFullscreen: Boolean,
) {
    companion object {
        const val MOVE_STAY = 0
        const val MOVE_WALK = 1
        const val MOVE_BOUNCE = 2

        const val EFFECT_NONE = 0
        const val EFFECT_GLOW = 1
        const val EFFECT_PULSE = 2
        const val EFFECT_SWEEP = 3

        const val TRIGGER_ALWAYS = 0
        const val TRIGGER_CHARGING = 1
        const val TRIGGER_NOTIFICATION = 2

        const val MIN_SIZE_DP = 12
        const val MAX_SIZE_DP = 40
    }
}
