package com.luqman.decko

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Android sends "add this widget to the home screen" requests (e.g. Decko's own Add clock button) to the
 * default launcher. When that's Decko Home, this asks the user, binds the widget and hands it to the home screen.
 */
class PinConfirmActivity : Activity() {
    private var request: LauncherApps.PinItemRequest? = null
    private var widgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps.getPinItemRequest(intent)
        this.request = request
        // Shortcut pins aren't supported yet; leaving the request unanswered tells the app it wasn't added.
        if (request == null || !request.isValid || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET) {
            finish()
            return
        }
        val info = request.getAppWidgetProviderInfo(this) ?: return finish()
        val density = resources.displayMetrics.densityDpi
        val preview = ImageView(this).apply {
            setImageDrawable(info.loadPreviewImage(this@PinConfirmActivity, density) ?: info.loadIcon(this@PinConfirmActivity, density))
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            adjustViewBounds = true
            maxHeight = (220 * resources.displayMetrics.density).toInt()
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(preview, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_widget_title))
            .setMessage(info.loadLabel(packageManager))
            .setView(body)
            .setPositiveButton(R.string.add_widget_confirm) { _, _ -> bind(info) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** The launcher binds the widget itself (asking once for permission), and only then accepts the request. */
    private fun bind(info: AppWidgetProviderInfo) {
        widgetId = Widgets.host(this).allocateAppWidgetId()
        if (Widgets.manager(this).bindAppWidgetIdIfAllowed(widgetId, info.profile, info.provider, null)) return accept()
        startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), REQ_BIND)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BIND) return
        if (resultCode == RESULT_OK) accept() else {
            Widgets.host(this).deleteAppWidgetId(widgetId)
            finish()
        }
    }

    private fun accept() {
        val id = widgetId
        if (request?.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }) == true) {
            // The home screen owns the layout; it places the bound widget on the page in view.
            startActivity(Intent(this, LauncherActivity::class.java)
                .setAction(LauncherActivity.ACTION_PLACE_WIDGET)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Widgets.host(this).deleteAppWidgetId(id)
        }
        finish()
    }

    private companion object {
        const val REQ_BIND = 1
    }
}
