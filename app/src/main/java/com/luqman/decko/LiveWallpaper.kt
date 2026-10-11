package com.luqman.decko

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/** Helpers for this app's live wallpaper services (clock and video). */
object LiveWallpaper {
    /** True when [service] is the active live wallpaper on the home or lock screen. */
    fun isActive(ctx: Context, service: Class<*>): Boolean {
        val wm = WallpaperManager.getInstance(ctx)
        val me = ComponentName(ctx, service)
        val home = wm.wallpaperInfo?.component == me
        val lock = Build.VERSION.SDK_INT >= 34 && wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == me
        return home || lock
    }

    /** Opens the system preview for [service]; there the user applies it to home, lock, or both. */
    fun openPicker(activity: Activity, service: Class<*>) {
        val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(activity, service),
        )
        try {
            activity.startActivity(preview)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.lock_screen_manual, Toast.LENGTH_LONG).show()
            }
        }
    }
}
