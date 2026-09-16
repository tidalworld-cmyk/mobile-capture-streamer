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

    companion object {
        private const val TAG = "OtgCameraSource"
    }

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (isRunning()) return

        try {
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
            if (isCameraOpen) {
                surface?.let {
                    if (it.isValid) {
                        try {
                            cameraHelper?.removeSurface(it)
                        } catch (e: Exception) {
                            Log.w(TAG, "removeSurface warning", e)
                        }
                    }
                }
                try {
                    cameraHelper?.stopPreview()
                } catch (e: Exception) {
                    Log.w(TAG, "stopPreview warning", e)
                }
                try {
                    cameraHelper?.closeCamera()
                } catch (e: Exception) {
                    Log.w(TAG, "closeCamera warning", e)
                }
            }
            try {
                surface?.release()
            } catch (e: Exception) {}
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
            Log.d(TAG, "UVC onCameraOpen: ${device.deviceName}")
            isCameraOpen = true
            try {
                surface?.let {
                    if (it.isValid) {
                        cameraHelper?.addSurface(it, false)
                    } else {
                        Log.w(TAG, "Surface is not valid during onCameraOpen")
                    }
                }
                cameraHelper?.startPreview()
            } catch (e: Exception) {
                Log.e(TAG, "startPreview or addSurface failed", e)
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
