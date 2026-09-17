package com.streamezy.capture.bonding

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

enum class PacketType(val value: Byte) {
    DATA(1),
    HEARTBEAT(2),
    ACK(3),
    HELLO(4),
    GOODBYE(5),
    PROBE(6),
    PROBE_ACK(7),
    DOWNLINK(8),
    AUTH_FAIL(9);

    companion object {
        fun fromByte(b: Byte): PacketType = values().firstOrNull { it.value == b } ?: DATA
    }
}

class BondPacket(
    val packetType: PacketType,
    val pathId: Byte,
    val sessionId: Int,
    val streamId: Int = 1,
    val sequence: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val flags: Byte = 0,
    val payload: ByteArray = ByteArray(0)
) {
    companion object {
        val MAGIC_BYTES = byteArrayOf(0x42, 0x53) // "BS"
        const val PROTOCOL_VERSION: Byte = 1
        const val HEADER_SIZE = 36
        const val MAX_PAYLOAD_SIZE = 1400

        fun deserialize(data: ByteArray, length: Int = data.size): BondPacket {
            if (length < HEADER_SIZE) {
                throw IllegalArgumentException("Packet too short: $length bytes (min $HEADER_SIZE)")
            }

            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

            val m0 = buffer.get()
            val m1 = buffer.get()
            if (m0 != MAGIC_BYTES[0] || m1 != MAGIC_BYTES[1]) {
                throw IllegalArgumentException("Invalid magic bytes: $m0, $m1")
            }

            val version = buffer.get()
            if (version != PROTOCOL_VERSION) {
                throw IllegalArgumentException("Unsupported protocol version: $version")
            }

            val pType = PacketType.fromByte(buffer.get())
            val flags = buffer.get()
            val pathId = buffer.get()
            val sessionId = buffer.int
            val streamId = buffer.int
            val sequence = buffer.long
            val timestamp = buffer.long
            val payloadLen = buffer.short.toInt() and 0xFFFF
            val checksum = buffer.int.toLong() and 0xFFFFFFFFL

            if (length < HEADER_SIZE + payloadLen) {
                throw IllegalArgumentException("Truncated payload: expected $payloadLen, got ${length - HEADER_SIZE}")
            }

            val payload = ByteArray(payloadLen)
            buffer.get(payload)

            // Verify CRC32
            val crc = CRC32()
            crc.update(data, 0, 32)
            crc.update(payload, 0, payloadLen)
            if (checksum != crc.value) {
                throw IllegalArgumentException("CRC32 mismatch: $checksum != ${crc.value}")
            }

            return BondPacket(
                packetType = pType,
                pathId = pathId,
                sessionId = sessionId,
                streamId = streamId,
                sequence = sequence,
                timestamp = timestamp,
                flags = flags,
                payload = payload
            )
        }
    }

    fun serialize(): ByteArray {
        val payloadLen = payload.size
        val totalSize = HEADER_SIZE + payloadLen
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_BYTES)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(packetType.value)
        buffer.put(flags)
        buffer.put(pathId)
        buffer.putInt(sessionId)
        buffer.putInt(streamId)
        buffer.putLong(sequence)
        buffer.putLong(timestamp)
        buffer.putShort(payloadLen.toShort())

        // Calculate CRC32 over header_pre (32 bytes) + payload
        val crc = CRC32()
        crc.update(buffer.array(), 0, 32)
        crc.update(payload, 0, payloadLen)
        buffer.putInt(crc.value.toInt())

        if (payloadLen > 0) {
            buffer.put(payload)
        }

        return buffer.array()
    }

    override fun toString(): String {
        return "BondPacket(type=$packetType, path=$pathId, session=$sessionId, seq=$sequence, len=${payload.size})"
    }
}
