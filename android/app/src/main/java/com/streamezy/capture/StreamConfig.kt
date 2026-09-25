package com.streamezy.capture

import android.content.Context
import android.content.SharedPreferences
import com.streamezy.capture.update.OnlineUpdateManager
import org.json.JSONObject
import java.io.File

class StreamConfig(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("StreamEzyCapturePrefs", Context.MODE_PRIVATE)

    private var remoteOverrides: JSONObject? = null

    init {
        loadRemoteConfigOverrides()
    }

    fun loadRemoteConfigOverrides() {
        try {
            val updateMgr = OnlineUpdateManager(context)
            val cfgFile = updateMgr.getActiveFile("stream_config.json")
            if (cfgFile != null && cfgFile.exists()) {
                val jsonStr = cfgFile.readText()
                remoteOverrides = JSONObject(jsonStr)
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_RTMP_URL = "rtmp://srv1990205.hstgr.cloud:1935/live"
        const val DEFAULT_STREAM_KEY = ""
        const val DEFAULT_VPS_HOST = "srv1990205.hstgr.cloud"
        const val DEFAULT_VPS_PORT = 5000

        const val MAX_GLOBAL_STREAM_BITRATE = 1500 * 1000 // Strict 1.5 Mbps maximum total stream data transmission
        const val DEFAULT_BITRATE = 1500 * 1000 // 1.5 Mbps
        const val BITRATE_500K = 500 * 1000 // 500 kbps
        const val BITRATE_1000K = 1000 * 1000 // 1000 kbps (1.0 Mbps)
        const val BITRATE_1500K = 1500 * 1000 // 1500 kbps (1.5 Mbps Cap)

        const val LANDSCAPE_WIDTH = 1280
        const val LANDSCAPE_HEIGHT = 720
        const val DEFAULT_FPS = 30
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_BITRATE = 128 * 1000 // 128 kbps
    }

    var videoBitrate: Int
        get() {
            val b = prefs.getInt("video_bitrate", DEFAULT_BITRATE)
            return b.coerceAtMost(MAX_GLOBAL_STREAM_BITRATE)
        }
        set(value) {
            val clamped = value.coerceAtMost(MAX_GLOBAL_STREAM_BITRATE)
            prefs.edit().putInt("video_bitrate", clamped).apply()
        }

    var selectedAspectRatio: String
        get() = "16:9"
        set(_) {
            prefs.edit().putString("selected_aspect_ratio", "16:9").apply()
        }

    val videoWidth: Int get() = LANDSCAPE_WIDTH
    val videoHeight: Int get() = LANDSCAPE_HEIGHT
    val aspectRatioFloat: Float get() = 16f / 9f

    var rtmpUrl: String
        get() = prefs.getString("rtmp_url", DEFAULT_RTMP_URL) ?: DEFAULT_RTMP_URL
        set(value) = prefs.edit().putString("rtmp_url", value.trim()).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", DEFAULT_STREAM_KEY) ?: DEFAULT_STREAM_KEY
        set(value) = prefs.edit().putString("stream_key", value.trim()).apply()

    var isBondingEnabled: Boolean
        get() = prefs.getBoolean("bonding_enabled", true)
        set(value) = prefs.edit().putBoolean("bonding_enabled", value).apply()

    var bondingServerHost: String
        get() = prefs.getString("bonding_server_host", DEFAULT_VPS_HOST) ?: DEFAULT_VPS_HOST
        set(value) = prefs.edit().putString("bonding_server_host", value.trim()).apply()

    var bondingServerPort: Int
        get() = prefs.getInt("bonding_server_port", DEFAULT_VPS_PORT)
        set(value) = prefs.edit().putInt("bonding_server_port", value).apply()

    var bondingMode: String
        get() = prefs.getString("bonding_mode", "ON") ?: "ON"
        set(value) {
            prefs.edit().putString("bonding_mode", value).apply()
            isBondingEnabled = (value == "ON")
        }

    var bondingAuthToken: String
        get() = prefs.getString("bonding_auth_token", "") ?: ""
        set(value) = prefs.edit().putString("bonding_auth_token", value.trim()).apply()

    var isAutoFallbackEnabled: Boolean
        get() = prefs.getBoolean("bonding_auto_fallback", true)
        set(value) = prefs.edit().putBoolean("bonding_auto_fallback", value).apply()

    var playoutDelayMs: Double
        get() = prefs.getFloat("bonding_playout_delay_ms", 1000.0f).toDouble()
        set(value) = prefs.edit().putFloat("bonding_playout_delay_ms", value.toFloat()).apply()

    var enableArq: Boolean
        get() = prefs.getBoolean("bonding_enable_arq", true)
        set(value) = prefs.edit().putBoolean("bonding_enable_arq", value).apply()

    var enableRedundancy: Boolean
        get() = prefs.getBoolean("bonding_enable_redundancy", true)
        set(value) = prefs.edit().putBoolean("bonding_enable_redundancy", value).apply()

    var enableFec: Boolean
        get() = prefs.getBoolean("bonding_enable_fec", true)
        set(value) = prefs.edit().putBoolean("bonding_enable_fec", value).apply()

    var fecBlockSize: Int
        get() = prefs.getInt("bonding_fec_block_size", 8)
        set(value) = prefs.edit().putInt("bonding_fec_block_size", value).apply()

    val fullStreamEndpoint: String
        get() {
            val base = rtmpUrl.removeSuffix("/")
            val key = streamKey.trim()
            return if (key.isNotBlank()) {
                "$base/$key"
            } else {
                base
            }
        }
}