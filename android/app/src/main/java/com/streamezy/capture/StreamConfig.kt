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
        get() = prefs.getString("selected_aspect_ratio", "16:9") ?: "16:9"
        set(value) = prefs.edit().putString("selected_aspect_ratio", value).apply()

    var isPortraitShorts: Boolean
        get() = selectedAspectRatio == "9:16"
        set(value) {
            selectedAspectRatio = if (value) "9:16" else "16:9"
        }

    val videoWidth: Int
        get() = when (selectedAspectRatio) {
            "9:16" -> SHORTS_WIDTH
            "4:3" -> STANDARD_WIDTH
            else -> LANDSCAPE_WIDTH
        }

    val videoHeight: Int
        get() = when (selectedAspectRatio) {
            "9:16" -> SHORTS_HEIGHT
            "4:3" -> STANDARD_HEIGHT
            else -> LANDSCAPE_HEIGHT
        }

    val aspectRatioFloat: Float
        get() = when (selectedAspectRatio) {
            "9:16" -> 9f / 16f
            "4:3" -> 4f / 3f
            else -> 16f / 9f
        }

    var rtmpUrl: String
        get() = prefs.getString("rtmp_url", DEFAULT_RTMP_URL) ?: DEFAULT_RTMP_URL
        set(value) = prefs.edit().putString("rtmp_url", value.trim()).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", DEFAULT_STREAM_KEY) ?: DEFAULT_STREAM_KEY
        set(value) = prefs.edit().putString("stream_key", value.trim()).apply()

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