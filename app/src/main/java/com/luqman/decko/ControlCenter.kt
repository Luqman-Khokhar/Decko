package com.luqman.decko

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Right pull-down: glass tiles for connectivity, Do Not Disturb, flashlight and rotation; tall brightness
 * and volume sliders; then shortcuts. Android lets apps switch only some of these directly; the rest open
 * the system panel or setting for it, and the tile says which state it's in either way.
 */
class ControlCenter(private val activity: Activity, private val onClose: () -> Unit) : GlassPanels.Panel {
    private val density = activity.resources.displayMetrics.density
    private val ink = activity.getColor(R.color.glass_text)
    private val muted = activity.getColor(R.color.glass_text_muted)
    private val accent = activity.getColor(R.color.glass_accent)
    private val cr = activity.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val camera = activity.getSystemService(CameraManager::class.java)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val notifications = activity.getSystemService(NotificationManager::class.java)
    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var torchOn = false
    private val tiles = ArrayList<Tile>()
    private val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    private val media = MediaCard(activity)
    private lateinit var brightness: GlassSlider
    private lateinit var volume: GlassSlider

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                refresh()
            }
        }
    }

    override val view: View = scroll

    /** A tile reads its state fresh each time the panel shows or after it's tapped. */
    private inner class Tile(val icon: Int, val label: Int, val isOn: () -> Boolean?, val onTap: () -> Unit) {
        val bubble = ImageView(activity).apply { setImageResource(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val state = TextView(activity).apply { textSize = 12f; setTextColor(muted) }
        val root: View = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(activity, 24f, sampleBackdrop = false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
            addView(bubble, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    setText(label)
                    textSize = 14f
                    setTextColor(ink)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 2
                })
                addView(state)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            setOnClickListener {
                onTap()
                // Settings land a moment after the change; read again once they have.
                main.postDelayed(::refresh, 400)
            }
        }

        fun bind() {
            val on = isOn()
            val p = dp(9)
            bubble.setPadding(p, p, p, p)
            bubble.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on == true) accent else (muted and 0x00FFFFFF) or 0x33000000)
            }
            bubble.setColorFilter(if (on == true) activity.getColor(R.color.controls_text) else ink)
            // Color is never the only signal: the state is spelled out too.
            state.setText(when (on) { true -> R.string.state_on; false -> R.string.state_off; null -> R.string.state_open })
            root.contentDescription = activity.getString(label) + ", " + state.text
        }
    }

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setOnClickListener { onClose() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scroll.addView(content)
        content.addView(TextView(activity).apply {
            setText(R.string.control_center)
            textSize = 15f
            setTextColor(muted)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        content.addView(media.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        tiles += Tile(R.drawable.ic_cc_wifi, R.string.cc_internet, ::wifiOn) { openPanel(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS) }
        tiles += Tile(R.drawable.ic_cc_bluetooth, R.string.cc_bluetooth, { Settings.Global.getInt(cr, "bluetooth_on", 0) == 1 }) { openPanel(Settings.ACTION_BLUETOOTH_SETTINGS) }
        tiles += Tile(R.drawable.ic_cc_airplane, R.string.cc_airplane, { Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }) { openPanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
        tiles += Tile(R.drawable.ic_cc_moon, R.string.cc_dnd, { notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL }, ::toggleDnd)
        if (torchId != null) tiles += Tile(R.drawable.ic_cc_flashlight, R.string.cc_flashlight, { torchOn }, ::toggleTorch)
        tiles += Tile(R.drawable.ic_cc_rotate, R.string.cc_rotate, { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }, ::toggleRotate)
        val grid = GridLayout(activity).apply { columnCount = 2 }
        for (t in tiles) grid.addView(t.root, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
            width = 0
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
        content.addView(grid)

        brightness = GlassSlider(activity, activity.getDrawable(R.drawable.ic_cc_sun)?.mutate(), activity.getString(R.string.cc_brightness)).apply {
            canChange = {
                Settings.System.canWrite(activity).also { if (!it) openPanel(Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true) }
            }
            onChange = { v ->
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (MIN_BRIGHTNESS + v * (MAX_BRIGHTNESS - MIN_BRIGHTNESS)).toInt())
            }
        }
        volume = GlassSlider(activity, activity.getDrawable(R.drawable.ic_cc_volume)?.mutate(), activity.getString(R.string.cc_volume)).apply {
            onChange = { v ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0) }
            }
        }
        content.addView(LinearLayout(activity).apply {
            addView(brightness, LinearLayout.LayoutParams(0, dp(SLIDER_DP), 1f).apply { marginStart = dp(4); marginEnd = dp(8) })
            addView(volume, LinearLayout.LayoutParams(0, dp(SLIDER_DP), 1f).apply { marginStart = dp(8); marginEnd = dp(4) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val shortcuts = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        shortcut(shortcuts, R.drawable.ic_cc_camera, R.string.cc_camera) { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }
        shortcut(shortcuts, R.drawable.ic_cc_calculator, R.string.cc_calculator) { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR) }
        shortcut(shortcuts, R.drawable.ic_cc_clock, R.string.cc_alarms) { Intent(AlarmClock.ACTION_SHOW_ALARMS) }
        shortcut(shortcuts, R.drawable.ic_cc_settings, R.string.cc_settings) { Intent(Settings.ACTION_SETTINGS) }
        content.addView(shortcuts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    override fun onShow() {
        torchId?.let { runCatching { camera.registerTorchCallback(torchCallback, main) } }
        media.start()
        refresh()
    }

    override fun onHide() {
        runCatching { camera.unregisterTorchCallback(torchCallback) }
        media.stop()
        main.removeCallbacksAndMessages(null)
    }

    override fun canScrollDown() = scroll.canScrollVertically(1)

    private fun refresh() {
        tiles.forEach { it.bind() }
        brightness.value = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) - MIN_BRIGHTNESS) / (MAX_BRIGHTNESS - MIN_BRIGHTNESS).toFloat()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private fun wifiOn(): Boolean? = runCatching { activity.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull()

    private fun toggleTorch() {
        val id = torchId ?: return
        // Fails while another app holds the camera; the tile just stays as it was.
        runCatching { camera.setTorchMode(id, !torchOn) }
    }

    private fun toggleDnd() {
        if (!notifications.isNotificationPolicyAccessGranted) return openPanel(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        val on = notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    private fun toggleRotate() {
        if (!Settings.System.canWrite(activity)) return openPanel(Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true)
        val on = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
    }

    /** Leaves the panel for a system screen; [withPackage] points it at Decko (e.g. "Modify system settings"). */
    private fun openPanel(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).apply { if (withPackage) data = Uri.parse("package:" + activity.packageName) }
        onClose()
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun shortcut(row: LinearLayout, icon: Int, label: Int, intent: () -> Intent) {
        val size = dp(60)
        row.addView(ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(ink)
            val p = dp(17)
            setPadding(p, p, p, p)
            background = GlassDrawable(activity, 30f, sampleBackdrop = false)
            foreground = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run { getDrawable(0).also { recycle() } }
            contentDescription = activity.getString(label)
            isClickable = true
            isFocusable = true
            GlassStyle.pressFeedback(this)
            setOnClickListener {
                onClose()
                runCatching { activity.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(8); marginEnd = dp(8) })
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        const val SLIDER_DP = 180
        const val MIN_BRIGHTNESS = 1
        const val MAX_BRIGHTNESS = 255
    }
}
