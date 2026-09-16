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
        const val BASE_WIDTH = 1280
        const val BASE_HEIGHT = 720
        const val DEFAULT_BITRATE = 1000 * 1000 // 1000 kbps (1 Mbps)
        const val DEFAULT_FPS = 30
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_BITRATE = 128 * 1000 // 128 kbps
    }

    var rtmpUrl: String
        get() = prefs.getString("rtmp_url", DEFAULT_RTMP_URL) ?: DEFAULT_RTMP_URL
        set(value) = prefs.edit().putString("rtmp_url", value.trim()).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", DEFAULT_STREAM_KEY) ?: DEFAULT_STREAM_KEY
        set(value) = prefs.edit().putString("stream_key", value.trim()).apply()

    var isPortraitShorts: Boolean
        get() = prefs.getBoolean("is_portrait_shorts", false)
        set(value) = prefs.edit().putBoolean("is_portrait_shorts", value).apply()

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