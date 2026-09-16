package com.streamezy.capture

import android.content.Context
import android.content.SharedPreferences

class StreamConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("StreamEzyCapturePrefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_RTMP_URL = "rtmp://tn.streamezy.in/siva"
        const val YOUTUBE_RTMP_URL = "rtmp://a.rtmp.youtube.com/live2"
        const val DEFAULT_BITRATE = 1000 * 1024 // 1000 kbps
        const val DEFAULT_FPS = 30
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_AUDIO_BITRATE = 128 * 1024 // 128 kbps
    }

    var rtmpUrl: String
        get() = prefs.getString("rtmp_url", DEFAULT_RTMP_URL) ?: DEFAULT_RTMP_URL
        set(value) = prefs.edit().putString("rtmp_url", value.trim()).apply()

    var streamKey: String
        get() = prefs.getString("stream_key", "") ?: ""
        set(value) = prefs.edit().putString("stream_key", value.trim()).apply()

    var isPortraitShorts: Boolean
        get() = prefs.getBoolean("is_portrait_shorts", false)
        set(value) = prefs.edit().putBoolean("is_portrait_shorts", value).apply()

    val width: Int
        get() = if (isPortraitShorts) 720 else 1280

    val height: Int
        get() = if (isPortraitShorts) 1280 else 720

    val fullStreamEndpoint: String
        get() {
            val base = rtmpUrl.removeSuffix("/")
            return if (streamKey.isNotBlank()) {
                "$base/$streamKey"
            } else {
                base
            }
        }
}
