package com.streamezy.capture.bonding

import android.net.Network
import kotlin.math.abs

enum class PathStatus {
    DISCONNECTED,
    CONNECTING,
    ONLINE,
    FAILING,
    OFFLINE,
    RECOVERING
}

data class NetworkPath(
    val pathId: Byte,
    var name: String,
    val transportType: String,
    var network: Network? = null,
    var status: PathStatus = PathStatus.DISCONNECTED,
    var latencyMs: Long = 0L,
    var jitterMs: Long = 0L,
    var lossRate: Float = 0f,
    var estimatedUploadMbps: Double = 0.0,
    var lastHeartbeatTimestamp: Long = 0L,
    var packetsSent: Long = 0L,
    var bytesSent: Long = 0L,
    var packetsAcked: Long = 0L,
    var packetsLost: Long = 0L,
    var retransmissionsSent: Long = 0L,
    var availableBandwidthMbps: Double = 0.0,
    var currentUsageMbps: Double = 0.0,
    var lastBytesSent: Long = 0L,
    var localIp: String = "",
    var carrierName: String = "",
    var statusDetail: String = "Not checked",
    var isInternetAvailable: Boolean = false,
    var isVpsReachable: Boolean = false
) {
    val packetLossPct: Double
        get() = (lossRate * 100.0).toDouble()

    val isUsable: Boolean
        get() = (status == PathStatus.ONLINE || status == PathStatus.RECOVERING) && network != null

    fun updateMetrics(newLatency: Long, loss: Float, mbps: Double) {
        if (latencyMs > 0L) {
            val sampleJitter = abs(newLatency - latencyMs)
            jitterMs = ((jitterMs * 0.8) + (sampleJitter * 0.2)).toLong()
            latencyMs = ((latencyMs * 0.8) + (newLatency * 0.2)).toLong()
        } else {
            latencyMs = newLatency
            jitterMs = 0L
        }
        lossRate = loss
        estimatedUploadMbps = mbps
        lastHeartbeatTimestamp = System.currentTimeMillis()
    }
}
