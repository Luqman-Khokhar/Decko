package com.luqman.decko

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Called by the launcher once a pinned widget has actually been placed on the home screen. */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Toast.makeText(ctx, R.string.widget_added, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Mutable because the system adds the new widget's id to the intent. */
        fun callback(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PinResultReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
