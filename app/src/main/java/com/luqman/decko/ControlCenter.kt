package com.luqman.decko

import android.content.Context
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Right pull-down, on dark glass whatever Glass's mode: time and phone status, then Internet and Bluetooth
 * beside now playing; quick toggles with tall brightness and volume sliders; a row of connection toggles;
 * gaming and focus modes; shortcuts; weather and battery. Android lets apps switch only some of these
 * directly; the rest open the system panel or setting for it, and the tile says which state it's in either way.
 */
class ControlCenter(private val context: Context, private val onClose: () -> Unit) : GlassPanels.Panel {
    private val kit = CcKit(context)
    private val ui = kit.ui
    private val cr = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val camera = context.getSystemService(CameraManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val nfc: NfcAdapter? = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var torchOn = false
    private val tiles = ArrayList<CcKit.Tile>()
    private val scroll = ScrollView(ui).apply { isVerticalScrollBarEnabled = false }
    private val status = CcStatus(kit, onEdit = { launch(Intent(context, HomeSettingsActivity::class.java)) }, onSettings = { openPanel(Settings.ACTION_SETTINGS) })
    private val media = MediaCard(ui, kit, onLeave = onClose)
    private val weather = WeatherCard(kit, ::launch)
    private val device = DeviceCard(kit) { openPanel(Intent.ACTION_POWER_USAGE_SUMMARY) }
    private val gamingToggle = kit.Toggle()
    private lateinit var gaming: View
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

    private val batteryChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = bindBattery(intent)
    }

    override val view: View = scroll

    override val sheet: Drawable = GlassDrawable(ui, 0f, sheet = true)

    init {
        val content = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(8), kit.dp(8), kit.dp(8), kit.dp(24))
            setOnClickListener { onClose() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scroll.addView(content)
        val frame = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(ui, 40f, sampleBackdrop = false)
            setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(12))
            // Gaps inside the frame are glass too: tapping them doesn't close the panel.
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(frame)
        frame.addView(handle(4), LinearLayout.LayoutParams(kit.dp(36), kit.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = kit.dp(12) })
        frame.addView(status.view, LinearLayout.LayoutParams(-1, -2).apply { marginStart = kit.dp(8); marginEnd = kit.dp(6) })
        frame.addView(header(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(14); bottomMargin = kit.dp(16) })

