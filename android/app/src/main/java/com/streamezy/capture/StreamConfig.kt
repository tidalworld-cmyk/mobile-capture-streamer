package com.streamezy.capture

import android.content.Context
import android.content.SharedPreferences

class StreamConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("StreamEzyCapturePrefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_RTMP_URL = "rtmp://tn.streamezy.in/siva"
        const val DEFAULT_STREAM_KEY = "live"
        const val YOUTUBE_RTMP_URL = "rtmp://a.rtmp.youtube.com/live2"
        const val LANDSCAPE_WIDTH = 1280
        const val LANDSCAPE_HEIGHT = 720
        const val SHORTS_WIDTH = 720
        const val SHORTS_HEIGHT = 1280
        const val STANDARD_WIDTH = 960
        const val STANDARD_HEIGHT = 720
        const val DEFAULT_BITRATE = 1000 * 1000 // 1000 kbps (1 Mbps)
        const val DEFAULT_FPS = 30
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_BITRATE = 128 * 1000 // 128 kbps
    }

    var selectedAspectRatio: String
        get() = "16:9"
        set(_) {
            prefs.edit().putString("selected_aspect_ratio", "16:9").apply()
        }

    var isPortraitShorts: Boolean
        get() = false
        set(_) {}

    val videoWidth: Int
        get() = LANDSCAPE_WIDTH // 1280

    val videoHeight: Int
        get() = LANDSCAPE_HEIGHT // 720

    val aspectRatioFloat: Float
        get() = 16f / 9f

    var rtmpUrl: String
        get() = prefs.getString("rtmp_url", DEFAULT_RTMP_URL) ?: DEFAULT_RTMP_URL
        set(value) = prefs.edit().putString("rtmp_url", value.trim()).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", DEFAULT_STREAM_KEY) ?: DEFAULT_STREAM_KEY
        set(value) = prefs.edit().putString("stream_key", value.trim()).apply()

    var isBondingEnabled: Boolean
        get() = prefs.getBoolean("bonding_enabled", false)
        set(value) = prefs.edit().putBoolean("bonding_enabled", value).apply()

    var bondingServerHost: String
        get() = prefs.getString("bonding_server_host", "192.168.29.184") ?: "192.168.29.184"
        set(value) = prefs.edit().putString("bonding_server_host", value.trim()).apply()

    var bondingServerPort: Int
        get() = prefs.getInt("bonding_server_port", 5000)
        set(value) = prefs.edit().putInt("bonding_server_port", value).apply()

    var bondingMode: String
        get() = prefs.getString("bonding_mode", if (isBondingEnabled) "ON" else "OFF") ?: "OFF"
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