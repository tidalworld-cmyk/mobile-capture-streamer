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
    private val isRunning = AtomicBoolean(false)
    private var receiverThread: Thread? = null
    private var heartbeatThread: Thread? = null

    var onPacketAcked: ((BondPacket) -> Unit)? = null
    var onNackReceived: ((List<Long>) -> Unit)? = null
    var onDownlinkReceived: ((ByteArray) -> Unit)? = null
    var onAuthFailed: ((String) -> Unit)? = null

    fun start() {
        if (isRunning.getAndSet(true)) return

        Thread({
            try {
                // Background thread for DNS resolution and socket creation (prevents NetworkOnMainThreadException)
                serverAddress = try {
                    if (path.network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        path.network!!.getByName(serverHost)
                    } else {
                        InetAddress.getByName(serverHost)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DNS resolution failed for $serverHost on ${path.name}, falling back to static IP 187.53.143.47")
                    InetAddress.getByName("187.53.143.47")
                }

                socket = DatagramSocket()

                val net = path.network
                if (net != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    net.bindSocket(socket)
                    Log.i(TAG, "Socket successfully bound to Android Network ${path.name} ($net)")
                }

                path.lastHeartbeatTimestamp = System.currentTimeMillis()
                // Retain ONLINE status from AndroidNetworkManager so bonding scheduler immediately sends data
                path.status = PathStatus.ONLINE

                // Send HELLO packet with authentication token and LiveU playout delay
                val helloPayload = if (authToken.isNotBlank()) {
                    "{\"token\":\"$authToken\",\"client_name\":\"Android-${path.name}\",\"playout_delay_ms\":$playoutDelayMs}".toByteArray()
                } else {
                    "{\"client_name\":\"Android-${path.name}\",\"playout_delay_ms\":$playoutDelayMs}".toByteArray()
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
        }, "BondClientInit-${path.name}").start()
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

                // Timeout check: Allow 6 seconds grace period for cellular / Wi-Fi jitter before failing
                val idle = System.currentTimeMillis() - path.lastHeartbeatTimestamp
                if (idle > 6000 && path.status == PathStatus.ONLINE) {
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

    fun rebindNetwork(newNetwork: android.net.Network) {
        try {
            path.network = newNetwork
            val oldSocket = socket
            val newSocket = DatagramSocket()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                newNetwork.bindSocket(newSocket)
            }
            socket = newSocket
            try {
                oldSocket?.close()
            } catch (ignored: Exception) {}
            Log.i(TAG, "Rebound DatagramSocket to refreshed Android Network ${path.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rebind socket to network ${path.name}: ${e.message}")
        }
    }
}