        // Internet and Bluetooth share a card, beside now playing.
        val internet = kit.pill(R.drawable.ic_cc_wifi, R.string.cc_internet, kit.blue, ::online, { ui.getString(if (it == true) R.string.cc_connected else R.string.cc_not_connected) }) {
            openPanel(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS)
        }
        val bluetooth = kit.pill(R.drawable.ic_cc_bluetooth, R.string.cc_bluetooth, kit.indigo, { Settings.Global.getInt(cr, "bluetooth_on", 0) == 1 }, kit::onOff) { openPanel(Settings.ACTION_BLUETOOTH_SETTINGS) }
        tiles += internet
        tiles += bluetooth
        val links = kit.column(internet.root).apply {
            background = kit.pane(28f)
            setPadding(kit.dp(7), kit.dp(7), kit.dp(7), kit.dp(7))
            addView(bluetooth.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(7) })
        }
        frame.addView(row(10, links to 1.1f, media.view to 1f))

        // Quick toggles, then the sliders.
        val quick = ArrayList<Pair<View, Float>>()
        fun square(t: CcKit.Tile) { tiles += t; quick += t.root to 1f }
        square(kit.square(R.drawable.ic_cc_airplane, R.string.cc_airplane, { Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }) { openPanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS) })
        square(kit.square(R.drawable.ic_cc_moon, R.string.cc_dnd, { notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL }, ::toggleDnd))
        if (torchId != null) square(kit.square(R.drawable.ic_cc_flashlight, R.string.cc_flashlight, { torchOn }, ::toggleTorch))
        square(kit.square(R.drawable.ic_cc_rotate, R.string.cc_rotate, { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }, ::toggleRotate))
        val warm = intArrayOf(ui.getColor(R.color.cc_fill_bright), ui.getColor(R.color.cc_fill_warm))
        val cool = intArrayOf(ui.getColor(R.color.cc_fill_cool), ui.getColor(R.color.cc_fill_lavender))
        brightness = GlassSlider(ui, ui.getDrawable(R.drawable.ic_cc_sun)?.mutate(), ui.getString(R.string.cc_brightness), TRACK_INSET_DP, warm).apply {
            minimumHeight = kit.dp(SLIDER_DP)
            canChange = {
                Settings.System.canWrite(context).also { if (!it) askWriteSettings() }
            }
            onChange = { v ->
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (MIN_BRIGHTNESS + v * (MAX_BRIGHTNESS - MIN_BRIGHTNESS)).toInt())
            }
        }
        volume = GlassSlider(ui, ui.getDrawable(R.drawable.ic_cc_volume)?.mutate(), ui.getString(R.string.cc_volume), TRACK_INSET_DP, cool).apply {
            minimumHeight = kit.dp(SLIDER_DP)
            onChange = { v ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0) }
            }
        }
        quick += brightness to 1.1f
        quick += volume to 1.1f
        frame.addView(row(6, *quick.toTypedArray()), gap())

        // Connection toggles share one wide card.
        val connections = ArrayList<Pair<View, Float>>()
        fun round(t: CcKit.Tile) { tiles += t; connections += t.root to 1f }
        round(kit.round(R.drawable.ic_cc_location, R.string.cc_location, ::locationOn) { openPanel(Settings.ACTION_LOCATION_SOURCE_SETTINGS) })
        round(kit.round(R.drawable.ic_cc_wifi, R.string.cc_hotspot, ::hotspotOn) { openPanel(ACTION_TETHER_SETTINGS) })
        round(kit.round(R.drawable.ic_cc_leaf, R.string.cc_battery_saver, { power.isPowerSaveMode }) { openPanel(Settings.ACTION_BATTERY_SAVER_SETTINGS) })
        round(kit.round(R.drawable.ic_cc_cast, R.string.cc_screen_cast, ::casting) { openPanel(Settings.ACTION_CAST_SETTINGS) })
        round(kit.round(R.drawable.ic_cc_data, R.string.cc_mobile_data, ::mobileDataOn) {
            openPanel(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS)
        })
        frame.addView(row(0, *connections.toTypedArray()).apply {
            background = kit.pane(28f)
            setPadding(kit.dp(2), kit.dp(14), kit.dp(2), kit.dp(12))
        }, gap())

        gaming = modeCard(R.drawable.ic_cc_gamepad, R.string.cc_gaming, R.string.cc_gaming_sub, R.color.cc_glow_violet, gamingToggle, ::toggleGaming)
        val focus = modeCard(R.drawable.ic_cc_moon, R.string.cc_focus, R.string.cc_focus_sub, 0, kit.chevron()) { openPanel(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) }
        frame.addView(row(10, gaming to 1f, focus to 1f), gap())

        val shortcuts = ArrayList<Pair<View, Float>>()
        fun shortcut(icon: Int, label: Int, onTap: () -> Unit) { shortcuts += kit.shortcut(icon, label, onTap) to 1f }
        shortcut(R.drawable.ic_cc_camera, R.string.cc_camera) { launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        shortcut(R.drawable.ic_cc_clock, R.string.cc_alarms) { launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        if (Build.VERSION.SDK_INT >= 28) shortcut(R.drawable.ic_cc_screenshot, R.string.cc_screenshot, ::screenshot)
        shortcut(R.drawable.ic_cc_calculator, R.string.cc_calculator) { launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)) }
        if (nfc != null) shortcut(R.drawable.ic_cc_nfc, R.string.cc_nfc) { openPanel(Settings.ACTION_NFC_SETTINGS) }
        // Phone cameras read QR codes in their normal photo mode; there's no system-wide scanner intent.
        shortcut(R.drawable.ic_cc_qr, R.string.cc_qr) { launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        frame.addView(row(6, *shortcuts.toTypedArray()), gap())

        frame.addView(row(10, weather.view to 1f, device.view to 1f), gap())
        frame.addView(handle(5), LinearLayout.LayoutParams(kit.dp(120), kit.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(16) })
    }

    override fun onShow() {
        torchId?.let { runCatching { camera.registerTorchCallback(torchCallback, main) } }
        context.registerReceiver(batteryChanged, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::bindBattery)
        media.start()
        weather.refresh()
        refresh()
    }

    override fun onHide() {
        runCatching { camera.unregisterTorchCallback(torchCallback) }
        runCatching { context.unregisterReceiver(batteryChanged) }
        media.stop()
        main.removeCallbacksAndMessages(null)
    }

    override fun canScrollDown() = scroll.canScrollVertically(1)

    private fun refresh() {
        tiles.forEach { it.bind() }
        status.refresh()
        val gamingOn = notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        gamingToggle.checked = gamingOn
        gaming.contentDescription = ui.getString(R.string.cc_gaming) + ", " + kit.onOff(gamingOn)
        brightness.value = (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) - MIN_BRIGHTNESS) / (MAX_BRIGHTNESS - MIN_BRIGHTNESS).toFloat()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volume.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private fun bindBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100 / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        status.bindBattery(pct, charging)
        device.bind(pct, charging)
    }

    /** Round logo beside the panel's name and what it's for. */
    private fun header() = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL
        val glow = intArrayOf(ui.getColor(R.color.cc_glow_violet), ui.getColor(R.color.cc_bubble))
        addView(kit.icon(R.drawable.ic_cc_logo, 14, kit.bubble(glow)).apply { clearColorFilter() }, LinearLayout.LayoutParams(kit.dp(58), kit.dp(58)))
        addView(kit.column(
            kit.text(24f).apply {
                setText(R.string.control_center)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            },
            kit.text(14f, kit.muted).apply { setText(R.string.cc_subtitle) },
        ), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(14) })
    }

    /** Gaming and Focus: hue bubble, name over what it does, and a switch or chevron at the end. */
    private fun modeCard(icon: Int, title: Int, sub: Int, glow: Int, end: View, onTap: () -> Unit) = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = kit.pane(26f, glow)
        setPadding(kit.dp(10), kit.dp(12), kit.dp(10), kit.dp(12))
        addView(kit.icon(icon, 10, kit.bubble(if (glow != 0) kit.violet else null)), LinearLayout.LayoutParams(kit.dp(42), kit.dp(42)))
        addView(kit.column(
            kit.fitText(15, 11, medium = true).apply { gravity = Gravity.START; setText(title) },
            kit.text(11f, kit.muted, lines = 2).apply { setText(sub) },
        ), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(10); marginEnd = kit.dp(4) })
        val chevron = end !is CcKit.Toggle
        addView(end, if (chevron) LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)) else LinearLayout.LayoutParams(kit.dp(38), kit.dp(22)))
        contentDescription = ui.getString(title)
        kit.pressable(this, onClick = onTap)
    }

    private fun row(gapDp: Int, vararg cells: Pair<View, Float>) = LinearLayout(ui).apply {
        isBaselineAligned = false
        cells.forEachIndexed { i, (v, w) -> addView(v, LinearLayout.LayoutParams(0, -1, w).apply { if (i > 0) marginStart = kit.dp(gapDp) }) }
    }

    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) }

    private fun handle(h: Int) = View(ui).apply {
        background = GradientDrawable().apply { cornerRadius = kit.dp(h).toFloat(); setColor(ui.getColor(R.color.cc_handle)) }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun online(): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrNull()

    private fun locationOn(): Boolean? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        @Suppress("DEPRECATION") // LOCATION_MODE is the only reading before API 28.
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrNull()

    /** Not public API; phones that hide it get "Open settings" instead of a guess. */
    private fun hotspotOn(): Boolean? = runCatching {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
    }.getOrNull()

    /** Casting shows up as a presentation display while it's on. */
    private fun casting(): Boolean = context.getSystemService(DisplayManager::class.java).getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty()

    private fun mobileDataOn(): Boolean? = runCatching { Settings.Global.getInt(cr, "mobile_data") == 1 }.getOrNull()

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

    /** Gaming mode is Do Not Disturb at "alarms only": calls, messages and alerts stay quiet while playing. */
    private fun toggleGaming() {
        if (!notifications.isNotificationPolicyAccessGranted) return openPanel(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        val on = notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_ALARMS)
        main.postDelayed(::refresh, 400)
    }

    private fun toggleRotate() {
        if (!Settings.System.canWrite(context)) return askWriteSettings()
        val on = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
    }

    /** Through the Glass sheets service, which can ask Android for a screenshot; without it, where to turn it on. */
    private fun screenshot() {
        if (GlassShadeService.screenshot()) onClose() else openPanel(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /** Explains "Modify system settings" before sending the user to Decko's switch for it. */
    private fun askWriteSettings() {
        AlertDialog.Builder(context)
            .setTitle(R.string.cc_write_settings_title)
            .setMessage(R.string.cc_write_settings_body)
            .setPositiveButton(R.string.cc_write_settings_allow) { _, _ -> openPanel(Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                // Over other apps there's no activity window to attach to; the shade's accessibility window type works.
                if (context !is Activity) window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            }
            .show()
    }

    /** Leaves the panel for a system screen; [withPackage] points it at Decko (e.g. "Modify system settings"). */
    private fun openPanel(action: String, withPackage: Boolean = false) {
        // New task: over other apps the panels live in a service's window, not an activity.
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (withPackage) data = Uri.parse("package:" + context.packageName) }
        onClose()
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Leaves the panel for an app; nothing happens if the phone has none for [intent]. */
    private fun launch(intent: Intent) {
        onClose()
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        const val SLIDER_DP = 128
        const val TRACK_INSET_DP = 14f
        const val MIN_BRIGHTNESS = 1
        const val MAX_BRIGHTNESS = 255
        /** Hidden in the SDK but handled by Settings on most phones; a miss falls back to Settings' main screen. */
        const val ACTION_TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"
    }
}
