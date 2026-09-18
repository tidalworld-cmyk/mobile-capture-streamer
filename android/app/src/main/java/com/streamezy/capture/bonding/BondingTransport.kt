package com.streamezy.capture.bonding

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * BondingTransport — Stage 2 Abstraction
 * Client-side transport layer decoupling encoder output from physical network sockets.
 * Handles MTU segmentation (<= 1380 bytes), monotonic sequence numbering,
 * high-resolution timestamps, CRC32 checksums, and single-path packet dispatch.
 */
class BondingTransport(
    val sessionId: Int,
    val streamId: Int = 1,
    var pathClient: BondPathClient? = null
) {
    companion object {
        private const val TAG = "BondingTransport"
        const val MAX_PAYLOAD_SIZE = 1380 // Safe MTU preventing IP fragmentation
    }

    private val sequenceNumber = AtomicLong(0L)
    val packetsSent = AtomicLong(0L)
    val bytesSent = AtomicLong(0L)
    var isRunning = true

    /**
     * Slices an incoming media chunk into safe MTU units and sends as BondingPackets.
     * Returns the count of successfully transmitted packets.
     */
    fun sendMediaChunk(chunk: ByteArray, flags: Byte = PacketFlags.NONE): Int {
        if (!isRunning || chunk.isEmpty()) return 0
        val client = pathClient ?: return 0

        var sentCount = 0
        var offset = 0
        val totalLen = chunk.size

        while (offset < totalLen) {
            val sliceLen = Math.min(MAX_PAYLOAD_SIZE, totalLen - offset)
            val slice = ByteArray(sliceLen)
            System.arraycopy(chunk, offset, slice, 0, sliceLen)

            val seq = sequenceNumber.getAndIncrement()
            val nowMs = System.currentTimeMillis()

            val pkt = BondingPacket(
                packetType = PacketType.DATA,
                pathId = client.path.pathId,
                sessionId = sessionId,
                streamId = streamId,
                sequence = seq,
                timestamp = nowMs,
                flags = flags,
                payload = slice
            )

            if (client.sendPacket(pkt)) {
                packetsSent.incrementAndGet()
                bytesSent.addAndGet(sliceLen.toLong())
                sentCount++
            } else {
                Log.w(TAG, "Failed sending packet seq=$seq on path ${client.path.name}")
            }

            offset += sliceLen
        }

        return sentCount
    }

    fun close() {
        isRunning = false
    }
}
