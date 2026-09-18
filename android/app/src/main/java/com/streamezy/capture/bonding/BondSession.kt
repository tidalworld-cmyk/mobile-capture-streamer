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
    val averageJitterMs: Long,
    val packetLossPct: Double,
    val retransmissionsRepaired: Long,
    val redundantPacketsSent: Long,
    val fecPacketsSent: Long,
    val playoutDelayMs: Double,
    val isZeroLoss: Boolean,
    val paths: List<NetworkPath>
)

class BondSession(
    private val context: Context,
    var serverHost: String = "192.168.29.184",
    var serverPort: Int = 5000,
    var authToken: String = "",
    var playoutDelayMs: Double = 1000.0,
    var enableArq: Boolean = true,
    var enableRedundancy: Boolean = true,
    var enableFec: Boolean = true,
    var fecBlockSize: Int = 8
) {
    companion object {
        private const val TAG = "BondSession"
        private const val RING_BUFFER_CAPACITY = 2048
    }

    val networkManager = AndroidNetworkManager(context)
    private val scheduler = BondScheduler()
    private val pathClients = ConcurrentHashMap<Byte, BondPathClient>()

    private val sequenceNumber = AtomicLong(0L)
    val sessionId: Int = Random.nextInt(100000, 999999)
    private val isRunning = AtomicBoolean(false)

    // LiveU LRT ARQ Ring Buffer
    private val ringBuffer = ConcurrentHashMap<Long, BondPacket>()
    private val ringOrder = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    val retransmissionsRepaired = AtomicLong(0L)
    val redundantPacketsSent = AtomicLong(0L)
    val fecPacketsSent = AtomicLong(0L)

    // Forward Error Correction (FEC) Parity Block Buffer
    private val fecBuffer = java.util.ArrayList<Pair<Long, ByteArray>>()
    private val fecLock = Any()

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

    val averageJitterMs: Long
        get() {
            val online = pathClients.values.filter { it.path.status == PathStatus.ONLINE }
            return if (online.isNotEmpty()) online.map { it.path.jitterMs }.average().toLong() else 0L
        }

    var onDownlinkData: ((ByteArray) -> Unit)? = null
    var onAuthFailed: ((String) -> Unit)? = null
    var onMetricsUpdated: ((BondMetrics) -> Unit)? = null
    private var metricsThread: Thread? = null

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting BondStream LiveU Session $sessionId targeting $serverHost:$serverPort (buffer: ${playoutDelayMs}ms)...")

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
                    val repaired = retransmissionsRepaired.get()
                    val metrics = BondMetrics(
                        isBonded = isBonded,
                        activePathCount = activePathCount,
                        combinedUploadMbps = currentTotalUsage,
                        totalAvailableBandwidthMbps = currentTotalAvailable,
                        totalUsageMbps = currentTotalUsage,
                        totalBytesSent = currentTotalSent,
                        averageLatencyMs = averageLatencyMs,
                        averageJitterMs = averageJitterMs,
                        packetLossPct = loss,
                        retransmissionsRepaired = repaired,
                        redundantPacketsSent = redundantPacketsSent.get(),
                        fecPacketsSent = fecPacketsSent.get(),
                        playoutDelayMs = playoutDelayMs,
                        isZeroLoss = loss <= 0.1 || repaired > 0,
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
                val client = BondPathClient(path, serverHost, serverPort, sessionId, authToken, playoutDelayMs)
                client.onDownlinkReceived = { data ->
                    onDownlinkData?.invoke(data)
                }
                client.onAuthFailed = { reason ->
                    onAuthFailed?.invoke(reason)
                }
                client.onNackReceived = { missingSeqs ->
                    handleNack(missingSeqs)
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

    private fun getFastestActivePath(): BondPathClient? {
        val online = pathClients.values.filter { it.path.status == PathStatus.ONLINE }
        return online.minByOrNull { it.path.latencyMs }
    }

    private fun getSecondaryActivePath(excludePathId: Byte): BondPathClient? {
        val others = pathClients.values.filter { it.path.pathId != excludePathId && it.path.status == PathStatus.ONLINE }
        return others.minByOrNull { it.path.latencyMs }
    }

    private fun isCriticalPayload(payload: ByteArray): Boolean {
        if (payload.size >= 3 && payload[0] == 'F'.code.toByte() && payload[1] == 'L'.code.toByte() && payload[2] == 'V'.code.toByte()) {
            return true
        }
        if (payload.size > 11 && (payload[0].toInt() and 0x1F) == 9) {
            return true
        }
        return false
    }

    private fun handleNack(missingSeqs: List<Long>) {
        if (!enableArq) return
        val fastest = getFastestActivePath() ?: return
        for (seq in missingSeqs) {
            val cached = ringBuffer[seq]
            if (cached != null) {
                val rePkt = BondPacket(
                    packetType = PacketType.DATA,
                    pathId = fastest.path.pathId,
                    sessionId = sessionId,
                    streamId = cached.streamId,
                    sequence = cached.sequence,
                    timestamp = cached.timestamp,
                    flags = (cached.flags.toInt() or PacketFlags.RETRANSMITTED.toInt()).toByte(),
                    payload = cached.payload
                )
                if (fastest.sendPacket(rePkt)) {
                    fastest.path.retransmissionsSent++
                    retransmissionsRepaired.incrementAndGet()
                    Log.i(TAG, "LiveU LRT ARQ Retransmitted seq=$seq over fastest path ${fastest.path.name}")
                }
            } else {
                Log.w(TAG, "LiveU LRT ARQ missed cache for seq=$seq")
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

        val success = client.sendPacket(pkt)

        // Store into LiveU LRT ARQ ring buffer for instant retransmission
        ringBuffer[seq] = pkt
        ringOrder.add(seq)
        while (ringOrder.size > RING_BUFFER_CAPACITY) {
            val oldSeq = ringOrder.poll()
            if (oldSeq != null) {
                ringBuffer.remove(oldSeq)
            }
        }

        // Proactive LiveU Header Duplication across secondary physical interface
        if (success && enableRedundancy && isCriticalPayload(payload)) {
            val secondary = getSecondaryActivePath(selectedPath.pathId)
            if (secondary != null) {
                val dupPkt = BondPacket(
                    packetType = PacketType.DATA,
                    pathId = secondary.path.pathId,
                    sessionId = sessionId,
                    sequence = seq,
                    timestamp = pkt.timestamp,
                    flags = PacketFlags.REDUNDANT,
                    payload = payload
                )
                if (secondary.sendPacket(dupPkt)) {
                    redundantPacketsSent.incrementAndGet()
                    Log.d(TAG, "Proactively duplicated critical header seq=$seq over ${secondary.path.name}")
                }
            }
        }

        // LiveU Forward Error Correction (FEC) Parity Packets
        if (success && enableFec) {
            var fecToSend: Pair<BondPathClient, BondPacket>? = null
            synchronized(fecLock) {
                fecBuffer.add(Pair(seq, payload))
                if (fecBuffer.size >= fecBlockSize) {
                    val baseSeq = fecBuffer[0].first
                    val payloads = fecBuffer.map { it.second }
                    val fecPayload = BondPacket.encodeFecPayload(baseSeq, fecBuffer.size, payloads)
                    val onlineClients = pathClients.values.filter { it.path.status == PathStatus.ONLINE }
                    if (onlineClients.isNotEmpty()) {
                        val alt = onlineClients.filter { it.path.pathId != selectedPath.pathId }
                        val fecClient = alt.firstOrNull() ?: onlineClients.first()
                        val fecPkt = BondPacket(
                            packetType = PacketType.DATA,
                            pathId = fecClient.path.pathId,
                            sessionId = sessionId,
                            sequence = seq,
                            flags = PacketFlags.FEC_PARITY,
                            payload = fecPayload
                        )
                        fecToSend = Pair(fecClient, fecPkt)
                    }
                    fecBuffer.clear()
                }
            }
            fecToSend?.let { (fc, fp) ->
                if (fc.sendPacket(fp)) {
                    fecPacketsSent.incrementAndGet()
                }
            }
        }

        return success
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
