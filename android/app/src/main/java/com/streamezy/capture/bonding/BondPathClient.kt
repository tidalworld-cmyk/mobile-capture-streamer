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
    private val authToken: String = "",
    private val playoutDelayMs: Double = 1000.0
) {
    companion object {
        private const val TAG = "BondPathClient"
        private const val HEARTBEAT_INTERVAL_MS = 1000L
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var fallbackAddress: InetAddress? = null
    private val isRunning = AtomicBoolean(false)
    private var receiverThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var consecutiveSendErrors = 0

    var onPacketAcked: ((BondPacket) -> Unit)? = null
    var onNackReceived: ((List<Long>) -> Unit)? = null
    var onDownlinkReceived: ((ByteArray) -> Unit)? = null
    var onAuthFailed: ((String) -> Unit)? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        Thread({
            try {
                resolveServerAddress()

                val s = DatagramSocket()
                val net = path.network
                if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    net.bindSocket(s)
                    Log.i(TAG, "Socket successfully bound to Android Network ${path.name} ($net)")
                }
                socket = s

                path.lastHeartbeatTimestamp = System.currentTimeMillis()
                path.status = PathStatus.ONLINE
                consecutiveSendErrors = 0

                // Send HELLO packet with authentication token and LiveU playout delay
                sendHelloPacket()

                // Start UDP receive loop
                receiverThread = Thread({ receiveLoop() }, "BondRx-${path.name}").apply { start() }
                // Start periodic heartbeat loop
                heartbeatThread = Thread({ heartbeatLoop() }, "BondHb-${path.name}").apply { start() }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start path client for ${path.name}", e)
                path.status = PathStatus.OFFLINE
                stop()
            }
        }, "BondClientInit-${path.name}").start()
    }

    private fun resolveServerAddress() {
        try {
            if (serverHost == "187.53.143.47") {
                serverAddress = InetAddress.getByName("187.53.143.47")
                return
            }

            val addrs = if (path.network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    path.network!!.getAllByName(serverHost)
                } catch (e: Exception) {
                    InetAddress.getAllByName(serverHost)
                }
            } else {
                InetAddress.getAllByName(serverHost)
            }

            val v4 = addrs.firstOrNull { it is java.net.Inet4Address }
            val v6 = addrs.firstOrNull { it is java.net.Inet6Address }

            serverAddress = v4 ?: v6 ?: InetAddress.getByName("187.53.143.47")
            fallbackAddress = if (serverAddress == v4) v6 else v4
            Log.i(TAG, "Resolved server address for ${path.name}: primary=$serverAddress, fallback=$fallbackAddress")
        } catch (e: Exception) {
            Log.w(TAG, "DNS resolution failed for $serverHost on ${path.name}, falling back to static IP 187.53.143.47: ${e.message}")
            serverAddress = InetAddress.getByName("187.53.143.47")
        }
    }

    private fun sendHelloPacket() {
        val helloPayload = if (authToken.isNotBlank()) {
            "{\"token\":\"$authToken\",\"client_name\":\"Android-${path.name}\",\"path_name\":\"${path.name}\",\"path_id\":${path.pathId},\"playout_delay_ms\":$playoutDelayMs}".toByteArray()
        } else {
            "{\"client_name\":\"Android-${path.name}\",\"path_name\":\"${path.name}\",\"path_id\":${path.pathId},\"playout_delay_ms\":$playoutDelayMs}".toByteArray()
        }

        sendPacket(
            BondPacket(
                packetType = PacketType.HELLO,
                pathId = path.pathId,
                sessionId = sessionId,
                payload = helloPayload
            )
        )
    }

    fun sendPacket(packet: BondPacket): Boolean {
        var s = socket
        var dest = serverAddress
        if (s == null || dest == null) {
            val deadline = System.currentTimeMillis() + 300
            while ((socket == null || serverAddress == null) && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(10) } catch (_: Exception) {}
            }
            s = socket ?: return false
            dest = serverAddress ?: return false
        }

        return try {
            val bytes = packet.serialize()
            val dgram = DatagramPacket(bytes, bytes.size, dest, serverPort)
            s.send(dgram)
            path.packetsSent++
            path.bytesSent += bytes.size
            consecutiveSendErrors = 0
            if (path.status == PathStatus.FAILING || path.status == PathStatus.CONNECTING) {
                path.status = PathStatus.ONLINE
            }
            true
        } catch (e: Exception) {
            // Attempt fallback address family (e.g. IPv6 if IPv4 failed on cellular)
            val fb = fallbackAddress
            if (fb != null && fb != dest) {
                try {
                    val bytes = packet.serialize()
                    val dgram = DatagramPacket(bytes, bytes.size, fb, serverPort)
                    s.send(dgram)
                    path.packetsSent++
                    path.bytesSent += bytes.size
                    // Swap primary to fallback since fallback worked
                    serverAddress = fb
                    fallbackAddress = dest
                    consecutiveSendErrors = 0
                    if (path.status == PathStatus.FAILING || path.status == PathStatus.CONNECTING) {
                        path.status = PathStatus.ONLINE
                    }
                    return true
                } catch (_: Exception) {}
            }

            consecutiveSendErrors++
            if (consecutiveSendErrors % 10 == 1) {
                Log.w(TAG, "Send error on ${path.name} (#$consecutiveSendErrors): ${e.message}")
            }
            if (consecutiveSendErrors >= 5 && path.status == PathStatus.ONLINE) {
                path.status = PathStatus.FAILING
            }
            false
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(8192)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning.get()) {
            try {
                val s = socket
                if (s == null || s.isClosed) {
                    Thread.sleep(50)
                    continue
                }
                s.receive(packet)
                val bondPkt = BondPacket.deserialize(packet.data, packet.length)

                if (bondPkt.packetType == PacketType.ACK) {
                    val now = System.currentTimeMillis()
                    val rtt = (now - bondPkt.timestamp).coerceAtLeast(1L)
                    path.updateMetrics(rtt, path.lossRate, path.estimatedUploadMbps)
                    path.packetsAcked++
                    path.status = PathStatus.ONLINE
                    consecutiveSendErrors = 0
                    onPacketAcked?.invoke(bondPkt)
                } else if (bondPkt.packetType == PacketType.DOWNLINK) {
                    path.lastHeartbeatTimestamp = System.currentTimeMillis()
                    if (path.status == PathStatus.FAILING) path.status = PathStatus.ONLINE
                    if (bondPkt.payload.isNotEmpty()) {
                        onDownlinkReceived?.invoke(bondPkt.payload)
                    }
                } else if (bondPkt.packetType == PacketType.NACK) {
                    path.lastHeartbeatTimestamp = System.currentTimeMillis()
                    val missingSeqs = BondPacket.decodeNackPayload(bondPkt.payload)
                    if (missingSeqs.isNotEmpty()) {
                        Log.d(TAG, "Received NACK on ${path.name} for ${missingSeqs.size} missing packets: $missingSeqs")
                        onNackReceived?.invoke(missingSeqs)
                    }
                } else if (bondPkt.packetType == PacketType.AUTH_FAIL) {
                    val reason = String(bondPkt.payload)
                    Log.e(TAG, "BondStream Authentication Failed on ${path.name}: $reason")
                    path.status = PathStatus.OFFLINE
                    onAuthFailed?.invoke(reason)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    // If socket was rebound, continue loop on the new socket
                    val s = socket
                    if (s != null && !s.isClosed) {
                        continue
                    }
                    try { Thread.sleep(50) } catch (_: Exception) {}
                }
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

                // Timeout check: allow 10 seconds grace period for cellular / Wi-Fi handover jitter before marking failing
                val idle = System.currentTimeMillis() - path.lastHeartbeatTimestamp
                if (idle > 10000 && path.status == PathStatus.ONLINE) {
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
        } catch (_: Exception) {}
        socket = null
        receiverThread?.interrupt()
        heartbeatThread?.interrupt()
        path.status = PathStatus.DISCONNECTED
    }

    fun rebindNetwork(newNetwork: android.net.Network) {
        Thread({
            try {
                path.network = newNetwork
                resolveServerAddress()

                val oldSocket = socket
                val newSocket = DatagramSocket()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    newNetwork.bindSocket(newSocket)
                }
                socket = newSocket

                try {
                    oldSocket?.close()
                } catch (_: Exception) {}

                path.status = PathStatus.ONLINE
                path.lastHeartbeatTimestamp = System.currentTimeMillis()
                consecutiveSendErrors = 0
                Log.i(TAG, "[FAILOVER] Rebound DatagramSocket to refreshed Android Network ${path.name} ($newNetwork)")

                // Send immediate HELLO on new network transport
                sendHelloPacket()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to rebind socket to network ${path.name}: ${e.message}")
            }
        }, "BondRebind-${path.name}").start()
    }
}
