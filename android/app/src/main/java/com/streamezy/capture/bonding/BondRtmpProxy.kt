package com.streamezy.capture.bonding

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BondStream Local RTMP Loopback Proxy.
 *
 * Runs a local TCP server on 127.0.0.1 (e.g. port 19350).
 * When RootEncoder connects and streams to rtmp://127.0.0.1:19350/...,
 * this proxy reads outgoing TCP byte chunks from RootEncoder, fragments them
 * into safe MTU units (<= 1380 bytes), and pipes them into BondSession.sendData().
 *
 * It also receives return RTMP handshake and control bytes from the VPS via
 * BondSession.onDownlinkData and writes them directly back to RootEncoder's TCP input.
 */
class BondRtmpProxy(
    private val bondSession: BondSession,
    private val preferredPort: Int = 19350
) {
    companion object {
        private const val TAG = "BondRtmpProxy"
        private const val CHUNK_SIZE = 1380
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientIn: InputStream? = null
    private var clientOut: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var upstreamThread: Thread? = null

    var boundPort: Int = preferredPort
        private set

    fun start(): Int {
        if (isRunning.getAndSet(true)) return boundPort

        try {
            // Bind to loopback address on preferred port, or ephemeral port on conflict
            serverSocket = try {
                ServerSocket(preferredPort, 1, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            }
            boundPort = serverSocket!!.localPort
            Log.i(TAG, "BondRtmpProxy listening on 127.0.0.1:$boundPort")

            // Wire downlink data from VPS back to RootEncoder
            bondSession.onDownlinkData = { data ->
                try {
                    synchronized(this) {
                        clientOut?.write(data)
                        clientOut?.flush()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Downlink write to RootEncoder failed: ${e.message}")
                }
            }

            serverThread = Thread({ acceptLoop() }, "BondRtmpProxy-Accept").apply { start() }
            return boundPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind BondRtmpProxy", e)
            isRunning.set(false)
            return -1
        }
    }

    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val server = serverSocket ?: break
                val sock = server.accept()
                sock.tcpNoDelay = true
                sock.sendBufferSize = 64 * 1024
                sock.receiveBufferSize = 64 * 1024

                synchronized(this) {
                    clientSocket = sock
                    clientIn = sock.getInputStream()
                    clientOut = sock.getOutputStream()
                }
                Log.i(TAG, "RootEncoder connected to BondRtmpProxy loopback!")

                upstreamThread = Thread({ pumpUpstream() }, "BondRtmpProxy-Upstream").apply { start() }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Accept loop error: ${e.message}")
                }
                break
            }
        }
    }

    private fun pumpUpstream() {
        val buffer = ByteArray(CHUNK_SIZE)
        try {
            val streamIn = clientIn ?: return
            while (isRunning.get()) {
                val bytesRead = streamIn.read(buffer)
                if (bytesRead <= 0) break

                val chunk = if (bytesRead == CHUNK_SIZE) {
                    buffer.clone()
                } else {
                    buffer.copyOf(bytesRead)
                }

                // Send via multi-path bonded session
                bondSession.sendData(chunk)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.w(TAG, "Upstream pump error: ${e.message}")
            }
        } finally {
            closeClient()
        }
    }

    private fun closeClient() {
        synchronized(this) {
            try { clientIn?.close() } catch (_: Exception) {}
            try { clientOut?.close() } catch (_: Exception) {}
            try { clientSocket?.close() } catch (_: Exception) {}
            clientIn = null
            clientOut = null
            clientSocket = null
        }
    }

    fun stop() {
        isRunning.set(false)
        closeClient()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        bondSession.onDownlinkData = null
        Log.i(TAG, "BondRtmpProxy stopped.")
    }
}