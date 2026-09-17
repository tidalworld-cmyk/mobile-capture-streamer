package com.streamezy.capture.bonding

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BondScheduler {

    // Recovery ramp-up tracking: pathId -> rampFactor (0.1 to 1.0)
    private val recoveryWeights = ConcurrentHashMap<Byte, Float>()
    private val roundRobinCounter = AtomicInteger(0)

    fun selectPath(paths: List<NetworkPath>): NetworkPath? {
        val usable = paths.filter { it.isUsable }
        if (usable.isEmpty()) return null
        if (usable.size == 1) return usable[0]

        // Calculate dynamic weights
        val weightedList = mutableListOf<NetworkPath>()

        for (path in usable) {
            val rtt = path.latencyMs.coerceAtLeast(10L).toFloat()
            val loss = path.lossRate.coerceIn(0f, 0.9f)
            val speed = path.estimatedUploadMbps.coerceAtLeast(1.0).toFloat()

            // Smooth recovery multiplier
            var ramp = recoveryWeights[path.pathId] ?: 1.0f
            if (ramp < 1.0f) {
                ramp = (ramp + 0.05f).coerceAtMost(1.0f)
                recoveryWeights[path.pathId] = ramp
            }

            // Quality score: higher bandwidth, lower latency, lower loss = higher score
            val qualityScore = (speed / rtt) * (1.0f - loss) * (1.0f - loss) * ramp
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
        // Start gradual traffic ramp-up from 15% capacity
        recoveryWeights[pathId] = 0.15f
    }

    fun onPathFailed(pathId: Byte) {
        recoveryWeights.remove(pathId)
    }
}
