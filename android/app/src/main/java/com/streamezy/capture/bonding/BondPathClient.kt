package com.streamezy.capture.bonding

import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class BondPathClient(
    val path: NetworkPath,
    private val serverHost: String,
    private val serverPort: Int,
    private val sessionId: Int,
    private val authToken: String = ""
) {
    companion object {
        private const val TAG = "BondPathClient"
        private const val HEARTBEAT_INTERVAL_MS = 1000L
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val isRunning = AtomicBoolean(false)
    private var receiverThread: Thread? = null
    private var heartbeatThread: Thread? = null

    var onPacketAcked: ((BondPacket) -> Unit)? = null
    var onDownlinkReceived: ((ByteArray) -> Unit)? = null
    var onAuthFailed: ((String) -> Unit)? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        try {
            serverAddress = InetAddress.getByName(serverHost)
            socket = DatagramSocket()

            val net = path.network
            if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                net.bindSocket(socket)
                Log.i(TAG, "Socket successfully bound to Android Network ${path.name} ($net)")
            }

            path.status = PathStatus.CONNECTING

            // Send HELLO packet with authentication token if configured
            val helloPayload = if (authToken.isNotBlank()) {
                "{\"token\":\"$authToken\",\"client_name\":\"Android-${path.name}\"}".toByteArray()
            } else {
                "Android-${path.name}".toByteArray()
            }

            sendPacket(
                BondPacket(
                    packetType = PacketType.HELLO,
                    pathId = path.pathId,
                    sessionId = sessionId,
                    payload = helloPayload
                )
            )

            // Start UDP receive loop
            receiverThread = Thread({ receiveLoop() }, "BondRx-${path.name}").apply { start() }
            // Start periodic heartbeat loop
            heartbeatThread = Thread({ heartbeatLoop() }, "BondHb-${path.name}").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start path client for ${path.name}", e)
            path.status = PathStatus.OFFLINE
            stop()
        }
    }

    fun sendPacket(packet: BondPacket): Boolean {
        val s = socket ?: return false
        val dest = serverAddress ?: return false

        return try {
            val bytes = packet.serialize()
            val dgram = DatagramPacket(bytes, bytes.size, dest, serverPort)
            s.send(dgram)
            path.packetsSent++
            path.bytesSent += bytes.size
            true
        } catch (e: Exception) {
            Log.w(TAG, "Send error on ${path.name}: ${e.message}")
            path.status = PathStatus.FAILING
            false
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(8192)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning.get()) {
            try {
                val s = socket ?: break
                s.receive(packet)
                val bondPkt = BondPacket.deserialize(packet.data, packet.length)

                if (bondPkt.packetType == PacketType.ACK) {
                    val now = System.currentTimeMillis()
                    val rtt = (now - bondPkt.timestamp).coerceAtLeast(1L)
                    path.updateMetrics(rtt, path.lossRate, path.estimatedUploadMbps)
                    path.packetsAcked++
                    path.status = PathStatus.ONLINE
                    onPacketAcked?.invoke(bondPkt)
                } else if (bondPkt.packetType == PacketType.DOWNLINK) {
                    if (bondPkt.payload.isNotEmpty()) {
                        onDownlinkReceived?.invoke(bondPkt.payload)
                    }
                } else if (bondPkt.packetType == PacketType.AUTH_FAIL) {
                    val reason = String(bondPkt.payload)
                    Log.e(TAG, "BondStream Authentication Failed on ${path.name}: $reason")
                    path.status = PathStatus.OFFLINE
                    onAuthFailed?.invoke(reason)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Receive error on ${path.name}: ${e.message}")
                }
                break
            }
        }
    }

    private fun heartbeatLoop() {
        while (isRunning.get()) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
                if (!isRunning.get()) break

                sendPacket(
                    BondPacket(
                        packetType = PacketType.HEARTBEAT,
                        pathId = path.pathId,
                        sessionId = sessionId,
                        timestamp = System.currentTimeMillis()
                    )
                )

                // Timeout check
                val idle = System.currentTimeMillis() - path.lastHeartbeatTimestamp
                if (idle > 3000 && path.status == PathStatus.ONLINE) {
                    path.status = PathStatus.FAILING
                    Log.w(TAG, "Path ${path.name} heartbeat timeout (idle ${idle}ms)")
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat error on ${path.name}: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        receiverThread?.interrupt()
        heartbeatThread?.interrupt()
        path.status = PathStatus.DISCONNECTED
    }
}
