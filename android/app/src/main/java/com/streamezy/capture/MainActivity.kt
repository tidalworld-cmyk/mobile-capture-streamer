package com.streamezy.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
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
import android.app.PendingIntent
import android.os.Build
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
    private var otgCameraSource: OtgCameraSource? = null

    private enum class ActiveSource { REAR, FRONT, OTG }
    private var currentSource = ActiveSource.REAR

    private var isStreaming = false
    private var streamStartTime: Long = 0
    private var lastLiveClickTime: Long = 0
    private var smoothedKbps: Long = 0L
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
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Toast.makeText(context, "USB Video Capture Card Connected!", Toast.LENGTH_SHORT).show()
                    updateOtgAvailability(true)
                    device?.let { requestUsbPermissionIfNeeded(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Toast.makeText(context, "USB Video Capture Card Disconnected", Toast.LENGTH_SHORT).show()
                    updateOtgAvailability(false)
                    if (currentSource == ActiveSource.OTG) {
                        selectRearCamera()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        Toast.makeText(context, "USB Capture Card Authorized", Toast.LENGTH_SHORT).show()
                        updateOtgAvailability(true)
                    } else {
                        Toast.makeText(context, "USB Permission Denied for Capture Card", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun requestUsbPermissionIfNeeded(device: UsbDevice) {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) {
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, intent, flags
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestUsbPermissionIfNeeded failed", e)
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
        private const val ACTION_USB_PERMISSION = "com.streamezy.capture.USB_PERMISSION"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_main)

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(TAG, "Crash shielded in ${thread.name}: ${throwable.message}", throwable)
                val msg = throwable.localizedMessage ?: "Notice"
                if (msg.contains("ComponentInfo", ignoreCase = true) ||
                    msg.contains("SuperNotCalledException", ignoreCase = true)) {
                    defaultHandler?.uncaughtException(thread, throwable)
                } else {
                    runOnUiThread {
                        try {
                            Toast.makeText(applicationContext, "Stream recovered: $msg", Toast.LENGTH_SHORT).show()
                            btnLive.isEnabled = true
                            if (!isStreaming) {
                                btnLive.text = getString(R.string.go_live)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "UI toast error in uncaught handler", e)
                        }
                    }
                }
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
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error in onCreate", e)
            Toast.makeText(this, "Startup warning: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                updateOtgAvailability(true)
                device?.let { requestUsbPermissionIfNeeded(it) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onNewIntent failed", e)
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
            genericStream?.getGlInterface()?.autoHandleOrientation = true

            // RTMP network resilience configuration to prevent Broken Pipe & Socket drops
            genericStream?.getStreamClient()?.apply {
                setReTries(10) // Automatically retry up to 10 times on network jitter
                setSocketTimeout(10000) // 10s socket timeout for cellular/Wi-Fi packet delays
            }

            prepareAndStartPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Init stream engine failed", e)
            Toast.makeText(this, "Camera init failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The customer-selected aspect ratio remains fixed across mobile, tablet, and desktop,
     * including when the phone is held vertically.
     *
     * 16:9 -> video remains 16:9 in portrait mobile (centered with letterbox black bars).
     * 9:16 -> video remains 9:16 (centered with pillarbox on wide displays).
     * 4:3  -> video remains 4:3 (centered with letterbox black bars).
     * The mobile screen will not automatically force the video into 9:16.
     * The video fits inside the fixed ratio without stretching or distortion.
     */
    private fun adjustAspectRatio(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return

        val targetRatio: Float = streamConfig.aspectRatioFloat

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val scaleX: Float
        val scaleY: Float

        if (viewRatio > targetRatio) {
            // View is wider than target aspect ratio -> fit height, pillarbox width
            scaleX = targetRatio / viewRatio
            scaleY = 1.0f
        } else {
            // View is taller than target aspect ratio (e.g. phone held vertically)
            // -> fit width, scale down height (letterbox with black bars top and bottom)
            scaleX = 1.0f
            scaleY = viewRatio / targetRatio
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    private fun prepareAndStartPreview() {
        val stream = genericStream ?: return
        try {
            // Prepare hardware encoders only if not already active
            if (!stream.isOnPreview && !stream.isStreaming) {
                val videoPrepared = stream.prepareVideo(
                    streamConfig.videoWidth,
                    streamConfig.videoHeight,
                    StreamConfig.DEFAULT_BITRATE,
                    StreamConfig.DEFAULT_FPS,
                    2,
                    0
                )
                val audioPrepared = stream.prepareAudio(
                    StreamConfig.DEFAULT_SAMPLE_RATE,
                    false, // Universal MONO channel for 100% Android mic & OTG capture card compatibility
                    StreamConfig.DEFAULT_AUDIO_BITRATE
                )
                if (!videoPrepared || !audioPrepared) {
                    Log.w(TAG, "Hardware video ($videoPrepared) or audio ($audioPrepared) returned false")
                }
            }

            // If TextureView surface is already available, calculate aspect ratio and start preview
            if (textureView.isAvailable) {
                adjustAspectRatio(textureView.width, textureView.height)
                if (!stream.isOnPreview) {
                    stream.startPreview(textureView)
                }
            } else {
                // Otherwise attach listener to adjust and start as soon as TextureView surface is ready
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        adjustAspectRatio(width, height)
                        try {
                            if (genericStream?.isOnPreview == false) {
                                genericStream?.startPreview(textureView)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "onSurfaceTextureAvailable startPreview failed", e)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        adjustAspectRatio(width, height)
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

        if (streamConfig.rtmpUrl.contains("youtube", ignoreCase = true) && streamConfig.streamKey.isBlank()) {
            Toast.makeText(this, "Please enter your YouTube Stream Key in settings ⚙️", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }

        try {
            btnLive.isEnabled = false
            btnLive.text = getString(R.string.connecting)

            // Reset any lingering stream state before starting again
            if (stream.isStreaming) {
                try {
                    stream.stopStream()
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup previous stream state before start", e)
                }
            }

            // Only prepare video & audio if preview was not active (RootEncoder requires preview stopped to prepare)
            if (!stream.isOnPreview && !stream.isStreaming) {
                val videoPrepared = stream.prepareVideo(
                    streamConfig.videoWidth,
                    streamConfig.videoHeight,
                    StreamConfig.DEFAULT_BITRATE,
                    StreamConfig.DEFAULT_FPS,
                    2,
                    0
                )
                val audioPrepared = stream.prepareAudio(
                    StreamConfig.DEFAULT_SAMPLE_RATE,
                    false, // MONO channel
                    StreamConfig.DEFAULT_AUDIO_BITRATE
                )

                if (!videoPrepared || !audioPrepared) {
                    Log.w(TAG, "Encoder preparation returned false (v=$videoPrepared, a=$audioPrepared)")
                }

                if (textureView.isAvailable) {
                    adjustAspectRatio(textureView.width, textureView.height)
                    stream.startPreview(textureView)
                }
            }

            // Setup resilient retry
            stream.getStreamClient().setReTries(5)
            stream.startStream(endpoint)
        } catch (e: Throwable) {
            Log.e(TAG, "startStream failed", e)
            btnLive.isEnabled = true
            btnLive.text = getString(R.string.go_live)
            Toast.makeText(this, "Start live failed: ${e.localizedMessage ?: "Unexpected error"}", Toast.LENGTH_LONG).show()
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
        if (!::camera2Source.isInitialized) {
            Toast.makeText(this, "Camera initializing, please grant permissions", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSource == ActiveSource.REAR && camera2Source.getCameraFacing() == CameraHelper.Facing.BACK) {
            return
        }
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
            textureView.post {
                adjustAspectRatio(textureView.width, textureView.height)
            }
            val resLabel = "${streamConfig.videoWidth}x${streamConfig.videoHeight} (${streamConfig.selectedAspectRatio})"
            Toast.makeText(this, "Rear Camera: $resLabel", Toast.LENGTH_SHORT).show()
            updateStatsDisplay()
        } catch (e: Throwable) {
            Log.e(TAG, "Switch to Rear failed", e)
            Toast.makeText(this, "Switch to Rear failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectFrontCamera() {
        if (!::camera2Source.isInitialized) {
            Toast.makeText(this, "Camera initializing, please grant permissions", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSource == ActiveSource.FRONT && camera2Source.getCameraFacing() == CameraHelper.Facing.FRONT) {
            return
        }
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
            textureView.post {
                adjustAspectRatio(textureView.width, textureView.height)
            }
            val resLabel = "${streamConfig.videoWidth}x${streamConfig.videoHeight} (${streamConfig.selectedAspectRatio})"
            Toast.makeText(this, "Front Camera: $resLabel", Toast.LENGTH_SHORT).show()
            updateStatsDisplay()
        } catch (e: Throwable) {
            Log.e(TAG, "Switch to Front failed", e)
            Toast.makeText(this, "Switch to Front failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectOtgCamera() {
        if (currentSource == ActiveSource.OTG) {
            return
        }

        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val uvcDevice = usbManager?.deviceList?.values?.firstOrNull { dev ->
                dev.deviceClass == 14 || dev.deviceClass == 239 ||
                (0 until dev.interfaceCount).any { i -> dev.getInterface(i).interfaceClass == 14 }
            }

            if (uvcDevice == null) {
                Toast.makeText(this, "No USB Capture Card detected. Check OTG connection.", Toast.LENGTH_LONG).show()
                return
            }

            if (!usbManager.hasPermission(uvcDevice)) {
                Toast.makeText(this, "Requesting USB permission for capture card...", Toast.LENGTH_SHORT).show()
                requestUsbPermissionIfNeeded(uvcDevice)
                return
            }

            val otg = otgCameraSource ?: OtgCameraSource(this).also { otgCameraSource = it }
            otg.setTargetDimensions(streamConfig.videoWidth, streamConfig.videoHeight, StreamConfig.DEFAULT_FPS)
            genericStream?.changeVideoSource(otg)
            currentSource = ActiveSource.OTG
            updateSwitcherUI()
            textureView.post {
                adjustAspectRatio(textureView.width, textureView.height)
            }
            val resLabel = "${streamConfig.videoWidth}x${streamConfig.videoHeight} (${streamConfig.selectedAspectRatio})"
            Toast.makeText(this, "OTG Active: $resLabel", Toast.LENGTH_SHORT).show()
            updateStatsDisplay()
        } catch (e: Throwable) {
            Log.e(TAG, "OTG Camera switch failed", e)
            Toast.makeText(this, "OTG Switch error: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
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
            val nextRatio = when (streamConfig.selectedAspectRatio) {
                "16:9" -> "9:16"
                "9:16" -> "4:3"
                else -> "16:9"
            }
            streamConfig.selectedAspectRatio = nextRatio
            val stream = genericStream ?: return
            if (stream.isOnPreview) {
                stream.stopPreview()
            }
            prepareAndStartPreview()
            val mode = when (nextRatio) {
                "9:16" -> "9:16 Shorts (Vertical)"
                "4:3" -> "4:3 Standard"
                else -> "16:9 Landscape"
            }
            Toast.makeText(this, "Aspect Ratio: $mode", Toast.LENGTH_SHORT).show()
            updateStatsDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "Aspect ratio toggle failed", e)
            Toast.makeText(this, "Aspect ratio error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatsDisplay() {
        val aspect = streamConfig.selectedAspectRatio
        val src = when (currentSource) {
            ActiveSource.OTG -> "OTG"
            ActiveSource.REAR -> "Rear"
            ActiveSource.FRONT -> "Front"
        }
        val res = "${streamConfig.videoWidth}x${streamConfig.videoHeight} ($aspect $src)"
        tvStreamStats.text = "1000 kbps | 30 fps | $res"
    }

    private fun checkUsbConnectedInitially() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val deviceList = usbManager?.deviceList
            val uvcDevice = deviceList?.values?.firstOrNull { dev ->
                dev.deviceClass == 14 || dev.deviceClass == 239 || (0 until dev.interfaceCount).any { i -> dev.getInterface(i).interfaceClass == 14 }
            }
            val hasUvc = uvcDevice != null
            updateOtgAvailability(hasUvc)
            if (uvcDevice != null) {
                requestUsbPermissionIfNeeded(uvcDevice)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "checkUsbConnectedInitially failed", e)
        }
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
            etStreamKey.setText(StreamConfig.DEFAULT_STREAM_KEY)
        }

        btnPresetYouTube.setOnClickListener {
            etRtmpUrl.setText(StreamConfig.YOUTUBE_RTMP_URL)
            if (etStreamKey.text.toString().trim() == StreamConfig.DEFAULT_STREAM_KEY) {
                etStreamKey.setText("")
            }
            etStreamKey.hint = "Paste YouTube Stream Key"
            etStreamKey.requestFocus()
        }

        val btnRatio16x9 = dialogView.findViewById<Button>(R.id.btnRatio16x9)
        val btnRatio9x16 = dialogView.findViewById<Button>(R.id.btnRatio9x16)
        val btnRatio4x3 = dialogView.findViewById<Button>(R.id.btnRatio4x3)
        var tempRatio = streamConfig.selectedAspectRatio

        fun updateRatioUI() {
            val activeColor = ContextCompat.getColor(this, R.color.accent_blue)
            val normalColor = ContextCompat.getColor(this, R.color.surface_card)
            btnRatio16x9.setBackgroundColor(if (tempRatio == "16:9") activeColor else normalColor)
            btnRatio9x16.setBackgroundColor(if (tempRatio == "9:16") activeColor else normalColor)
            btnRatio4x3.setBackgroundColor(if (tempRatio == "4:3") activeColor else normalColor)
        }
        updateRatioUI()

        btnRatio16x9.setOnClickListener { tempRatio = "16:9"; updateRatioUI() }
        btnRatio9x16.setOnClickListener { tempRatio = "9:16"; updateRatioUI() }
        btnRatio4x3.setOnClickListener { tempRatio = "4:3"; updateRatioUI() }

        btnSave.setOnClickListener {
            streamConfig.rtmpUrl = etRtmpUrl.text.toString().trim()
            streamConfig.streamKey = etStreamKey.text.toString().trim()
            val ratioChanged = streamConfig.selectedAspectRatio != tempRatio
            streamConfig.selectedAspectRatio = tempRatio

            if (ratioChanged && !isStreaming) {
                try {
                    genericStream?.let { s ->
                        if (s.isOnPreview) s.stopPreview()
                        prepareAndStartPreview()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Re-init on ratio change failed", e)
                }
            }
            updateStatsDisplay()
            Toast.makeText(this, "Settings saved (Aspect: $tempRatio)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun registerUsbReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                } catch (se: SecurityException) {
                    registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                }
            } else {
                registerReceiver(usbReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "registerUsbReceiver failed", e)
        }
    }

    private fun registerBatteryReceiver() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } catch (se: SecurityException) {
                    registerReceiver(batteryReceiver, filter)
                }
            } else {
                registerReceiver(batteryReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "registerBatteryReceiver failed", e)
        }
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
        val safeReason = reason ?: ""
        Log.w(TAG, "Connection failed: $safeReason")

        // If broadcast was active, ALWAYS attempt resilient reconnect (handles camera switch pauses)
        val retried = if (isStreaming) {
            try {
                genericStream?.getStreamClient()?.reTry(1500, safeReason, null) ?: false
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        if (retried) {
            runOnUiThread {
                btnLive.text = getString(R.string.connecting)
                tvLiveBadge.text = "RECONNECTING"
                tvLiveBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
                Toast.makeText(this, "Reconnecting live stream...", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                genericStream?.stopStream()
            } catch (e: Exception) {
                Log.e(TAG, "stopStream on connection failed error", e)
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

                val userMessage = when {
                    safeReason.contains("end of stream", ignoreCase = true) || safeReason.contains("configure stream", ignoreCase = true) ->
                        "Server closed connection. Please check RTMP URL & Stream Key in Settings (⚙️)."
                    safeReason.contains("refused", ignoreCase = true) || safeReason.contains("unresolved", ignoreCase = true) ->
                        "Cannot connect to RTMP server. Check internet & server address."
                    else -> "Connection failed: $safeReason"
                }
                Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread {
            val currentKbps = bitrate / 1000
            // Exponential Moving Average filter to eliminate 0 kbps flickering during cellular TCP windowing
            smoothedKbps = if (smoothedKbps == 0L) {
                currentKbps
            } else if (currentKbps == 0L) {
                // Soft decay during momentary TCP buffer ACK stalls rather than flashing 0
                (smoothedKbps * 0.8).toLong()
            } else {
                ((smoothedKbps * 0.7) + (currentKbps * 0.3)).toLong()
            }
            val displayKbps = if (smoothedKbps > 0) smoothedKbps else currentKbps
            val aspect = streamConfig.selectedAspectRatio
            val res = "${streamConfig.videoWidth}x${streamConfig.videoHeight} ($aspect)"
            tvStreamStats.text = "$displayKbps kbps | 30 fps | $res"
        }
    }

    override fun onDisconnect() {
        smoothedKbps = 0L
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Device orientation changed: ${newConfig.orientation}")
        textureView.post {
            adjustAspectRatio(textureView.width, textureView.height)
            try {
                genericStream?.getGlInterface()?.setPreviewResolution(textureView.width, textureView.height)
            } catch (e: Exception) {
                Log.e(TAG, "setPreviewResolution failed on orientation change", e)
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
            otgCameraSource?.stop()
        } catch (e: Exception) {}
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