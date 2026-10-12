package com.luqman.decko

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One launchable activity, in the personal or a work profile. */
class AppEntry(val info: LauncherActivityInfo, val label: String, val key: String) {
    val component: ComponentName get() = info.componentName
    val user: UserHandle get() = info.user
    val isWork get() = user != Process.myUserHandle()
    val isSystem get() = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
}

/** Every app the home screen can launch, across profiles, sorted by label for the user's locale. */
object Apps {
    /** Kept apart from [Library.io] so a long video import can't stall the app drawer. */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Blocking; call off the main thread. */
    fun load(ctx: Context): List<AppEntry> {
        val launcherApps = ctx.getSystemService(LauncherApps::class.java)
        val users = ctx.getSystemService(UserManager::class.java)
        val collator = Collator.getInstance()
        return users.userProfiles.flatMap { user ->
            // The serial survives reboots, unlike the UserHandle id, so per-app prefs stay attached.
            val serial = users.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user).map {
                AppEntry(it, it.label.toString().trim(), "${it.componentName.flattenToShortString()}#$serial")
            }
        }.sortedWith(compareBy(collator) { it.label })
    }
}
