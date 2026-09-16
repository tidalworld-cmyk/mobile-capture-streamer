package com.streamezy.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource

class OtgCameraSource(private val context: Context) : VideoSource() {

    private var cameraHelper: ICameraHelper? = null
    private var running = false
    private var surface: Surface? = null
    private var isCameraOpen = false
    @Volatile
    private var selectedDeviceName: String? = null

    private var targetWidth: Int = 1280
    private var targetHeight: Int = 720
    private var targetFps: Int = 30

    companion object {
        private const val TAG = "OtgCameraSource"
    }

    fun setTargetDimensions(width: Int, height: Int, fps: Int) {
        if (width > 0 && height > 0) {
            targetWidth = width
            targetHeight = height
        }
        if (fps > 0) {
            targetFps = fps
        }
        Log.d(TAG, "OtgCameraSource setTargetDimensions: ${targetWidth}x${targetHeight}@$targetFps fps")
    }

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        setTargetDimensions(width, height, fps)
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (isRunning()) return

        try {
            // Set default buffer size matching the encoder dimensions for zero-distortion OpenGL rendering
            surfaceTexture.setDefaultBufferSize(targetWidth, targetHeight)
            surface = Surface(surfaceTexture)
            cameraHelper = CameraHelper()
            cameraHelper?.setStateCallback(stateCallback)
            running = true

            // Automatically select already connected USB UVC device so stream never stalls
            selectConnectedDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OtgCameraSource", e)
            running = false
        }
    }

    fun selectConnectedDevice() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val deviceList = usbManager?.deviceList
            val uvcDevice = deviceList?.values?.firstOrNull { dev ->
                dev.deviceClass == 14 || dev.deviceClass == 239 ||
                (0 until dev.interfaceCount).any { i -> dev.getInterface(i).interfaceClass == 14 }
            }
            if (uvcDevice != null) {
                if (selectedDeviceName == uvcDevice.deviceName) {
                    Log.d(TAG, "Device ${uvcDevice.deviceName} already selected, skipping duplicate select")
                    return
                }
                Log.d(TAG, "Selecting connected UVC device: ${uvcDevice.deviceName}")
                selectedDeviceName = uvcDevice.deviceName
                cameraHelper?.selectDevice(uvcDevice)
            } else {
                Log.w(TAG, "No UVC device found in UsbManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "selectConnectedDevice error", e)
        }
    }

    override fun stop() {
        try {
            surface?.let {
                try {
                    cameraHelper?.removeSurface(it)
                } catch (e: Exception) {
                    Log.w(TAG, "removeSurface warning", e)
                }
            }
            surface = null

            try {
                cameraHelper?.release()
            } catch (e: Exception) {
                Log.w(TAG, "cameraHelper release warning", e)
            }
            cameraHelper = null
            isCameraOpen = false
            selectedDeviceName = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping OtgCameraSource", e)
        } finally {
            running = false
        }
    }

    override fun release() {
        stop()
    }

    override fun isRunning(): Boolean = running

    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)

    private val stateCallback: ICameraHelper.StateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            Log.d(TAG, "UVC onAttach: ${device.deviceName}")
            try {
                if (selectedDeviceName != device.deviceName) {
                    selectedDeviceName = device.deviceName
                    cameraHelper?.selectDevice(device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "selectDevice in onAttach failed", e)
            }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.d(TAG, "UVC onDeviceOpen: ${device.deviceName}")
            try {
                cameraHelper?.openCamera()
            } catch (e: Exception) {
                Log.e(TAG, "openCamera failed", e)
            }
        }

        override fun onCameraOpen(device: UsbDevice) {
            Log.d(TAG, "UVC onCameraOpen: ${device.deviceName}, dimensions: ${targetWidth}x${targetHeight}@$targetFps")
            isCameraOpen = true
            try {
                // Attach surface FIRST so the native UVC sink is registered
                surface?.let {
                    if (it.isValid) {
                        try {
                            cameraHelper?.addSurface(it, false)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Pre-preview addSurface notice", e)
                        }
                    }
                }

                // Start native preview stream
                cameraHelper?.startPreview()

                // Re-verify surface is registered with the active preview pipeline
                surface?.let {
                    if (it.isValid) {
                        try {
                            cameraHelper?.addSurface(it, false)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Post-preview addSurface notice", e)
                        }
                    } else {
                        Log.w(TAG, "Surface is not valid during onCameraOpen")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onCameraOpen startPreview or addSurface failed", e)
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            Log.d(TAG, "UVC onCameraClose: ${device.deviceName}")
            isCameraOpen = false
        }

        override fun onDeviceClose(device: UsbDevice) {
            Log.d(TAG, "UVC onDeviceClose: ${device.deviceName}")
            isCameraOpen = false
            selectedDeviceName = null
        }

        override fun onDetach(device: UsbDevice) {
            Log.d(TAG, "UVC onDetach: ${device.deviceName}")
            isCameraOpen = false
            selectedDeviceName = null
        }

        override fun onCancel(device: UsbDevice) {
            Log.w(TAG, "UVC onCancel: permission denied for ${device.deviceName}")
            isCameraOpen = false
            selectedDeviceName = null
        }
    }
}
