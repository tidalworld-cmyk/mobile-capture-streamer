package com.streamezy.capture.bonding

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BondScheduler {

    // Recovery ramp-up tracking: pathId -> rampFactor (0.1 to 1.0)
    private val recoveryWeights = ConcurrentHashMap<Byte, Float>()
    private val roundRobinCounter = AtomicInteger(0)

    fun selectPath(paths: List<NetworkPath>): NetworkPath? {
        val usable = paths.filter { it.isUsable }
        if (usable.isEmpty()) {
            // Zero-drop fallback: never stop broadcast if any network handle exists
            val fallback = paths.filter { it.network != null && it.status != PathStatus.OFFLINE }
            if (fallback.isNotEmpty()) return fallback[0]
            return paths.firstOrNull { it.network != null }
        }
        if (usable.size == 1) return usable[0]

        // Calculate dynamic weights for combined bandwidth aggregation
        val weightedList = mutableListOf<NetworkPath>()

        for (path in usable) {
            val rtt = path.latencyMs.coerceAtLeast(10L).toFloat()
            val loss = path.lossRate.coerceIn(0f, 0.9f)
            // Real bandwidth aggregation: Wi-Fi + SIM available bandwidth combined
            val speed = (if (path.estimatedUploadMbps > 0) path.estimatedUploadMbps else path.availableBandwidthMbps).coerceAtLeast(1.0).toFloat()
            val jitterPenalty = 1.0f / (1.0f + (path.jitterMs.coerceAtLeast(0L).toFloat() / 25.0f))

            // Transport bonus
            val transportBonus = if (path.transportType.contains("ETHERNET", ignoreCase = true) || path.name.contains("LAN", ignoreCase = true)) {
                1.20f
            } else if (path.transportType.contains("WIFI", ignoreCase = true) || path.name.contains("Wi-Fi", ignoreCase = true)) {
                1.10f
            } else {
                1.05f
            }

            // Rapid recovery ramp-up to boost broadcast when SIM connects
            var ramp = recoveryWeights[path.pathId] ?: 1.0f
            if (ramp < 1.0f) {
                ramp = (ramp + 0.15f).coerceAtMost(1.0f)
                recoveryWeights[path.pathId] = ramp
            }

            // LiveU LRT Quality score: higher combined bandwidth = higher proportional tickets
            val qualityScore = (speed / rtt) * (1.0f - loss) * (1.0f - loss) * jitterPenalty * ramp * transportBonus
            val tickets = (qualityScore * 10).toInt().coerceIn(1, 100)

            repeat(tickets) {
                weightedList.add(path)
            }
        }

        if (weightedList.isEmpty()) return usable[0]

        val idx = (roundRobinCounter.incrementAndGet() and Int.MAX_VALUE) % weightedList.size
        return weightedList[idx]
    }

    fun onPathRecovered(pathId: Byte) {
        // Immediate boost: start at 50% capacity and ramp to 100% within moments
        recoveryWeights[pathId] = 0.50f
    }

    fun onPathFailed(pathId: Byte) {
        recoveryWeights.remove(pathId)
    }
}
