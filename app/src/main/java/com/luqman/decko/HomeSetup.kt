package com.luqman.decko

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Making Decko Home the default home app. */
object HomeSetup {
    fun isDefaultHome(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }?.let { return it.isRoleHeld(RoleManager.ROLE_HOME) }
        }
        // Needs the HOME <queries> entry: without it the current launcher is invisible and this always resolves to us.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    }

    /** Android 10+ asks with the system role dialog; [requestCode] comes back to the activity, which offers [openSettings] after a refusal. */
    fun request(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode)
                return
            }
        }
        openSettings(activity)
    }

    /** Some OEM builds (XOS, MIUI) refuse the role dialog silently; their Default apps screen still works. */
    fun openSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
