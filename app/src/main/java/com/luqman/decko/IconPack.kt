package com.luqman.decko

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Built-in icon packs for Decko Home. Shape packs re-mask every app's adaptive icon;
 * THEMED repaints it as a single-color glyph in the current clock design's colors.
 */
enum class IconPack(val id: String, val title: Int) {
    SYSTEM("system", R.string.pack_system),
    CIRCLE("circle", R.string.pack_circle),
    SQUIRCLE("squircle", R.string.pack_squircle),
    ROUNDED("rounded", R.string.pack_rounded),
    TEARDROP("teardrop", R.string.pack_teardrop),
    THEMED("themed", R.string.pack_themed);

    /** Mask for a [size]-px icon; null for SYSTEM, which keeps each app's own shape. */
    fun shape(size: Float): Path? = when (this) {
        SYSTEM -> null
        CIRCLE -> Path().apply { addCircle(size / 2, size / 2, size / 2, Path.Direction.CW) }
        SQUIRCLE, THEMED -> squircle(size)
        ROUNDED -> Path().apply { addRoundRect(0f, 0f, size, size, size * 0.22f, size * 0.22f, Path.Direction.CW) }
        TEARDROP -> {
            val r = size / 2
            val tip = size * 0.12f
            // Corner radii clockwise from top-left; the bottom-right corner is the drop's point.
            Path().apply { addRoundRect(0f, 0f, size, size, floatArrayOf(r, r, r, r, tip, tip, r, r), Path.Direction.CW) }
        }
    }

    companion object {
        private const val SQUIRCLE_STEPS = 96

        fun byId(id: String?): IconPack? = entries.firstOrNull { it.id == id }

        /** Superellipse |x|⁴ + |y|⁴ = 1, the iOS/One UI style squircle. */
        private fun squircle(size: Float): Path {
            val r = size / 2
            return Path().apply {
                for (i in 0..SQUIRCLE_STEPS) {
                    val t = 2 * PI * i / SQUIRCLE_STEPS
                    val c = cos(t)
                    val s = sin(t)
                    val x = r + r * (sign(c) * sqrt(abs(c))).toFloat()
                    val y = r + r * (sign(s) * sqrt(abs(s))).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
}
