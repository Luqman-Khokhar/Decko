package com.luqman.decko

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the header overlay back after a reboot or an app update. */
class HeaderBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        HeaderOverlayService.sync(ctx)
    }
}
