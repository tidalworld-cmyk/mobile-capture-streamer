package com.streamezy.capture.bonding

import android.net.Network
import kotlin.math.abs

enum class PathStatus {
    STANDBY,
    AVAILABLE,
    TESTING,
    HEALTHY,
    ACTIVE,
    DEGRADED,
    FAILED,
    RECOVERING,
    // Aliases for compatibility
    DISCONNECTED,
    CONNECTING,
    ONLINE,
    FAILING,
    OFFLINE
}

data class NetworkPath(
    val pathId: Byte,
    var name: String,
    val transportType: String,
    var network: Network? = null,
    var status: PathStatus = PathStatus.STANDBY,
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
        get() = (status == PathStatus.ACTIVE || 
                 status == PathStatus.ONLINE || 
                 status == PathStatus.HEALTHY || 
                 status == PathStatus.RECOVERING || 
                 status == PathStatus.AVAILABLE || 
                 status == PathStatus.TESTING || 
                 status == PathStatus.CONNECTING || 
                 status == PathStatus.DEGRADED || 
                 status == PathStatus.FAILING) && network != null

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
