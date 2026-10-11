package com.luqman.decko

import android.service.dreams.DreamService

/** Screensaver: Settings → Display → Screen saver → Decko, "while charging". */
class ClockDreamService : DreamService() {
    private var screen: ClockScreen? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val theme = Prefs.appTheme(this)
        isInteractive = false
        isFullscreen = true
        isScreenBright = theme != ClockTheme.NIGHT
        setContentView(R.layout.activity_main)
        screen = ClockScreen(findViewById(R.id.root)).also { it.apply(theme) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        screen?.start()
    }

    override fun onDreamingStopped() {
        screen?.stop()
        super.onDreamingStopped()
    }
}
