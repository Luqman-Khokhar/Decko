package com.luqman.decko

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Control center card: local weather from Open-Meteo (no account or key) at the phone's approximate
 * location, with the place name from Android's geocoder. Kept for [STALE_MS] so opening the panel shows
 * it at once; without location access the card says so and taps through to Decko's permissions.
 */
class WeatherCard(private val kit: CcKit, private val onOpen: (Intent) -> Unit) {
    private val ui = kit.ui
    private val main = Handler(Looper.getMainLooper())
    private val icon = kit.icon(R.drawable.ic_wx_partly, 11).apply { clearColorFilter() }
    private val place = kit.text(15f, medium = true)
    private val condition = kit.text(12f, kit.muted, lines = 2)
    private val temp = kit.text(26f, medium = true)
    private val range = kit.text(11f, kit.muted)
    private val reading = LinearLayout(ui).apply {
        gravity = Gravity.BOTTOM
        addView(temp)
        addView(range, LinearLayout.LayoutParams(-2, -2).apply { marginStart = kit.dp(8); bottomMargin = kit.dp(5) })
    }
    private var state = State.LOADING
    private var shown: Weather? = null
    private var busy = false

    private enum class State { LOADING, NEED_LOCATION, FAILED, READY }

    private data class Weather(val time: Long, val place: String, val temp: Int, val high: Int, val low: Int, val code: Int)

    val view: View = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = kit.pane(26f)
        setPadding(kit.dp(12), kit.dp(14), kit.dp(12), kit.dp(14))
        addView(icon, LinearLayout.LayoutParams(kit.dp(50), kit.dp(50)))
        addView(kit.column(place, condition, reading), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = kit.dp(12); marginEnd = kit.dp(6) })
        addView(kit.chevron(), LinearLayout.LayoutParams(kit.dp(18), kit.dp(18)))
        kit.pressable(this, onClick = ::tap)
    }

    /** Shows what's kept, then fetches if it's old or missing. */
    fun refresh(force: Boolean = false) {
        if (!hasLocation()) return show(State.NEED_LOCATION)
        val kept = shown ?: Prefs.weather(ui)?.let(::decode)
        if (kept != null) show(State.READY, kept) else show(State.LOADING)
        if (busy || (!force && kept != null && System.currentTimeMillis() - kept.time < STALE_MS)) return
        busy = true
        worker.execute {
            val got = runCatching(::fetch).getOrNull()
            main.post {
                busy = false
                when {
                    got != null -> {
                        Prefs.setWeather(ui, encode(got))
                        show(State.READY, got)
                    }
                    // A failed refresh keeps showing the older reading rather than replacing it with an error.
                    shown == null -> show(State.FAILED)
                }
            }
        }
    }

    private fun tap() {
        when (state) {
            State.NEED_LOCATION -> onOpen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ui.packageName)))
            State.FAILED -> refresh(force = true)
            State.READY -> onOpen(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, ui.getString(R.string.weather_title) + " " + shown?.place.orEmpty()))
            State.LOADING -> Unit
        }
    }

    private fun show(s: State, w: Weather? = null) {
        state = s
        if (w != null) shown = w
        reading.visibility = if (s == State.READY) View.VISIBLE else View.GONE
        if (s == State.READY && w != null) {
            place.text = w.place
            condition.setText(describe(w.code))
            condition.maxLines = 1
            temp.text = ui.getString(R.string.weather_temp, w.temp)
            range.text = ui.getString(R.string.weather_range, w.high, w.low)
            icon.setImageResource(art(w.code))
        } else {
            place.setText(R.string.weather_title)
            condition.maxLines = 2
            condition.setText(when (s) { State.NEED_LOCATION -> R.string.weather_need_location; State.FAILED -> R.string.weather_failed; else -> R.string.weather_loading })
            icon.setImageResource(R.drawable.ic_wx_partly)
        }
        view.contentDescription = listOf(place.text, condition.text, if (s == State.READY) "${temp.text}, ${range.text}" else "").filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun hasLocation() = ui.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Off the main thread: where, what it's called, then the forecast for there. */
    private fun fetch(): Weather? {
        val loc = locate() ?: return null
        val name = runCatching {
            @Suppress("DEPRECATION") // The listener form is API 33+; this already runs on a worker thread.
            Geocoder(ui).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.run { locality ?: subAdminArea ?: adminArea }
        }.getOrNull() ?: ui.getString(R.string.weather_here)
        val url = Uri.parse(ui.getString(R.string.weather_url)).buildUpon()
            .appendQueryParameter("latitude", "%.3f".format(Locale.US, loc.latitude))
            .appendQueryParameter("longitude", "%.3f".format(Locale.US, loc.longitude))
            .appendQueryParameter("current", "temperature_2m,weather_code")
            .appendQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
            .appendQueryParameter("forecast_days", "1")
            .appendQueryParameter("timezone", "auto")
            .apply { if (Locale.getDefault().country in FAHRENHEIT) appendQueryParameter("temperature_unit", "fahrenheit") }
            .build().toString()
        val conn = URL(url).openConnection() as HttpURLConnection
        val body = try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        val now = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        return Weather(
            System.currentTimeMillis(), name,
            now.getDouble("temperature_2m").roundToInt(),
            daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            now.getInt("weather_code"),
        )
    }

    /** The freshest position any provider already has; failing that, one new network fix. */
    @Suppress("MissingPermission") // Only called once hasLocation() is true.
    private fun locate(): Location? {
        val lm = ui.getSystemService(LocationManager::class.java)
        lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { return it }
        if (Build.VERSION.SDK_INT < 30 || !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        var fix: Location? = null
        val done = CountDownLatch(1)
        // Delivered on the main thread: this worker is the one waiting for it.
        lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ui.mainExecutor) { fix = it; done.countDown() }
        done.await(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        return fix
    }

    private fun encode(w: Weather) = JSONObject().put("time", w.time).put("place", w.place).put("temp", w.temp).put("high", w.high).put("low", w.low).put("code", w.code).toString()

    private fun decode(s: String) = runCatching {
        JSONObject(s).run { Weather(getLong("time"), getString("place"), getInt("temp"), getInt("high"), getInt("low"), getInt("code")) }
    }.getOrNull()

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun describe(code: Int) = when (code) {
        0 -> R.string.wx_clear
        1, 2 -> R.string.wx_partly
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        in 51..57 -> R.string.wx_drizzle
        in 61..67 -> R.string.wx_rain
        in 71..77, 85, 86 -> R.string.wx_snow
        in 80..82 -> R.string.wx_showers
        in 95..99 -> R.string.wx_storm
        else -> R.string.wx_cloudy
    }

    private fun art(code: Int) = when (code) {
        0 -> R.drawable.ic_wx_sun
        1, 2 -> R.drawable.ic_wx_partly
        45, 48 -> R.drawable.ic_wx_fog
        in 51..67, in 80..82 -> R.drawable.ic_wx_rain
        in 71..77, 85, 86 -> R.drawable.ic_wx_snow
        in 95..99 -> R.drawable.ic_wx_storm
        else -> R.drawable.ic_wx_cloud
    }

    private companion object {
        const val STALE_MS = 30 * 60 * 1000L
        const val TIMEOUT_MS = 10_000
        val FAHRENHEIT = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
        val worker = Executors.newSingleThreadExecutor()
    }
}
