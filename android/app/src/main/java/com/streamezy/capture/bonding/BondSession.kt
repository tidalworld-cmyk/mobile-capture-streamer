package com.streamezy.capture.bonding

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class BondingMode {
    OFF,
    TEST,
    ON
}

data class BondMetrics(
    val isBonded: Boolean,
    val activePathCount: Int,
    val combinedUploadMbps: Double,
    val totalAvailableBandwidthMbps: Double,
    val totalUsageMbps: Double,
    val totalBytesSent: Long,
    val averageLatencyMs: Long,
    val packetLossPct: Double,
    val paths: List<NetworkPath>
)

class BondSession(
    private val context: Context,
    var serverHost: String = "192.168.29.184",
    var serverPort: Int = 5000,
    var authToken: String = ""
) {
    companion object {
        private const val TAG = "BondSession"
    }

    val networkManager = AndroidNetworkManager(context)
    private val scheduler = BondScheduler()
    private val pathClients = ConcurrentHashMap<Byte, BondPathClient>()

    private val sequenceNumber = AtomicLong(0L)
    val sessionId: Int = Random.nextInt(100000, 999999)
    private val isRunning = AtomicBoolean(false)

    var mode: BondingMode = BondingMode.OFF

    val isBonded: Boolean
        get() = pathClients.values.count { it.path.status == PathStatus.ONLINE } >= 2

    val activePathCount: Int
        get() = pathClients.values.count { it.path.status == PathStatus.ONLINE }

    val combinedUploadMbps: Double
        get() = pathClients.values.sumOf { it.path.currentUsageMbps }

    val averageLatencyMs: Long
        get() {
            val online = pathClients.values.filter { it.path.status == PathStatus.ONLINE }
            return if (online.isNotEmpty()) online.map { it.path.latencyMs }.average().toLong() else 0L
        }

    var onDownlinkData: ((ByteArray) -> Unit)? = null
    var onAuthFailed: ((String) -> Unit)? = null
    var onMetricsUpdated: ((BondMetrics) -> Unit)? = null
    private var metricsThread: Thread? = null

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting BondStream Session $sessionId targeting $serverHost:$serverPort...")

        networkManager.onPathsChanged = { paths ->
            syncPathClients(paths)
        }
        networkManager.startDiscovery()

        metricsThread = Thread({
            while (isRunning.get()) {
                try {
                    Thread.sleep(1000)
                    if (!isRunning.get()) break
                    val paths = pathClients.values.map { it.path }
                    var currentTotalUsage = 0.0
                    var currentTotalAvailable = 0.0
                    var currentTotalSent = 0L

                    for (p in paths) {
                        currentTotalSent += p.bytesSent
                        if (p.status == PathStatus.ONLINE) {
                            val bytesDelta = (p.bytesSent - p.lastBytesSent).coerceAtLeast(0L)
                            p.currentUsageMbps = (bytesDelta * 8.0) / 1_000_000.0
                            p.lastBytesSent = p.bytesSent
                            currentTotalUsage += p.currentUsageMbps
                            currentTotalAvailable += p.availableBandwidthMbps
                        } else {
                            p.currentUsageMbps = 0.0
                        }
                    }

                    val loss = if (paths.isNotEmpty()) paths.map { it.lossRate }.average() else 0.0
                    val metrics = BondMetrics(
                        isBonded = isBonded,
                        activePathCount = activePathCount,
                        combinedUploadMbps = currentTotalUsage,
                        totalAvailableBandwidthMbps = currentTotalAvailable,
                        totalUsageMbps = currentTotalUsage,
                        totalBytesSent = currentTotalSent,
                        averageLatencyMs = averageLatencyMs,
                        packetLossPct = loss,
                        paths = paths
                    )
                    onMetricsUpdated?.invoke(metrics)
                } catch (e: Exception) {
                    break
                }
            }
        }, "BondMetricsTicker").apply { start() }
    }

    private fun syncPathClients(paths: List<NetworkPath>) {
        for (path in paths) {
            if (path.isUsable && !pathClients.containsKey(path.pathId)) {
                Log.i(TAG, "Initializing path client for ${path.name}")
                val client = BondPathClient(path, serverHost, serverPort, sessionId, authToken)
                client.onDownlinkReceived = { data ->
                    onDownlinkData?.invoke(data)
                }
                client.onAuthFailed = { reason ->
                    onAuthFailed?.invoke(reason)
                }
                pathClients[path.pathId] = client
                client.start()
                scheduler.onPathRecovered(path.pathId)
            } else if (!path.isUsable && pathClients.containsKey(path.pathId)) {
                Log.w(TAG, "Stopping path client for ${path.name}")
                pathClients.remove(path.pathId)?.stop()
                scheduler.onPathFailed(path.pathId)
            }
        }
    }

    fun sendData(payload: ByteArray): Boolean {
        if (!isRunning.get() || mode == BondingMode.OFF) return false

        val activePaths = pathClients.values.map { it.path }
        val selectedPath = scheduler.selectPath(activePaths) ?: return false
        val client = pathClients[selectedPath.pathId] ?: return false

        val seq = sequenceNumber.getAndIncrement()
        val pkt = BondPacket(
            packetType = PacketType.DATA,
            pathId = selectedPath.pathId,
            sessionId = sessionId,
            sequence = seq,
            payload = payload
        )

        return client.sendPacket(pkt)
    }

    fun runBenchmarkTest(
        durationSeconds: Int = 5,
        onProgress: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                onProgress("Benchmarking paths on $serverHost:$serverPort...")
                start()
                val startTime = System.currentTimeMillis()
                var probesSent = 0
                while (System.currentTimeMillis() - startTime < durationSeconds * 1000L) {
                    for (client in pathClients.values) {
                        val pkt = BondPacket(
                            packetType = PacketType.PROBE,
                            pathId = client.path.pathId,
                            sessionId = sessionId,
                            sequence = sequenceNumber.getAndIncrement(),
                            payload = ByteArray(256)
                        )
                        client.sendPacket(pkt)
                        probesSent++
                    }
                    Thread.sleep(500)
                    val online = pathClients.values.count { it.path.status == PathStatus.ONLINE }
                    onProgress("Active: $online paths | Latency: ${averageLatencyMs}ms | Probes: $probesSent")
                }

                val online = pathClients.values.count { it.path.status == PathStatus.ONLINE }
                val summary = if (online >= 2) {
                    "SUCCESS: Multi-path BONDED ($online paths active, ${averageLatencyMs}ms latency)"
                } else if (online == 1) {
                    "SUCCESS: Single path connected (${averageLatencyMs}ms latency)"
                } else {
                    "FAILED: Could not reach VPS on UDP $serverHost:$serverPort"
                }
                stop()
                onComplete(online > 0, summary)
            } catch (e: Exception) {
                stop()
                onComplete(false, "Test error: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        metricsThread?.interrupt()
        metricsThread = null
        for (client in pathClients.values) {
            client.stop()
        }
        pathClients.clear()
        networkManager.stopDiscovery()
        Log.i(TAG, "BondStream Session $sessionId stopped.")
    }
}
