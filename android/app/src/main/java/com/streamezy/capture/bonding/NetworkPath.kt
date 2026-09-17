package com.streamezy.capture.bonding

import android.net.Network

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
    var lossRate: Float = 0f,
    var estimatedUploadMbps: Double = 0.0,
    var lastHeartbeatTimestamp: Long = 0L,
    var packetsSent: Long = 0L,
    var bytesSent: Long = 0L,
    var packetsAcked: Long = 0L,
    var packetsLost: Long = 0L
) {
    val isUsable: Boolean
        get() = status == PathStatus.ONLINE && network != null

    fun updateMetrics(newLatency: Long, loss: Float, mbps: Double) {
        latencyMs = if (latencyMs == 0L) newLatency else ((latencyMs * 0.8) + (newLatency * 0.2)).toLong()
        lossRate = loss
        estimatedUploadMbps = mbps
        lastHeartbeatTimestamp = System.currentTimeMillis()
    }
}
