package com.streamezy.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.extrasources.CameraUvcSource
import com.pedro.library.generic.GenericStream

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var textureView: TextureView
    private lateinit var tvLiveBadge: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvStreamStats: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var tileRearCam: LinearLayout
    private lateinit var tileFrontCam: LinearLayout
    private lateinit var tileOtgCam: LinearLayout
    private lateinit var tvRearLabel: TextView
    private lateinit var tvFrontLabel: TextView
    private lateinit var tvOtgLabel: TextView
    private lateinit var ivOtgIcon: ImageView
    private lateinit var btnLive: Button

    private lateinit var streamConfig: StreamConfig
    private var genericStream: GenericStream? = null
    private lateinit var camera2Source: Camera2Source
    private lateinit var microphoneSource: MicrophoneSource
    private var cameraUvcSource: CameraUvcSource? = null

    private enum class ActiveSource { REAR, FRONT, OTG }
    private var currentSource = ActiveSource.REAR

    private var isStreaming = false
    private var streamStartTime: Long = 0
    private var lastLiveClickTime: Long = 0
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (isStreaming) {
                val elapsed = (SystemClock.elapsedRealtime() - streamStartTime) / 1000
                val hours = elapsed / 3600
                val minutes = (elapsed % 3600) / 60
                val seconds = elapsed % 60
                tvUptime.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                uptimeHandler.postDelayed(this, 1000)
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Toast.makeText(context, "USB Video Capture Card Connected!", Toast.LENGTH_SHORT).show()
                    updateOtgAvailability(true)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Toast.makeText(context, "USB Video Capture Card Disconnected", Toast.LENGTH_SHORT).show()
                    updateOtgAvailability(false)
                    if (currentSource == ActiveSource.OTG) {
                        selectRearCamera()
                    }
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

            if (isCharging) {
                tvBatteryStatus.text = "⚡ $batteryPct% (Charging)"
                tvBatteryStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
            } else {
                tvBatteryStatus.text = "🔋 $batteryPct%"
                tvBatteryStatus.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (batteryPct <= 20) R.color.accent_red else R.color.text_primary
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "StreamEzy"
        private const val PERMISSIONS_REQUEST_CODE = 101
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Prevent crashes from uncaught thread exceptions
        val defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught error in ${thread.name}: ${throwable.message}", throwable)
            runOnUiThread {
                Toast.makeText(applicationContext, "Stream error: ${throwable.localizedMessage}", Toast.LENGTH_LONG).show()
            }
            defaultUncaughtHandler?.uncaughtException(thread, throwable)
        }

        streamConfig = StreamConfig(this)

        initViews()
        setupListeners()
        registerUsbReceiver()
        registerBatteryReceiver()

        if (allPermissionsGranted()) {
            initStreamEngine()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun initViews() {
        textureView = findViewById(R.id.textureView)
        tvLiveBadge = findViewById(R.id.tvLiveBadge)
        tvUptime = findViewById(R.id.tvUptime)
        tvStreamStats = findViewById(R.id.tvStreamStats)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnAspectRatio = findViewById(R.id.btnAspectRatio)
        btnSettings = findViewById(R.id.btnSettings)
        tileRearCam = findViewById(R.id.tileRearCam)
        tileFrontCam = findViewById(R.id.tileFrontCam)
        tileOtgCam = findViewById(R.id.tileOtgCam)
        tvRearLabel = findViewById(R.id.tvRearLabel)
        tvFrontLabel = findViewById(R.id.tvFrontLabel)
        tvOtgLabel = findViewById(R.id.tvOtgLabel)
        ivOtgIcon = findViewById(R.id.ivOtgIcon)
        btnLive = findViewById(R.id.btnLive)

        checkUsbConnectedInitially()
    }

    private fun setupListeners() {
        btnLive.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveClickTime < 1200) {
                return@setOnClickListener // Debounce fast clicks
            }
            lastLiveClickTime = now

            if (isStreaming) {
                stopLiveStream()
            } else {
                startLiveStream()
            }
        }

        tileRearCam.setOnClickListener { selectRearCamera() }
        tileFrontCam.setOnClickListener { selectFrontCamera() }
        tileOtgCam.setOnClickListener { selectOtgCamera() }

        btnAspectRatio.setOnClickListener { toggleAspectRatio() }
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initStreamEngine() {
        try {
            camera2Source = Camera2Source(this)
            microphoneSource = MicrophoneSource()
            genericStream = GenericStream(this, this, camera2Source, microphoneSource)
            prepareAndStartPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Init stream engine failed", e)
            Toast.makeText(this, "Camera init failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun prepareAndStartPreview() {
        val stream = genericStream ?: return
        try {
            // Prepare hardware encoders only if not already active
            if (!stream.isOnPreview && !stream.isStreaming) {
                val rotation = if (streamConfig.isPortraitShorts) 90 else 0
                val videoPrepared = stream.prepareVideo(
                    StreamConfig.BASE_WIDTH,
                    StreamConfig.BASE_HEIGHT,
                    StreamConfig.DEFAULT_BITRATE,
                    StreamConfig.DEFAULT_FPS,
                    2,
                    rotation
                )
                val audioPrepared = stream.prepareAudio(
                    StreamConfig.DEFAULT_SAMPLE_RATE,
                    true,
                    StreamConfig.DEFAULT_AUDIO_BITRATE
                )
                if (!videoPrepared || !audioPrepared) {
                    Log.w(TAG, "Hardware video ($videoPrepared) or audio ($audioPrepared) returned false")
                }
            }

            // If TextureView surface is already available, start preview directly
            if (textureView.isAvailable) {
                if (!stream.isOnPreview) {
                    stream.startPreview(textureView)
                }
            } else {
                // Otherwise attach listener to start as soon as TextureView surface is ready
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        try {
                            if (genericStream?.isOnPreview == false) {
                                genericStream?.startPreview(textureView)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "onSurfaceTextureAvailable startPreview failed", e)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        try {
                            genericStream?.getGlInterface()?.setPreviewResolution(width, height)
                        } catch (e: Exception) {
                            Log.e(TAG, "setPreviewResolution failed", e)
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        try {
                            if (genericStream?.isOnPreview == true) {
                                genericStream?.stopPreview()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "onSurfaceTextureDestroyed failed", e)
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
            updateStatsDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "prepareAndStartPreview failed", e)
            Toast.makeText(this, "Preview error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLiveStream() {
        val stream = genericStream ?: run {
            Toast.makeText(this, "Stream engine not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        val endpoint = streamConfig.fullStreamEndpoint

        if (endpoint.isBlank() || endpoint == "rtmp://" || !endpoint.startsWith("rtmp")) {
            Toast.makeText(this, "Please configure RTMP URL in settings", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        try {
            btnLive.isEnabled = false
            btnLive.text = getString(R.string.connecting)

            // CRITICAL FIX: Reset any lingering stream state before starting again
            if (stream.isStreaming) {
                try {
                    stream.stopStream()
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup previous stream state before start", e)
                }
            }

            // If preview was not active, prepare now
            if (!stream.isOnPreview && !stream.isStreaming) {
                val rotation = if (streamConfig.isPortraitShorts) 90 else 0
                stream.prepareVideo(
                    StreamConfig.BASE_WIDTH,
                    StreamConfig.BASE_HEIGHT,
                    StreamConfig.DEFAULT_BITRATE,
                    StreamConfig.DEFAULT_FPS,
                    2,
                    rotation
                )
                stream.prepareAudio(
                    StreamConfig.DEFAULT_SAMPLE_RATE,
                    true,
                    StreamConfig.DEFAULT_AUDIO_BITRATE
                )
                if (textureView.isAvailable) {
                    stream.startPreview(textureView)
                }
            }

            stream.startStream(endpoint)
        } catch (e: Exception) {
            Log.e(TAG, "startStream failed", e)
            btnLive.isEnabled = true
            btnLive.text = getString(R.string.go_live)
            Toast.makeText(this, "Start live failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLiveStream() {
        try {
            genericStream?.stopStream()
        } catch (e: Exception) {
            Log.e(TAG, "stopStream error", e)
        }
        onDisconnect()
    }

    private fun selectRearCamera() {
        try {
            if (currentSource == ActiveSource.OTG) {
                if (camera2Source.getCameraFacing() != CameraHelper.Facing.BACK) {
                    camera2Source.switchCamera()
                }
                genericStream?.changeVideoSource(camera2Source)
            } else if (camera2Source.getCameraFacing() != CameraHelper.Facing.BACK) {
                camera2Source.switchCamera()
            }
            currentSource = ActiveSource.REAR
            updateSwitcherUI()
        } catch (e: Exception) {
            Log.e(TAG, "Switch to Rear failed", e)
            Toast.makeText(this, "Switch to Rear failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectFrontCamera() {
        try {
            if (currentSource == ActiveSource.OTG) {
                if (camera2Source.getCameraFacing() != CameraHelper.Facing.FRONT) {
                    camera2Source.switchCamera()
                }
                genericStream?.changeVideoSource(camera2Source)
            } else if (camera2Source.getCameraFacing() != CameraHelper.Facing.FRONT) {
                camera2Source.switchCamera()
            }
            currentSource = ActiveSource.FRONT
            updateSwitcherUI()
        } catch (e: Exception) {
            Log.e(TAG, "Switch to Front failed", e)
            Toast.makeText(this, "Switch to Front failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectOtgCamera() {
        try {
            if (cameraUvcSource == null) {
                cameraUvcSource = CameraUvcSource()
            }
            genericStream?.changeVideoSource(cameraUvcSource!!)
            currentSource = ActiveSource.OTG
            updateSwitcherUI()
            Toast.makeText(this, "Switched to OTG Video Capture Card", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "OTG Camera switch failed", e)
            Toast.makeText(this, "OTG Card error: ${e.localizedMessage}. Ensure OTG is turned on.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSwitcherUI() {
        tileRearCam.setBackgroundResource(if (currentSource == ActiveSource.REAR) R.drawable.bg_switcher_item_selected else R.drawable.bg_switcher_item_normal)
        tvRearLabel.setTextColor(ContextCompat.getColor(this, if (currentSource == ActiveSource.REAR) R.color.white else R.color.text_secondary))

        tileFrontCam.setBackgroundResource(if (currentSource == ActiveSource.FRONT) R.drawable.bg_switcher_item_selected else R.drawable.bg_switcher_item_normal)
        tvFrontLabel.setTextColor(ContextCompat.getColor(this, if (currentSource == ActiveSource.FRONT) R.color.white else R.color.text_secondary))

        tileOtgCam.setBackgroundResource(if (currentSource == ActiveSource.OTG) R.drawable.bg_switcher_item_selected else R.drawable.bg_switcher_item_normal)
        tvOtgLabel.setTextColor(ContextCompat.getColor(this, if (currentSource == ActiveSource.OTG) R.color.white else R.color.text_secondary))
    }

    private fun toggleAspectRatio() {
        if (isStreaming) {
            Toast.makeText(this, "Stop stream before changing aspect ratio", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            streamConfig.isPortraitShorts = !streamConfig.isPortraitShorts
            val stream = genericStream ?: return
            if (stream.isOnPreview) {
                stream.stopPreview()
            }
            prepareAndStartPreview()
            val mode = if (streamConfig.isPortraitShorts) "9:16 Shorts (Vertical)" else "16:9 Landscape"
            Toast.makeText(this, "Switched to $mode", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Aspect ratio toggle failed", e)
            Toast.makeText(this, "Aspect ratio error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatsDisplay() {
        val aspect = if (streamConfig.isPortraitShorts) "9:16 Shorts" else "16:9"
        tvStreamStats.text = "1000 kbps | 30 fps ($aspect)"
    }

    private fun checkUsbConnectedInitially() {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        val deviceList = usbManager?.deviceList
        val hasUvc = deviceList?.values?.any { dev ->
            dev.deviceClass == 14 || dev.deviceClass == 239 || (0 until dev.interfaceCount).any { i -> dev.getInterface(i).interfaceClass == 14 }
        } ?: false
        updateOtgAvailability(hasUvc)
    }

    private fun updateOtgAvailability(available: Boolean) {
        ivOtgIcon.setColorFilter(ContextCompat.getColor(this, if (available) R.color.accent_green else R.color.white))
        tvOtgLabel.text = if (available) "OTG READY" else "OTG CARD"
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etRtmpUrl = dialogView.findViewById<EditText>(R.id.etRtmpUrl)
        val etStreamKey = dialogView.findViewById<EditText>(R.id.etStreamKey)
        val btnPresetVps = dialogView.findViewById<Button>(R.id.btnPresetVps)
        val btnPresetYouTube = dialogView.findViewById<Button>(R.id.btnPresetYouTube)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        etRtmpUrl.setText(streamConfig.rtmpUrl)
        etStreamKey.setText(streamConfig.streamKey)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnPresetVps.setOnClickListener {
            etRtmpUrl.setText(StreamConfig.DEFAULT_RTMP_URL)
        }

        btnPresetYouTube.setOnClickListener {
            etRtmpUrl.setText(StreamConfig.YOUTUBE_RTMP_URL)
        }

        btnSave.setOnClickListener {
            streamConfig.rtmpUrl = etRtmpUrl.text.toString().trim()
            streamConfig.streamKey = etStreamKey.text.toString().trim()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    // --- ConnectChecker Callbacks ---

    override fun onConnectionStarted(url: String) {
        runOnUiThread {
            btnLive.isEnabled = false
            btnLive.text = getString(R.string.connecting)
        }
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            isStreaming = true
            streamStartTime = SystemClock.elapsedRealtime()
            uptimeHandler.post(uptimeRunnable)

            btnLive.isEnabled = true
            btnLive.text = getString(R.string.stop_stream)
            btnLive.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))

            tvLiveBadge.text = getString(R.string.live_badge)
            tvLiveBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))

            Toast.makeText(this, "Live Broadcast Connected!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        try {
            genericStream?.stopStream()
        } catch (e: Exception) {
            Log.e(TAG, "stopStream on connection failed error", e)
        }
        runOnUiThread {
            isStreaming = false
            btnLive.isEnabled = true
            btnLive.text = getString(R.string.go_live)
            btnLive.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))

            tvLiveBadge.text = getString(R.string.offline_badge)
            tvLiveBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.border_inactive))

            Toast.makeText(this, "Connection failed: $reason", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread {
            val kbps = bitrate / 1000
            val aspect = if (streamConfig.isPortraitShorts) "9:16 Shorts" else "16:9"
            tvStreamStats.text = "$kbps kbps | 30 fps ($aspect)"
        }
    }

    override fun onDisconnect() {
        try {
            if (genericStream?.isStreaming == true) {
                genericStream?.stopStream()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopStream onDisconnect error", e)
        }
        runOnUiThread {
            isStreaming = false
            uptimeHandler.removeCallbacks(uptimeRunnable)
            tvUptime.text = "00:00:00"

            btnLive.isEnabled = true
            btnLive.text = getString(R.string.go_live)
            btnLive.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))

            tvLiveBadge.text = getString(R.string.offline_badge)
            tvLiveBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.border_inactive))
        }
    }

    override fun onAuthError() {
        try {
            genericStream?.stopStream()
        } catch (e: Exception) {}
        runOnUiThread {
            Toast.makeText(this, "RTMP Authentication Error: check stream key", Toast.LENGTH_SHORT).show()
            onDisconnect()
        }
    }

    override fun onAuthSuccess() {}

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initStreamEngine()
            } else {
                Toast.makeText(this, "Camera & Audio permissions are required to stream", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && genericStream != null && !genericStream!!.isOnPreview && !isStreaming) {
            if (textureView.isAvailable) {
                try {
                    genericStream?.startPreview(textureView)
                } catch (e: Exception) {
                    Log.e(TAG, "onResume startPreview failed", e)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isStreaming && genericStream?.isOnPreview == true) {
            try {
                genericStream?.stopPreview()
            } catch (e: Exception) {
                Log.e(TAG, "stopPreview onPause failed", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
        uptimeHandler.removeCallbacks(uptimeRunnable)
        try {
            if (genericStream?.isStreaming == true) {
                genericStream?.stopStream()
            }
            if (genericStream?.isOnPreview == true) {
                genericStream?.stopPreview()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy stream cleanup failed", e)
        }
    }
}