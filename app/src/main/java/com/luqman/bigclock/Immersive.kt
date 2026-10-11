package com.luqman.bigclock

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

/** Full-screen helpers shared by the preview screens. */
object Immersive {
    fun hideSystemBars(window: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /** Pads [root] away from display cutouts and keeps [controls] out of the bottom gesture strip, which swallows taps. */
    fun keepClear(root: View, controls: View) {
        if (Build.VERSION.SDK_INT < 28) return
        val controlsPad = controls.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val cut = insets.displayCutout
            v.setPadding(cut?.safeInsetLeft ?: 0, cut?.safeInsetTop ?: 0, cut?.safeInsetRight ?: 0, cut?.safeInsetBottom ?: 0)
            if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                val gestures = insets.mandatorySystemGestureInsets.bottom
                controls.setPadding(controls.paddingLeft, controls.paddingTop, controls.paddingRight, controlsPad + gestures)
            }
            insets
        }
    }
}
