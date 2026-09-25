package com.streamezy.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.streamezy.capture.monitoring.MobileTelemetryManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    private lateinit var tvAppVersion: TextView
    private lateinit var onlineUpdateManager: com.streamezy.capture.update.OnlineUpdateManager
    private lateinit var tvUptime: TextView
    private lateinit var headerRow2: LinearLayout
    private lateinit var tvStreamStatsPortrait: TextView
    private lateinit var tvBatteryStatusPortrait: TextView
    private lateinit var tvStreamStatsLandscape: TextView
    private lateinit var tvBatteryStatusLandscape: TextView
    private lateinit var layoutRotateHint: LinearLayout
    private lateinit var btnAudioControl: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var layoutAudioPanel: CardView
    private lateinit var btnAudioClose: ImageButton
    private lateinit var switchMuteLive: SwitchCompat
    private lateinit var tvMuteHint: TextView
    private lateinit var tvMicVolumeLabel: TextView
    private lateinit var seekMicVolume: SeekBar
    private lateinit var tvNoiseReductionValue: TextView
    private lateinit var seekNoiseReduction: SeekBar
    private lateinit var btnSelectAudio: Button
    private lateinit var tvSelectedAudioName: TextView
    private lateinit var tvAudioStatus: TextView
    private lateinit var tvAudioProgress: TextView
    private lateinit var btnPlayAudio: Button
    private lateinit var btnPauseAudio: Button
    private lateinit var btnStopAudio: Button
    private lateinit var btnPreviewAudio: Button
    private lateinit var cbMonitorAudio: CheckBox
    private lateinit var tvBgVolumeLabel: TextView
    private lateinit var seekBgVolume: SeekBar
    private lateinit var cbLoopAudio: CheckBox

    private var selectedAudioUri: Uri? = null
    private var previewPlayer: MediaPlayer? = null
    private var isLocalPreviewing: Boolean = false
    private val audioProgressHandler = Handler(Looper.getMainLooper())
    private val audioProgressRunnable = object : Runnable {
        override fun run() {
            updateAudioProgressTick()
            if (::pbAudioLevel.isInitialized) {
                pbAudioLevel.progress = audioProcessor.audioLevel
            }
            audioProgressHandler.postDelayed(this, 60)
        }
    }

    private lateinit var layoutAudioStatusPanel: LinearLayout
    private lateinit var tvAudioLiveStatus: TextView
    private lateinit var btnMasterMute: Button
    private lateinit var btnCloseAudioStatusPanel: ImageButton
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var btnShowAudioStatusPanel: LinearLayout
    private lateinit var btnDeleteAudio: ImageButton
    private var isAudioStatusPanelMinimized = false
    private lateinit var tileSourceMobile: LinearLayout
    private lateinit var tvSourceMobileBadge: TextView
    private lateinit var tileSourceExternal: LinearLayout
    private lateinit var tvSourceExternalBadge: TextView
    private lateinit var tileSourceCustom: LinearLayout
    private lateinit var tvSourceCustomBadge: TextView
    private lateinit var tvMasterVolumeLabel: TextView
    private lateinit var btnMasterVolDown: Button
    private lateinit var seekMasterVolume: SeekBar
    private lateinit var btnMasterVolUp: Button

    private var isExternalAudioConnected = false
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkUsbAudioState()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkUsbAudioState()
        }
    }

    private lateinit var tileRearCam: LinearLayout
    private lateinit var tileFrontCam: LinearLayout
    private lateinit var tileOtgCam: LinearLayout
    private lateinit var tvRearLabel: TextView
    private lateinit var tvFrontLabel: TextView
    private lateinit var tvOtgLabel: TextView
    private lateinit var ivOtgIcon: ImageView
    private lateinit var btnLive: Button

    private val audioProcessor = AudioProcessor()

    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedAudioUri = uri
            val fileName = AudioDecoder.getFileName(this, uri)
            tvSelectedAudioName.text = "Decoding: $fileName..."
            lifecycleScope.launch {
                try {
                    val pcmData = AudioDecoder.decodeToPcm(this@MainActivity, uri)
                    audioProcessor.setBackgroundAudio(pcmData)
                    val seconds = pcmData.size / 44100
                    tvSelectedAudioName.text = "🎵 $fileName (${seconds / 60}m ${seconds % 60}s)"
                    if (::btnDeleteAudio.isInitialized) {
                        btnDeleteAudio.visibility = View.VISIBLE
                    }
                    updatePlaybackUIState(isPlaying = true, isPaused = false)
                    updateAudioStatusPanelUI()
                    Toast.makeText(this@MainActivity, "Audio loaded & ready to stream!", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Log.e(TAG, "Audio loading failed", e)
                    tvSelectedAudioName.text = "Failed: ${e.localizedMessage ?: "Unknown error"}"
                    if (::btnDeleteAudio.isInitialized) {
                        btnDeleteAudio.visibility = View.GONE
                    }
                    Toast.makeText(this@MainActivity, "Audio decode error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private lateinit var streamConfig: StreamConfig
    private var genericStream: GenericStream? = null
    private lateinit var camera2Source: Camera2Source
    private lateinit var microphoneSource: MicrophoneSource
    private var otgCameraSource: OtgCameraSource? = null

    // BondStream Multi-Network Bonding
    private var bondSession: com.streamezy.capture.bonding.BondSession? = null
    private var bondRtmpProxy: com.streamezy.capture.bonding.BondRtmpProxy? = null
    private lateinit var headerRowBonding: LinearLayout
    private lateinit var tvBondingBadge: TextView
    private lateinit var tvBondingNetworks: TextView
    private lateinit var tvBondingSimNetworks: TextView
    private lateinit var tvBondingMetrics: TextView
    private lateinit var btnNetworkCenter: ImageButton
    private var previewNetworkManager: com.streamezy.capture.bonding.AndroidNetworkManager? = null

    private enum class ActiveSource { REAR, FRONT, OTG }
    private var currentSource = ActiveSource.REAR

    // VPS Device Monitoring & Telemetry
    private lateinit var telemetryManager: MobileTelemetryManager

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

                // Dispatch stream telemetry to VPS monitoring
                if (::telemetryManager.isInitialized) {
                    val kbps = smoothedKbps.toInt()
                    telemetryManager.sendStreamTelemetry(
                        fps = 30f,
                        videoBitrate = if (kbps > 10) kbps * 1000 else streamConfig.videoBitrate,
                        audioBitrate = StreamConfig.DEFAULT_AUDIO_BITRATE
                    )
                }

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
                updateBatteryDisplay("⚡ $batteryPct% (Charging)", ContextCompat.getColor(context, R.color.accent_green))
            } else {
                val color = ContextCompat.getColor(
                    context,
                    if (batteryPct <= 20) R.color.accent_red else R.color.text_primary
                )
                updateBatteryDisplay("🔋 $batteryPct%", color)
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

            // Start VPS Device Monitoring
            telemetryManager = MobileTelemetryManager(this, streamConfig.bondingServerHost, 8080)
            telemetryManager.cameraStatus = "preview"
            telemetryManager.start()

            initViews()

            // Initialize Online Update System & Version Display
            onlineUpdateManager = com.streamezy.capture.update.OnlineUpdateManager(this)
            tvAppVersion.text = "StreamEzy v${onlineUpdateManager.getActiveVersion()}"

            // Online File & Version Update Check (Non-blocking background async)
            onlineUpdateManager.checkAndUpdateAsync(
                onStatusUpdate = { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                },
                onVersionUpdated = { newVer, _ ->
                    tvAppVersion.text = "StreamEzy v$newVer"
                    streamConfig.loadRemoteConfigOverrides()
                    Toast.makeText(this, "StreamEzy updated to v$newVer", Toast.LENGTH_SHORT).show()
                }
            )

            setupListeners()
            registerUsbReceiver()
            registerBatteryReceiver()
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "registerAudioDeviceCallback failed", e)
            }

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
        tvAppVersion = findViewById(R.id.tvAppVersion)
        tvUptime = findViewById(R.id.tvUptime)
        headerRow2 = findViewById(R.id.headerRow2)
        tvStreamStatsPortrait = findViewById(R.id.tvStreamStatsPortrait)
        tvBatteryStatusPortrait = findViewById(R.id.tvBatteryStatusPortrait)
        tvStreamStatsLandscape = findViewById(R.id.tvStreamStatsLandscape)
        tvBatteryStatusLandscape = findViewById(R.id.tvBatteryStatusLandscape)
        layoutRotateHint = findViewById(R.id.layoutRotateHint)
        btnAudioControl = findViewById(R.id.btnAudioControl)
        btnSettings = findViewById(R.id.btnSettings)
        layoutAudioPanel = findViewById(R.id.layoutAudioPanel)
        btnAudioClose = findViewById(R.id.btnAudioClose)
        switchMuteLive = findViewById(R.id.switchMuteLive)
        tvMuteHint = findViewById(R.id.tvMuteHint)
        tvMicVolumeLabel = findViewById(R.id.tvMicVolumeLabel)
        seekMicVolume = findViewById(R.id.seekMicVolume)
        tvNoiseReductionValue = findViewById(R.id.tvNoiseReductionValue)
        seekNoiseReduction = findViewById(R.id.seekNoiseReduction)
        btnSelectAudio = findViewById(R.id.btnSelectAudio)
        tvSelectedAudioName = findViewById(R.id.tvSelectedAudioName)
        tvAudioStatus = findViewById(R.id.tvAudioStatus)
        tvAudioProgress = findViewById(R.id.tvAudioProgress)
        btnPlayAudio = findViewById(R.id.btnPlayAudio)
        btnPauseAudio = findViewById(R.id.btnPauseAudio)
        btnStopAudio = findViewById(R.id.btnStopAudio)
        btnPreviewAudio = findViewById(R.id.btnPreviewAudio)
        cbMonitorAudio = findViewById(R.id.cbMonitorAudio)
        tvBgVolumeLabel = findViewById(R.id.tvBgVolumeLabel)
        seekBgVolume = findViewById(R.id.seekBgVolume)
        cbLoopAudio = findViewById(R.id.cbLoopAudio)

        tileRearCam = findViewById(R.id.tileRearCam)
        tileFrontCam = findViewById(R.id.tileFrontCam)
        tileOtgCam = findViewById(R.id.tileOtgCam)
        tvRearLabel = findViewById(R.id.tvRearLabel)
        tvFrontLabel = findViewById(R.id.tvFrontLabel)
        tvOtgLabel = findViewById(R.id.tvOtgLabel)
        ivOtgIcon = findViewById(R.id.ivOtgIcon)
        btnLive = findViewById(R.id.btnLive)

        // Compact Audio Status Panel on Home Screen
        layoutAudioStatusPanel = findViewById(R.id.layoutAudioStatusPanel)
        tvAudioLiveStatus = findViewById(R.id.tvAudioLiveStatus)
        btnMasterMute = findViewById(R.id.btnMasterMute)
        tileSourceMobile = findViewById(R.id.tileSourceMobile)
        tvSourceMobileBadge = findViewById(R.id.tvSourceMobileBadge)
        tileSourceExternal = findViewById(R.id.tileSourceExternal)
        tvSourceExternalBadge = findViewById(R.id.tvSourceExternalBadge)
        tileSourceCustom = findViewById(R.id.tileSourceCustom)
        tvSourceCustomBadge = findViewById(R.id.tvSourceCustomBadge)
        tvMasterVolumeLabel = findViewById(R.id.tvMasterVolumeLabel)
        btnMasterVolDown = findViewById(R.id.btnMasterVolDown)
        seekMasterVolume = findViewById(R.id.seekMasterVolume)
        btnMasterVolUp = findViewById(R.id.btnMasterVolUp)
        pbAudioLevel = findViewById(R.id.pbAudioLevel)
        btnCloseAudioStatusPanel = findViewById(R.id.btnCloseAudioStatusPanel)
        btnShowAudioStatusPanel = findViewById(R.id.btnShowAudioStatusPanel)
        btnDeleteAudio = findViewById(R.id.btnDeleteAudio)

        // BondStream Live Status Views
        headerRowBonding = findViewById(R.id.headerRowBonding)
        tvBondingBadge = findViewById(R.id.tvBondingBadge)
        tvBondingNetworks = findViewById(R.id.tvBondingNetworks)
        tvBondingSimNetworks = findViewById(R.id.tvBondingSimNetworks)
        tvBondingMetrics = findViewById(R.id.tvBondingMetrics)
        btnNetworkCenter = findViewById(R.id.btnNetworkCenter)

        // Persistent preview network manager for concurrent Wi-Fi + Cellular detection
        previewNetworkManager = com.streamezy.capture.bonding.AndroidNetworkManager(this).apply {
            onPathsChanged = { _ ->
                runOnUiThread {
                    if (!isStreaming) {
                        updateNetworkStatusPreview()
                    }
                }
            }
            startDiscovery()
        }

        updateOrientationHint(resources.configuration.orientation)
        updateHeaderOrientation(resources.configuration.orientation)
        checkUsbConnectedInitially()
        checkUsbAudioState()
        updateAudioStatusPanelUI()
        updateNetworkStatusPreview()
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

        btnNetworkCenter.setOnClickListener {
            showNetworkCenterDialog()
        }

        headerRowBonding.setOnClickListener {
            showNetworkCenterDialog()
        }

        btnAudioControl.setOnClickListener {
            toggleAudioPanel()
        }

        btnAudioClose.setOnClickListener {
            layoutAudioPanel.visibility = View.GONE
        }

        switchMuteLive.setOnCheckedChangeListener { _, isChecked ->
            audioProcessor.isLiveMuted = isChecked
            if (isChecked) {
                tvMuteHint.text = "Live mic MUTED — custom background audio plays cleanly"
                tvMuteHint.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                Toast.makeText(this, "Live mic muted: background audio plays cleanly", Toast.LENGTH_SHORT).show()
            } else {
                tvMuteHint.text = "Mic active (mixed with background audio)"
                tvMuteHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                Toast.makeText(this, "Live mic active", Toast.LENGTH_SHORT).show()
            }
        }

        seekMicVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioProcessor.micVolume = progress / 100f
                tvMicVolumeLabel.text = "Mic Gain: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekNoiseReduction.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioProcessor.noiseReductionPercent = progress
                tvNoiseReductionValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSelectAudio.setOnClickListener {
            try {
                audioPickerLauncher.launch("audio/*")
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open audio picker: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        btnPlayAudio.setOnClickListener {
            if (!audioProcessor.hasBackgroundAudio()) {
                Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show()
                audioPickerLauncher.launch("audio/*")
            } else {
                audioProcessor.playBackground()
                updatePlaybackUIState(isPlaying = true, isPaused = false)
                if (cbMonitorAudio.isChecked) {
                    startDeviceMonitor()
                }
                Toast.makeText(this, "Background audio playing to stream 🔊", Toast.LENGTH_SHORT).show()
            }
        }

        btnPauseAudio.setOnClickListener {
            audioProcessor.pauseBackground()
            pauseDeviceMonitor()
            updatePlaybackUIState(isPlaying = false, isPaused = true)
            Toast.makeText(this, "Background audio paused", Toast.LENGTH_SHORT).show()
        }

        btnStopAudio.setOnClickListener {
            audioProcessor.stopBackground()
            stopDeviceMonitor()
            updatePlaybackUIState(isPlaying = false, isPaused = false)
            Toast.makeText(this, "Background audio stopped", Toast.LENGTH_SHORT).show()
        }

        btnPreviewAudio.setOnClickListener {
            toggleLocalPreview()
        }

        cbMonitorAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && audioProcessor.isPlaying) {
                startDeviceMonitor()
            } else if (!isChecked) {
                stopDeviceMonitor()
            }
        }

        seekBgVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val vol = progress / 100f
                audioProcessor.bgVolume = vol
                previewPlayer?.setVolume(vol, vol)
                tvBgVolumeLabel.text = "Music Volume: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cbLoopAudio.setOnCheckedChangeListener { _, isChecked ->
            audioProcessor.isLooping = isChecked
            previewPlayer?.isLooping = isChecked
        }

        tileRearCam.setOnClickListener { selectRearCamera() }
        tileFrontCam.setOnClickListener { selectFrontCamera() }
        tileOtgCam.setOnClickListener { selectOtgCamera() }

        btnSettings.setOnClickListener { showSettingsDialog() }

        tileSourceMobile.setOnClickListener { selectAudioSource(AudioSourceType.MOBILE) }
        tileSourceExternal.setOnClickListener { selectAudioSource(AudioSourceType.EXTERNAL) }
        tileSourceCustom.setOnClickListener { selectAudioSource(AudioSourceType.CUSTOM) }

        btnMasterMute.setOnClickListener { toggleMasterMute() }

        seekMasterVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioProcessor.masterVolume = progress / 100f
                updateAudioStatusPanelUI()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnMasterVolDown.setOnClickListener {
            val p = (seekMasterVolume.progress - 5).coerceAtLeast(0)
            seekMasterVolume.progress = p
        }

        btnMasterVolUp.setOnClickListener {
            val p = (seekMasterVolume.progress + 5).coerceAtMost(100)
            seekMasterVolume.progress = p
        }

        btnCloseAudioStatusPanel.setOnClickListener {
            isAudioStatusPanelMinimized = true
            layoutAudioStatusPanel.visibility = View.GONE
            btnShowAudioStatusPanel.visibility = View.VISIBLE
        }

        btnShowAudioStatusPanel.setOnClickListener {
            isAudioStatusPanelMinimized = false
            layoutAudioStatusPanel.visibility = View.VISIBLE
            btnShowAudioStatusPanel.visibility = View.GONE
        }

        btnDeleteAudio.setOnClickListener {
            clearBackgroundAudioFile()
        }
    }

    private fun clearBackgroundAudioFile() {
        audioProcessor.clearBackgroundAudio()
        stopLocalPreview()
        selectedAudioUri = null
        tvSelectedAudioName.text = "No audio file chosen"
        btnDeleteAudio.visibility = View.GONE
        updatePlaybackUIState(isPlaying = false, isPaused = false)
        if (audioProcessor.activeSource == AudioSourceType.CUSTOM) {
            audioProcessor.activeSource = AudioSourceType.MOBILE
            routeAudioToBuiltinMic()
        }
        updateAudioStatusPanelUI()
        Toast.makeText(this, "Background audio removed", Toast.LENGTH_SHORT).show()
    }

    private fun checkUsbAudioState() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val hasUsb = inputs.any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val changed = isExternalAudioConnected != hasUsb
        isExternalAudioConnected = hasUsb
        runOnUiThread {
            if (changed) {
                if (!hasUsb && audioProcessor.activeSource == AudioSourceType.EXTERNAL) {
                    audioProcessor.activeSource = AudioSourceType.MOBILE
                    routeAudioToBuiltinMic()
                    Toast.makeText(this@MainActivity, "🔌 External Audio disconnected. Switched to Mobile Audio.", Toast.LENGTH_LONG).show()
                } else if (hasUsb) {
                    Toast.makeText(this@MainActivity, "🔌 External USB Audio detected!", Toast.LENGTH_SHORT).show()
                }
            }
            updateAudioStatusPanelUI()
        }
    }

    private fun selectAudioSource(type: AudioSourceType) {
        when (type) {
            AudioSourceType.MOBILE -> {
                audioProcessor.activeSource = AudioSourceType.MOBILE
                routeAudioToBuiltinMic()
                if (audioProcessor.isPlaying) {
                    audioProcessor.pauseBackground()
                    updatePlaybackUIState(isPlaying = false, isPaused = true)
                }
                updateAudioStatusPanelUI()
                Toast.makeText(this, "Active: 🎙️ Mobile Audio", Toast.LENGTH_SHORT).show()
            }
            AudioSourceType.EXTERNAL -> {
                if (!isExternalAudioConnected) {
                    Toast.makeText(this, "🔌 External USB/OTG Audio Disconnected. Plug in USB mic or capture card.", Toast.LENGTH_LONG).show()
                    return
                }
                audioProcessor.activeSource = AudioSourceType.EXTERNAL
                routeAudioToUsbDevice()
                if (audioProcessor.isPlaying) {
                    audioProcessor.pauseBackground()
                    updatePlaybackUIState(isPlaying = false, isPaused = true)
                }
                updateAudioStatusPanelUI()
                Toast.makeText(this, "Active: 🔌 External USB/OTG Audio", Toast.LENGTH_SHORT).show()
            }
            AudioSourceType.CUSTOM -> {
                if (!audioProcessor.hasBackgroundAudio()) {
                    Toast.makeText(this, "Select an audio track first for Custom Audio", Toast.LENGTH_SHORT).show()
                    audioPickerLauncher.launch("audio/*")
                    return
                }
                audioProcessor.activeSource = AudioSourceType.CUSTOM
                audioProcessor.playBackground()
                updatePlaybackUIState(isPlaying = true, isPaused = false)
                updateAudioStatusPanelUI()
                Toast.makeText(this, "Active: 🎵 Custom Audio (Mic Muted)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun routeAudioToBuiltinMic() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        } catch (e: Exception) {
            Log.w(TAG, "routeAudioToBuiltinMic error", e)
        }
    }

    private fun routeAudioToUsbDevice() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val usbDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (usbDevice != null) {
                    audioManager.setCommunicationDevice(usbDevice)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "routeAudioToUsbDevice error", e)
        }
    }

    private fun toggleMasterMute() {
        audioProcessor.isMasterMuted = !audioProcessor.isMasterMuted
        updateAudioStatusPanelUI()
        if (audioProcessor.isMasterMuted) {
            Toast.makeText(this, "🔇 Master Output MUTED (Microphone stays connected)", Toast.LENGTH_SHORT).show()
        } else {
            val pct = if (::seekMasterVolume.isInitialized) seekMasterVolume.progress else 75
            Toast.makeText(this, "🔊 Master Output UNMUTED ($pct%)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAudioStatusPanelUI() {
        if (!::tvAudioLiveStatus.isInitialized) return
        runOnUiThread {
            val src = audioProcessor.activeSource
            val isMuted = audioProcessor.isMasterMuted
            val volPct = if (::seekMasterVolume.isInitialized) seekMasterVolume.progress else (audioProcessor.masterVolume * 100).toInt()

            // 1. Mobile Pill
            if (src == AudioSourceType.MOBILE) {
                tileSourceMobile.setBackgroundResource(R.drawable.bg_audio_source_active)
                tvSourceMobileBadge.text = "ON"
                tvSourceMobileBadge.setBackgroundColor(Color.parseColor("#10B981"))
            } else {
                tileSourceMobile.setBackgroundResource(R.drawable.bg_audio_source_inactive)
                tvSourceMobileBadge.text = "OFF"
                tvSourceMobileBadge.setBackgroundColor(Color.parseColor("#4B5563"))
            }

            // 2. External Pill
            if (!isExternalAudioConnected) {
                tileSourceExternal.setBackgroundResource(R.drawable.bg_audio_source_disconnected)
                tvSourceExternalBadge.text = "DISC"
                tvSourceExternalBadge.setBackgroundColor(Color.parseColor("#D97706"))
            } else if (src == AudioSourceType.EXTERNAL) {
                tileSourceExternal.setBackgroundResource(R.drawable.bg_audio_source_active)
                tvSourceExternalBadge.text = "ON"
                tvSourceExternalBadge.setBackgroundColor(Color.parseColor("#10B981"))
            } else {
                tileSourceExternal.setBackgroundResource(R.drawable.bg_audio_source_inactive)
                tvSourceExternalBadge.text = "OFF"
                tvSourceExternalBadge.setBackgroundColor(Color.parseColor("#4B5563"))
            }

            // 3. Custom Pill
            if (!audioProcessor.hasBackgroundAudio()) {
                tileSourceCustom.setBackgroundResource(R.drawable.bg_audio_source_inactive)
                tvSourceCustomBadge.text = "NO FILE"
                tvSourceCustomBadge.setBackgroundColor(Color.parseColor("#4B5563"))
            } else if (src == AudioSourceType.CUSTOM) {
                tileSourceCustom.setBackgroundResource(R.drawable.bg_audio_source_active)
                tvSourceCustomBadge.text = "ON"
                tvSourceCustomBadge.setBackgroundColor(Color.parseColor("#10B981"))
            } else {
                tileSourceCustom.setBackgroundResource(R.drawable.bg_audio_source_inactive)
                tvSourceCustomBadge.text = "OFF"
                tvSourceCustomBadge.setBackgroundColor(Color.parseColor("#4B5563"))
            }

            // 4. Master Mute Button & Volume Label
            if (isMuted) {
                btnMasterMute.text = "🔊 UNMUTE"
                btnMasterMute.setBackgroundColor(Color.parseColor("#DC2626"))
                tvMasterVolumeLabel.text = "🔇 MUTED"
            } else {
                btnMasterMute.text = "🔇 MUTE"
                btnMasterMute.setBackgroundColor(Color.parseColor("#374151"))
                tvMasterVolumeLabel.text = "🔊 Master: $volPct%"
            }

            // 5. Live Status Readout
            val prefix = if (isStreaming) "● LIVE   " else "● READY  "
            val srcName = when (src) {
                AudioSourceType.MOBILE -> "🎙️ Mobile Audio"
                AudioSourceType.EXTERNAL -> if (isExternalAudioConnected) "🔌 External Audio" else "🔌 Ext (Disconnected)"
                AudioSourceType.CUSTOM -> "🎵 Custom Audio"
            }
            val volStatus = if (isMuted) "🔇 MUTED" else "🔊 $volPct%"
            tvAudioLiveStatus.text = "$prefix$srcName   $volStatus"
            tvAudioLiveStatus.setTextColor(
                if (isStreaming) ContextCompat.getColor(this, R.color.accent_red)
                else ContextCompat.getColor(this, R.color.accent_green)
            )
        }
    }

    private fun toggleAudioPanel() {
        if (::layoutAudioPanel.isInitialized) {
            layoutAudioPanel.visibility = if (layoutAudioPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }

    private fun updateHeaderOrientation(orientation: Int) {
        if (::headerRow2.isInitialized) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                headerRow2.visibility = View.GONE
                tvStreamStatsLandscape.visibility = View.VISIBLE
                tvBatteryStatusLandscape.visibility = View.VISIBLE
                // In horizontal (landscape) mode, minimize audio controls to keep 16:9 preview fully unobstructed
                if (::layoutAudioStatusPanel.isInitialized && ::btnShowAudioStatusPanel.isInitialized) {
                    layoutAudioStatusPanel.visibility = View.GONE
                    btnShowAudioStatusPanel.visibility = View.VISIBLE
                }
            } else {
                headerRow2.visibility = View.VISIBLE
                tvStreamStatsLandscape.visibility = View.GONE
                tvBatteryStatusLandscape.visibility = View.GONE
                // In vertical (portrait) mode, restore if not manually minimized
                if (::layoutAudioStatusPanel.isInitialized && ::btnShowAudioStatusPanel.isInitialized) {
                    if (!isAudioStatusPanelMinimized) {
                        layoutAudioStatusPanel.visibility = View.VISIBLE
                        btnShowAudioStatusPanel.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updatePlaybackUIState(isPlaying: Boolean, isPaused: Boolean) {
        runOnUiThread {
            if (isPlaying) {
                btnPlayAudio.text = "● PLAYING NOW"
                btnPlayAudio.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
                tvAudioStatus.text = "🔊 Playing to Stream (Broadcasting)"
                tvAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                btnPauseAudio.isEnabled = true
                btnStopAudio.isEnabled = true
            } else if (isPaused) {
                btnPlayAudio.text = "▶ Resume Play"
                btnPlayAudio.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
                tvAudioStatus.text = "⏸ Paused"
                tvAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.border_active))
                btnPauseAudio.isEnabled = false
                btnStopAudio.isEnabled = true
            } else {
                btnPlayAudio.text = "▶ Play to Stream"
                btnPlayAudio.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
                tvAudioStatus.text = "⏹ Stopped (Not playing)"
                tvAudioStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnPauseAudio.isEnabled = false
                btnStopAudio.isEnabled = false
            }
        }
    }

    private fun updateAudioProgressTick() {
        if (audioProcessor.isPlaying) {
            val cur = audioProcessor.currentPositionSeconds
            val tot = audioProcessor.totalDurationSeconds
            val curM = cur / 60
            val curS = cur % 60
            val totM = tot / 60
            val totS = tot % 60
            tvAudioProgress.text = String.format("%02d:%02d / %02d:%02d", curM, curS, totM, totS)
        } else if (!audioProcessor.hasBackgroundAudio()) {
            tvAudioProgress.text = "00:00 / 00:00"
        }
    }

    private fun toggleLocalPreview() {
        val uri = selectedAudioUri
        if (uri == null) {
            Toast.makeText(this, "Select an audio file first to preview", Toast.LENGTH_SHORT).show()
            audioPickerLauncher.launch("audio/*")
            return
        }

        if (isLocalPreviewing) {
            stopLocalPreview()
            Toast.makeText(this, "Audio preview stopped", Toast.LENGTH_SHORT).show()
        } else {
            startLocalPreview(uri)
            Toast.makeText(this, "Playing preview on phone speaker 🎧", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocalPreview(uri: Uri) {
        try {
            previewPlayer?.release()
            previewPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setVolume(audioProcessor.bgVolume, audioProcessor.bgVolume)
                isLooping = audioProcessor.isLooping
                setOnCompletionListener {
                    isLocalPreviewing = false
                    btnPreviewAudio.text = "🎧 Preview on Phone"
                }
                prepare()
                start()
            }
            isLocalPreviewing = true
            btnPreviewAudio.text = "⏹ Stop Preview"
        } catch (e: Exception) {
            Log.e(TAG, "startLocalPreview failed", e)
            Toast.makeText(this, "Preview failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLocalPreview() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        } catch (e: Exception) {}
        isLocalPreviewing = false
        btnPreviewAudio.text = "🎧 Preview on Phone"
    }

    private fun startDeviceMonitor() {
        val uri = selectedAudioUri ?: return
        try {
            if (previewPlayer == null) {
                previewPlayer = MediaPlayer().apply {
                    setDataSource(this@MainActivity, uri)
                    setVolume(audioProcessor.bgVolume, audioProcessor.bgVolume)
                    isLooping = audioProcessor.isLooping
                    prepare()
                }
            }
            previewPlayer?.setVolume(audioProcessor.bgVolume, audioProcessor.bgVolume)
            previewPlayer?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Device monitor play failed", e)
        }
    }

    private fun pauseDeviceMonitor() {
        try {
            previewPlayer?.pause()
        } catch (e: Exception) {}
    }

    private fun stopDeviceMonitor() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        } catch (e: Exception) {}
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
            microphoneSource.setAudioEffect(audioProcessor)
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

        val targetRatio: Float = 16f / 9f

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
                    streamConfig.videoBitrate,
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
                    streamConfig.videoBitrate,
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

            // Multi-Path Cellular Bonding vs Direct RTMP
            val targetUrl: String
            if (streamConfig.isBondingEnabled) {
                Log.i(TAG, "BondStream Multi-Path Bonding ENABLED. Starting local loopback proxy...")
                if (::headerRowBonding.isInitialized) {
                    headerRowBonding.visibility = View.VISIBLE
                    tvBondingBadge.text = "CONNECTING"
                    tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_card))
                    tvBondingMetrics.text = "Initializing multi-path UDP..."
                }

                val token = streamConfig.bondingAuthToken.ifBlank { streamConfig.streamKey }
                val session = com.streamezy.capture.bonding.BondSession(
                    this,
                    streamConfig.bondingServerHost,
                    streamConfig.bondingServerPort,
                    token,
                    streamConfig.playoutDelayMs,
                    streamConfig.enableArq,
                    streamConfig.enableRedundancy,
                    streamConfig.enableFec,
                    streamConfig.fecBlockSize
                ).apply {
                    mode = com.streamezy.capture.bonding.BondingMode.ON
                    onRecommendedBitrate = { targetBitrateKbps ->
                        runOnUiThread {
                            try {
                                genericStream?.setVideoBitrateOnFly(targetBitrateKbps * 1000)
                                Log.i(TAG, "ABR adjusted hardware encoder bitrate to $targetBitrateKbps kbps")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to adjust bitrate: ${e.message}")
                            }
                        }
                    }
                    onMetricsUpdated = { metrics ->
                        runOnUiThread {
                            updateBondingHeaderUI(metrics)
                            val statusMsg = if (metrics.isBonded) "Bonded: Wi-Fi + 4G/5G Active" else "Streaming: 1 Network Active"
                            com.streamezy.capture.service.BondStreamingService.updateStatus(
                                this@MainActivity,
                                statusMsg,
                                metrics.combinedUploadMbps
                            )
                        }
                    }
                    onAuthFailed = { reason ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Bonding Auth Failed: $reason", Toast.LENGTH_LONG).show()
                            if (streamConfig.isAutoFallbackEnabled) {
                                Toast.makeText(this@MainActivity, "Auto-falling back to Direct RTMP...", Toast.LENGTH_SHORT).show()
                                stopLiveStream()
                                streamConfig.isBondingEnabled = false
                                startLiveStream()
                            }
                        }
                    }
                    start()
                }
                bondSession = session

                val proxy = com.streamezy.capture.bonding.BondRtmpProxy(session)
                val proxyPort = proxy.start()
                bondRtmpProxy = proxy

                val key = streamConfig.streamKey.trim().ifEmpty { "live" }
                targetUrl = "rtmp://127.0.0.1:$proxyPort/live/$key"
                Log.i(TAG, "RootEncoder routing via BondStream loopback proxy: $targetUrl (LiveU playout: ${streamConfig.playoutDelayMs}ms)")
            } else {
                if (::headerRowBonding.isInitialized) {
                    headerRowBonding.visibility = View.GONE
                }
                targetUrl = endpoint
                Log.i(TAG, "Direct RTMP streaming: $targetUrl")
            }

            com.streamezy.capture.service.BondStreamingService.startService(this@MainActivity, "StreamEzy Streaming Active")
            stream.startStream(targetUrl)
        } catch (e: Throwable) {
            Log.e(TAG, "startStream failed", e)
            btnLive.isEnabled = true
            btnLive.text = getString(R.string.go_live)
            Toast.makeText(this, "Start live failed: ${e.localizedMessage ?: "Unexpected error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateBondingHeaderUI(metrics: com.streamezy.capture.bonding.BondMetrics) {
        if (!::headerRowBonding.isInitialized) return
        if (!streamConfig.isBondingEnabled) {
            headerRowBonding.visibility = View.GONE
            return
        }
        headerRowBonding.visibility = View.VISIBLE

        val onlinePaths = metrics.paths.filter { it.status == com.streamezy.capture.bonding.PathStatus.ONLINE }
        val count = onlinePaths.size

        if (metrics.isBonded) {
            tvBondingBadge.text = "BONDED ($count)"
            tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
        } else if (count > 0) {
            tvBondingBadge.text = "1 NET"
            tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
        } else {
            tvBondingBadge.text = "OFFLINE"
            tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        val wifiPath = metrics.paths.firstOrNull { it.pathId == com.streamezy.capture.bonding.AndroidNetworkManager.PATH_ID_WIFI }
        val simPath = metrics.paths.firstOrNull { it.pathId == com.streamezy.capture.bonding.AndroidNetworkManager.PATH_ID_SIM1 || it.pathId == com.streamezy.capture.bonding.AndroidNetworkManager.PATH_ID_SIM2 }

        // Line 1: Wi-Fi live TX metrics
        if (wifiPath?.status == com.streamezy.capture.bonding.PathStatus.ONLINE) {
            val ssid = if (wifiPath.carrierName.isNotBlank() && wifiPath.carrierName != "Wi-Fi") wifiPath.carrierName else "Connected"
            tvBondingNetworks.text = String.format("Wi-Fi: %s ● TX: %.1f Mbps (%dms)", ssid, wifiPath.currentUsageMbps, wifiPath.latencyMs)
        } else {
            tvBondingNetworks.text = "Wi-Fi: Disconnected"
        }

        // Line 2: SIM live TX metrics
        if (simPath?.status == com.streamezy.capture.bonding.PathStatus.ONLINE) {
            val carrier = if (simPath.carrierName.isNotBlank() && simPath.carrierName != "Carrier unavailable") simPath.carrierName else "Cellular"
            tvBondingSimNetworks.text = String.format("SIM: %s ● TX: %.1f Mbps (%dms)", carrier, simPath.currentUsageMbps, simPath.latencyMs)
        } else {
            tvBondingSimNetworks.text = "SIM: Standby / No Data"
        }

        val sentMb = metrics.totalBytesSent / (1024.0 * 1024.0)
        val arqRepaired = metrics.retransmissionsRepaired
        val fecSent = metrics.fecPacketsSent
        tvBondingMetrics.text = String.format("Avail: %.1f Mbps | Usage: %.1f Mbps | Sent: %.1f MB | %dms | ARQ: %d | FEC: %d (Zero Loss)",
            metrics.totalAvailableBandwidthMbps, metrics.totalUsageMbps, sentMb, metrics.averageLatencyMs, arqRepaired, fecSent)
    }

    private fun updateNetworkStatusPreview() {
        if (!::headerRowBonding.isInitialized) return
        if (!streamConfig.isBondingEnabled) {
            headerRowBonding.visibility = View.GONE
            return
        }
        if (isStreaming) return
        headerRowBonding.visibility = View.VISIBLE
        try {
            val netMgr = previewNetworkManager ?: com.streamezy.capture.bonding.AndroidNetworkManager(this).also {
                previewNetworkManager = it
                it.startDiscovery()
            }
            netMgr.refreshCurrentNetworks()

            val wifiPath = netMgr.paths[com.streamezy.capture.bonding.AndroidNetworkManager.PATH_ID_WIFI]
            val sim1Path = netMgr.paths[com.streamezy.capture.bonding.AndroidNetworkManager.PATH_ID_SIM1]
            val sim2Path = netMgr.paths[com.streamezy.capture.bonding.AndroidNetworkManager.PATH_ID_SIM2]

            val isWifiOnline = wifiPath?.status == com.streamezy.capture.bonding.PathStatus.ONLINE
            val isSim1Online = sim1Path?.status == com.streamezy.capture.bonding.PathStatus.ONLINE
            val isSim2Online = sim2Path?.status == com.streamezy.capture.bonding.PathStatus.ONLINE

            val onlineCount = (if (isWifiOnline) 1 else 0) + (if (isSim1Online) 1 else 0) + (if (isSim2Online) 1 else 0)

            if (onlineCount >= 2) {
                tvBondingBadge.text = "BONDED ($onlineCount)"
                tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            } else if (onlineCount == 1) {
                tvBondingBadge.text = "1 NET"
                tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            } else {
                tvBondingBadge.text = "OFFLINE"
                tvBondingBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
            }

            // Line 1: Wi-Fi details
            if (isWifiOnline && wifiPath != null) {
                val ssid = if (wifiPath.carrierName.isNotBlank() && wifiPath.carrierName != "Wi-Fi") wifiPath.carrierName else "Connected"
                tvBondingNetworks.text = String.format("Wi-Fi: %s ● Online (%.1f Mbps)", ssid, wifiPath.availableBandwidthMbps)
            } else {
                tvBondingNetworks.text = "Wi-Fi: Disconnected"
            }

            // Line 2: SIM network details
            val activeSim = if (isSim1Online) sim1Path else if (isSim2Online) sim2Path else null
            if (activeSim != null) {
                val carrier = if (activeSim.carrierName.isNotBlank() && activeSim.carrierName != "Carrier unavailable") activeSim.carrierName else "Cellular"
                val netType = netMgr.getNetworkTypeName()
                tvBondingSimNetworks.text = String.format("SIM: %s (%s) ● Online (%.1f Mbps)", carrier, netType, activeSim.availableBandwidthMbps)
            } else {
                val simCarrier = netMgr.getSimCarrierName(0)
                if (simCarrier != "Carrier unavailable") {
                    val netType = netMgr.getNetworkTypeName()
                    tvBondingSimNetworks.text = "SIM: $simCarrier ($netType) ● Standby / No Data"
                } else {
                    tvBondingSimNetworks.text = "SIM: Standby / No Data"
                }
            }

            val totalAvail = (if (isWifiOnline) wifiPath?.availableBandwidthMbps ?: 0.0 else 0.0) +
                             (if (isSim1Online) sim1Path?.availableBandwidthMbps ?: 0.0 else 0.0) +
                             (if (isSim2Online) sim2Path?.availableBandwidthMbps ?: 0.0 else 0.0)

            tvBondingMetrics.text = String.format("Avail: %.1f Mbps | Usage: 0.0 Mbps (Standby)", totalAvail)
        } catch (e: Exception) {
            Log.w(TAG, "updateNetworkStatusPreview failed", e)
        }
    }

    private fun stopLiveStream() {
        try {
            genericStream?.stopStream()
        } catch (e: Exception) {
            Log.e(TAG, "stopStream error", e)
        }
        try {
            bondRtmpProxy?.stop()
            bondRtmpProxy = null
            bondSession?.stop()
            bondSession = null
        } catch (e: Exception) {
            Log.w(TAG, "Bonding shutdown error", e)
        }

        // Notify VPS monitoring of stream stop
        if (::telemetryManager.isInitialized) {
            telemetryManager.notifyStreamStopped("user_stopped")
        }

        com.streamezy.capture.service.BondStreamingService.stopService(this@MainActivity)
        updateNetworkStatusPreview()
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

    private fun updateOrientationHint(orientation: Int) {
        if (::layoutRotateHint.isInitialized) {
            layoutRotateHint.visibility = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
    }

    private fun updateStatsDisplay(customText: String? = null) {
        val aspect = streamConfig.selectedAspectRatio
        val src = when (currentSource) {
            ActiveSource.OTG -> "OTG"
            ActiveSource.REAR -> "Rear"
            ActiveSource.FRONT -> "Front"
        }
        val res = "${streamConfig.videoWidth}x${streamConfig.videoHeight} ($aspect $src)"
        val text = customText ?: "1000 kbps | 30 fps | $res"
        if (::tvStreamStatsPortrait.isInitialized) {
            tvStreamStatsPortrait.text = text
        }
        if (::tvStreamStatsLandscape.isInitialized) {
            tvStreamStatsLandscape.text = text
        }
    }

    private fun updateBatteryDisplay(text: String, color: Int) {
        if (::tvBatteryStatusPortrait.isInitialized) {
            tvBatteryStatusPortrait.text = text
            tvBatteryStatusPortrait.setTextColor(color)
        }
        if (::tvBatteryStatusLandscape.isInitialized) {
            tvBatteryStatusLandscape.text = text
            tvBatteryStatusLandscape.setTextColor(color)
        }
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
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        etRtmpUrl.setText(streamConfig.rtmpUrl)
        etStreamKey.setText(streamConfig.streamKey)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

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

        // Video Bitrate Selector
        val btnBitrate500k = dialogView.findViewById<Button>(R.id.btnBitrate500k)
        val btnBitrate1000k = dialogView.findViewById<Button>(R.id.btnBitrate1000k)
        val btnBitrate1500k = dialogView.findViewById<Button>(R.id.btnBitrate1500k)
        var tempBitrate = streamConfig.videoBitrate

        fun updateBitrateUI() {
            val activeColor = ContextCompat.getColor(this, R.color.accent_blue)
            val normalColor = ContextCompat.getColor(this, R.color.surface_card)
            btnBitrate500k?.setBackgroundColor(if (tempBitrate <= 750_000) activeColor else normalColor)
            btnBitrate1000k?.setBackgroundColor(if (tempBitrate in 750_001..1_250_000) activeColor else normalColor)
            btnBitrate1500k?.setBackgroundColor(if (tempBitrate > 1_250_000) activeColor else normalColor)
        }
        updateBitrateUI()

        btnBitrate500k?.setOnClickListener { tempBitrate = StreamConfig.BITRATE_500K; updateBitrateUI() }
        btnBitrate1000k?.setOnClickListener { tempBitrate = StreamConfig.BITRATE_1000K; updateBitrateUI() }
        btnBitrate1500k?.setOnClickListener { tempBitrate = StreamConfig.BITRATE_1500K; updateBitrateUI() }

        // BondStream UI Binding
        val switchBonding = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBonding)
        val etBondingHost = dialogView.findViewById<EditText>(R.id.etBondingHost)
        val etBondingPort = dialogView.findViewById<EditText>(R.id.etBondingPort)
        val btnCheckConnections = dialogView.findViewById<Button>(R.id.btnCheckConnections)

        switchBonding.isChecked = streamConfig.isBondingEnabled
        etBondingHost.setText(streamConfig.bondingServerHost)
        etBondingPort.setText(streamConfig.bondingServerPort.toString())

        btnCheckConnections?.setOnClickListener {
            btnCheckConnections.isEnabled = false
            btnCheckConnections.text = "Checking Connections..."

            val host = etBondingHost.text.toString().trim().ifEmpty { StreamConfig.DEFAULT_VPS_HOST }
            val port = etBondingPort.text.toString().toIntOrNull() ?: StreamConfig.DEFAULT_VPS_PORT
            val token = etStreamKey.text.toString().trim()

            val activeSession = bondSession ?: com.streamezy.capture.bonding.BondSession(this, host, port, token)
            val testRunner = com.streamezy.capture.bonding.BondTestRunner(activeSession)

            testRunner.runCheckConnections { report ->
                runOnUiThread {
                    btnCheckConnections.isEnabled = true
                    btnCheckConnections.text = "CHECK CONNECTIONS"

                    val summary = StringBuilder()
                    summary.append("🔍 Diagnostic Report:\n\n")
                    for (step in report.steps) {
                        val icon = if (step.passed) "✓" else "✕"
                        summary.append("$icon ${step.stepName}:\n   ${step.details}\n")
                    }
                    summary.append("\n⚡ Bond Capacity: ${String.format("%.1f", report.totalAvailableMbps)} Mbps\n🔒 Configured Maximum: ${report.configuredMaxMbps} Mbps")

                    AlertDialog.Builder(this)
                        .setTitle("Multi-Path Network Diagnostics")
                        .setMessage(summary.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        btnSave.setOnClickListener {
            streamConfig.rtmpUrl = etRtmpUrl.text.toString().trim().ifEmpty { StreamConfig.DEFAULT_RTMP_URL }
            streamConfig.streamKey = etStreamKey.text.toString().trim()
            streamConfig.isBondingEnabled = switchBonding.isChecked
            streamConfig.bondingServerHost = etBondingHost.text.toString().trim().ifEmpty { StreamConfig.DEFAULT_VPS_HOST }
            streamConfig.bondingServerPort = etBondingPort.text.toString().toIntOrNull() ?: StreamConfig.DEFAULT_VPS_PORT
            val ratioChanged = streamConfig.selectedAspectRatio != tempRatio
            val bitrateChanged = streamConfig.videoBitrate != tempBitrate
            streamConfig.selectedAspectRatio = tempRatio
            streamConfig.videoBitrate = tempBitrate

            if ((ratioChanged || bitrateChanged) && !isStreaming) {
                try {
                    genericStream?.let { s ->
                        if (s.isOnPreview) s.stopPreview()
                        prepareAndStartPreview()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Re-init on settings change failed", e)
                }
            }
            updateStatsDisplay()
            updateNetworkStatusPreview()
            val kbps = tempBitrate / 1000
            Toast.makeText(this, "Settings saved (Aspect: $tempRatio, Bitrate: ${kbps}k)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showNetworkCenterDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_center, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnCloseNetworkCenter)
        val tvCenterBadge = dialogView.findViewById<TextView>(R.id.tvCenterBadge)
        val tvCenterSummary = dialogView.findViewById<TextView>(R.id.tvCenterSummary)
        val tvCenterBandwidth = dialogView.findViewById<TextView>(R.id.tvCenterBandwidth)
        val tvCenterSentLoss = dialogView.findViewById<TextView>(R.id.tvCenterSentLoss)
        val layoutNetworkCards = dialogView.findViewById<LinearLayout>(R.id.layoutNetworkCards)
        val btnBenchmark = dialogView.findViewById<Button>(R.id.btnCenterBenchmark)
        val tvBenchmarkResults = dialogView.findViewById<TextView>(R.id.tvCenterBenchmarkResults)
        val tvCenterNotice = dialogView.findViewById<TextView>(R.id.tvCenterNotice)

        btnClose.setOnClickListener { dialog.dismiss() }

        fun populateCards() {
            layoutNetworkCards.removeAllViews()

            // Always use the live bondSession networkManager if streaming is active —
            // it already has startDiscovery() running which keeps cellular alive concurrently.
            // When no session, we must call startDiscovery() (NOT just refreshCurrentNetworks)
            // because Android hides cellular from allNetworks when Wi-Fi is active.
            // requestNetwork(TRANSPORT_CELLULAR) inside startDiscovery() forces Android
            // to expose BOTH Wi-Fi and cellular simultaneously for LiveU LRT-style bonding.
            val paths = if (bondSession != null) {
                bondSession!!.networkManager.paths.values.toList()
            } else {
                val netMgr = com.streamezy.capture.bonding.AndroidNetworkManager(this)
                netMgr.startDiscovery()   // ← CRITICAL: forces concurrent cellular detection
                netMgr.paths.values.toList()
            }

            val onlinePaths = paths.filter { it.status == com.streamezy.capture.bonding.PathStatus.ONLINE }
            val onlineCount = onlinePaths.size
            val isBonded = onlineCount >= 2

            if (isBonded) {
                tvCenterBadge.text = "BONDED ($onlineCount)"
                tvCenterBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            } else if (onlineCount > 0) {
                tvCenterBadge.text = "SINGLE PATH (1)"
                tvCenterBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            } else {
                tvCenterBadge.text = "OFFLINE"
                tvCenterBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary))
            }

            val onlineNames = onlinePaths.joinToString(" ") { "${it.name} ●" }
            tvCenterSummary.text = if (onlineNames.isNotBlank()) "$onlineCount Connected: $onlineNames" else "No Networks Connected"

            val totalAvail = onlinePaths.sumOf { it.availableBandwidthMbps }
            val totalUsage = paths.sumOf { it.currentUsageMbps }
            val totalSentMb = paths.sumOf { it.bytesSent } / (1024.0 * 1024.0)
            val avgLatency = if (onlineCount > 0) onlinePaths.map { it.latencyMs }.average().toLong() else 0L
            val avgJitter = if (onlineCount > 0) onlinePaths.map { it.jitterMs }.average().toLong() else 0L
            val lossPct = if (onlineCount > 0) onlinePaths.map { it.lossRate }.average() else 0.0
            val arqRepaired = bondSession?.retransmissionsRepaired?.get() ?: 0L
            val fecSent = bondSession?.fecPacketsSent?.get() ?: 0L
            val playout = (bondSession?.playoutDelayMs ?: streamConfig.playoutDelayMs).toInt()

            tvCenterBandwidth.text = String.format("Total Avail: %.1f Mbps | Live TX Usage: %.1f Mbps", totalAvail, totalUsage)
            tvCenterSentLoss.text = String.format("Sent: %.1f MB | %dms RTT (%dms jit) | ARQ: %d | FEC: %d (Zero Loss) | LiveU 🛡️ %dms", 
                totalSentMb, avgLatency, avgJitter, arqRepaired, fecSent, playout)

            for (p in paths) {
                val cardView = LayoutInflater.from(this).inflate(R.layout.item_network_card, layoutNetworkCards, false)
                val tvName = cardView.findViewById<TextView>(R.id.tvCardName)
                val tvStatus = cardView.findViewById<TextView>(R.id.tvCardStatus)
                val tvAvail = cardView.findViewById<TextView>(R.id.tvCardAvailable)
                val tvUsage = cardView.findViewById<TextView>(R.id.tvCardUsage)
                val tvSent = cardView.findViewById<TextView>(R.id.tvCardSent)
                val tvLatencyLoss = cardView.findViewById<TextView>(R.id.tvCardLatencyLoss)

                tvName.text = "${p.name} [${p.transportType}]"
                tvStatus.text = p.status.name
                val statusColor = when (p.status) {
                    com.streamezy.capture.bonding.PathStatus.ONLINE -> R.color.accent_green
                    com.streamezy.capture.bonding.PathStatus.CONNECTING,
                    com.streamezy.capture.bonding.PathStatus.RECOVERING -> R.color.accent_blue
                    com.streamezy.capture.bonding.PathStatus.FAILING -> R.color.accent_orange
                    else -> R.color.text_secondary
                }
                tvStatus.setBackgroundColor(ContextCompat.getColor(this, statusColor))

                tvAvail.text = String.format("Avail: %.1f Mbps", p.availableBandwidthMbps)
                tvUsage.text = String.format("TX Usage: %.1f Mbps", p.currentUsageMbps)
                val sentMb = p.bytesSent / (1024.0 * 1024.0)
                tvSent.text = String.format("Sent: %.1f MB (TX: %d | ACK: %d)", sentMb, p.packetsSent, p.packetsAcked)
                tvLatencyLoss.text = String.format("%dms RTT (%dms jit) | %.1f%% loss", p.latencyMs, p.jitterMs, p.lossRate)

                layoutNetworkCards.addView(cardView)
            }

            tvCenterNotice.visibility = View.GONE
        }

        populateCards()

        btnBenchmark.setOnClickListener {
            btnBenchmark.isEnabled = false
            btnBenchmark.text = "Checking Connections..."
            tvBenchmarkResults.visibility = View.VISIBLE
            tvBenchmarkResults.text = "Probing multi-path network & VPS connectivity..."

            val host = streamConfig.bondingServerHost.trim().ifEmpty { StreamConfig.DEFAULT_VPS_HOST }
            val port = streamConfig.bondingServerPort
            val token = streamConfig.streamKey.trim()

            val activeSession = bondSession ?: com.streamezy.capture.bonding.BondSession(this, host, port, token)
            val testRunner = com.streamezy.capture.bonding.BondTestRunner(activeSession)

            testRunner.runCheckConnections { report ->
                runOnUiThread {
                    btnBenchmark.isEnabled = true
                    btnBenchmark.text = "Run Multi-Path Benchmark Test"
                    
                    val summary = StringBuilder()
                    for (step in report.steps) {
                        val icon = if (step.passed) "✓" else "✕"
                        summary.append("$icon ${step.stepName}: ${step.details}\n")
                    }
                    summary.append(String.format("Bond Capacity: %.1f Mbps | Configured Max: %.1f Mbps Cap", report.totalAvailableMbps, report.configuredMaxMbps))

                    tvBenchmarkResults.text = summary.toString()
                    tvBenchmarkResults.setTextColor(
                        ContextCompat.getColor(this, if (report.isBondActive) R.color.accent_green else R.color.accent_blue)
                    )
                    populateCards()
                }
            }
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

            // Notify VPS monitoring of active stream session
            if (::telemetryManager.isInitialized) {
                telemetryManager.notifyStreamStarted(
                    streamKey = streamConfig.streamKey,
                    resolution = "${streamConfig.videoWidth}x${streamConfig.videoHeight}",
                    fps = StreamConfig.DEFAULT_FPS,
                    videoBitrate = streamConfig.videoBitrate,
                    audioBitrate = StreamConfig.DEFAULT_AUDIO_BITRATE,
                    connectionType = if (streamConfig.isBondingEnabled) "bonded_android" else "direct_rtmp"
                )
            }

            Toast.makeText(this, "Live Broadcast Connected!", Toast.LENGTH_SHORT).show()
            updateAudioStatusPanelUI()
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
                updateAudioStatusPanelUI()
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
                updateAudioStatusPanelUI()

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
            updateStatsDisplay("$displayKbps kbps | 30 fps | $res")
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
            updateAudioStatusPanelUI()
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
        updateOrientationHint(newConfig.orientation)
        updateHeaderOrientation(newConfig.orientation)
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
        audioProgressHandler.post(audioProgressRunnable)
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
        audioProgressHandler.removeCallbacks(audioProgressRunnable)
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
        stopLocalPreview()
        audioProgressHandler.removeCallbacks(audioProgressRunnable)
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
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
        try {
            previewNetworkManager?.stopDiscovery()
            previewNetworkManager = null
        } catch (e: Exception) {}
        if (::telemetryManager.isInitialized) {
            telemetryManager.stop()
        }
    }
}