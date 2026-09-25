package com.streamezy.capture.monitoring

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MobileTelemetryManager(
    private val context: Context,
    private var vpsHost: String = "srv1990205.hstgr.cloud",
    private var vpsPort: Int = 8080
) {
    companion object {
        private const val TAG = "MobileTelemetryManager"
        private const val PREFS_NAME = "StreamEzyMonitoringPrefs"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    var deviceId: String? = prefs.getString("device_id", null)
    var authToken: String? = prefs.getString("auth_token", null)
    var currentSessionId: String? = null

    var cameraStatus: String = "stopped"
    var isLiveStreaming: Boolean = false

    fun updateServerConfig(host: String, port: Int = 8080) {
        this.vpsHost = host
        this.vpsPort = port
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        executor.submit {
            while (isRunning.get()) {
                try {
                    ensureRegistered()
                    if (deviceId != null && authToken != null) {
                        sendHeartbeat()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed: ${e.message}")
                }

                try {
                    Thread.sleep(10000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        if (currentSessionId != null) {
            notifyStreamStopped("app_closed")
        }
    }

    private fun postJson(endpoint: String, payload: JSONObject, token: String? = authToken): JSONObject? {
        val url = URL("http://$vpsHost:$vpsPort$endpoint")
        var conn: HttpURLConnection? = null
        try {
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code == 401 && !endpoint.contains("register")) {
                Log.w(TAG, "Auth token invalidated by VPS. Resetting...")
                authToken = null
                prefs.edit().remove("auth_token").apply()
                return null
            }

            val inputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            return if (responseText.isNotBlank()) JSONObject(responseText) else JSONObject()
        } catch (e: Exception) {
            Log.d(TAG, "HTTP error on $endpoint: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun ensureRegistered() {
        if (deviceId != null && authToken != null) return

        try {
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val payload = JSONObject().apply {
                put("name", deviceName)
                put("device_type", "android")
                put("client_version", "3.0.0-liveu")
                put("hardware_info", JSONObject().apply {
                    put("brand", Build.BRAND)
                    put("model", Build.MODEL)
                    put("sdk_int", Build.VERSION.SDK_INT)
                    put("release", Build.VERSION.RELEASE)
                })
            }

            val res = postJson("/api/devices/register", payload, null)
            if (res != null && res.has("device_id") && res.has("auth_token")) {
                deviceId = res.getString("device_id")
                authToken = res.getString("auth_token")
                prefs.edit()
                    .putString("device_id", deviceId)
                    .putString("auth_token", authToken)
                    .apply()
                Log.i(TAG, "Android device successfully registered on VPS: $deviceId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed: ${e.message}")
        }
    }

    private fun getBatteryPercentage(): Float {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (level in 0..100) level.toFloat() else 100f
    }

    private fun sendHeartbeat() {
        val devId = deviceId ?: return
        val battery = getBatteryPercentage()

        val interfacesArray = JSONArray()
        try {
            val netMgr = com.streamezy.capture.bonding.AndroidNetworkManager(context)
            netMgr.refreshCurrentNetworks()
            for (p in netMgr.getUsablePaths()) {
                val ifaceObj = JSONObject().apply {
                    put("name", p.name)
                    put("ip", p.localIp)
                    put("speed_mbps", p.availableBandwidthMbps)
                    put("latency_ms", p.latencyMs)
                    put("packet_loss_pct", p.packetLossPct)
                    put("is_up", true)
                }
                interfacesArray.put(ifaceObj)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Interface scan: ${e.message}")
        }

        val payload = JSONObject().apply {
            put("device_id", devId)
            put("status", "online")
            put("battery_level_pct", battery)
            put("camera_status", cameraStatus)
            put("interfaces", interfacesArray)
        }

        postJson("/api/devices/heartbeat", payload)
    }

    fun notifyStreamStarted(
        streamKey: String,
        resolution: String = "1280x720",
        fps: Int = 30,
        videoBitrate: Int = 1000000,
        audioBitrate: Int = 128000,
        connectionType: String = "bonded_android"
    ) {
        isLiveStreaming = true
        cameraStatus = "streaming"
        executor.submit {
            ensureRegistered()
            val devId = deviceId ?: return@submit

            val payload = JSONObject().apply {
                put("device_id", devId)
                put("stream_key", streamKey)
                put("resolution", resolution)
                put("fps", fps)
                put("video_bitrate", videoBitrate)
                put("audio_bitrate", audioBitrate)
                put("connection_type", connectionType)
            }

            val res = postJson("/api/devices/session/start", payload)
            if (res != null && res.has("session")) {
                currentSessionId = res.getJSONObject("session").getString("id")
                Log.i(TAG, "VPS streaming session active: $currentSessionId")
            }
        }
    }

    fun sendStreamTelemetry(
        fps: Float,
        videoBitrate: Int,
        audioBitrate: Int = 128000,
        packetLossPct: Double = 0.0,
        latencyMs: Long = 0,
        jitterMs: Long = 0
    ) {
        val sessId = currentSessionId ?: return
        executor.submit {
            val payload = JSONObject().apply {
                put("session_id", sessId)
                put("fps", fps.toDouble())
                put("video_bitrate", videoBitrate)
                put("audio_bitrate", audioBitrate)
                put("packet_loss_pct", packetLossPct)
                put("latency_ms", latencyMs.toDouble())
                put("jitter_ms", jitterMs.toDouble())
            }
            postJson("/api/devices/session/telemetry", payload)
        }
    }

    fun notifyStreamStopped(reason: String = "normal") {
        isLiveStreaming = false
        cameraStatus = "preview"
        val sessId = currentSessionId ?: return
        currentSessionId = null
        executor.submit {
            val payload = JSONObject().apply {
                put("session_id", sessId)
                put("reason", reason)
            }
            postJson("/api/devices/session/stop", payload)
        }
    }
}
