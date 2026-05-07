package com.btscreenshare

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class StreamClient {

    companion object {
        private const val TAG = "StreamClient"
        const val PORT = 9000

        // Protocol constants: Server -> Client
        const val MSG_TYPE_VIDEO_FRAME: Byte = 0x01
        const val MSG_TYPE_SPS_PPS: Byte = 0x02

        // Protocol constants: Client -> Server
        const val CTRL_REQUEST_KEY_FRAME: Byte = 0x01
    }

    interface Callback {
        fun onConnected(serverIp: String)
        fun onSpsPpsReceived(sps: ByteArray, pps: ByteArray)
        fun onFrameReceived(data: ByteArray, isKeyFrame: Boolean)
        fun onDisconnected()
        fun onError(error: String)
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var callback: Callback? = null
    private var receiveThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val sendLock = Object()
    private var totalBytesReceived = 0L
    private var totalFramesReceived = 0L
    private var keyFrameRequestsSent = 0L

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun connect(serverIp: String) {
        isRunning.set(true)
        receiveThread = Thread({
            try {
                Log.d(TAG, "Connecting to $serverIp:$PORT...")
                socket = Socket(serverIp, PORT).apply {
                    tcpNoDelay = true
                    sendBufferSize = 64 * 1024
                    receiveBufferSize = 256 * 1024
                    soTimeout = 5000
                }
                input = DataInputStream(socket!!.getInputStream())
                output = DataOutputStream(socket!!.getOutputStream())
                Log.d(TAG, "Connected to $serverIp:$PORT (TCP_NODELAY enabled, bidirectional)")

                callback?.onConnected(serverIp)

                // Read SPS and PPS (first message with type byte)
                readSpsPps()

                // Main receive loop
                receiveLoop()
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Connection error", e)
                    callback?.onError("Connection error: ${e.message}")
                }
            }
        }, "StreamClientThread").apply { start() }
    }

    private fun readSpsPps() {
        try {
            val inp = input ?: return
            // Read type byte (expecting MSG_TYPE_SPS_PPS = 0x02)
            val msgType = inp.readByte()
            if (msgType != MSG_TYPE_SPS_PPS) {
                Log.w(TAG, "Expected SPS/PPS message type (0x02), got: $msgType")
                callback?.onError("Protocol error: expected SPS/PPS")
                return
            }

            // Read SPS
            val spsLen = inp.readInt()
            val sps = ByteArray(spsLen)
            inp.readFully(sps)

            // Read PPS
            val ppsLen = inp.readInt()
            val pps = ByteArray(ppsLen)
            inp.readFully(pps)

            Log.d(TAG, "Received SPS (${sps.size} bytes) and PPS (${pps.size} bytes)")
            totalBytesReceived += 1 + 4 + spsLen + 4 + ppsLen // type + lengths + data
            callback?.onSpsPpsReceived(sps, pps)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read SPS/PPS", e)
            callback?.onError("Failed to read SPS/PPS: ${e.message}")
        }
    }

    private fun receiveLoop() {
        val inp = input ?: return
        Log.d(TAG, "Receive loop started")

        while (isRunning.get()) {
            try {
                // Read type byte with timeout protection
                val msgType = inp.readByte()
                if (msgType != MSG_TYPE_VIDEO_FRAME) {
                    Log.w(TAG, "Unexpected message type: $msgType (expected 0x01 video frame)")
                    continue
                }

                // Read 4-byte big-endian length
                val length = inp.readInt()
                if (length <= 0 || length > 2 * 1024 * 1024) {
                    Log.w(TAG, "Invalid frame length: $length")
                    continue
                }

                // Read frame data using read() instead of readFully() for better timeout handling
                val data = ByteArray(length)
                var bytesRead = 0
                while (bytesRead < length) {
                    val remaining = length - bytesRead
                    val read = inp.read(data, bytesRead, remaining)
                    if (read == -1) {
                        Log.w(TAG, "EOF while reading frame data (got $bytesRead of $length)")
                        callback?.onDisconnected()
                        return
                    }
                    bytesRead += read
                }

                totalBytesReceived += 1 + 4 + length
                totalFramesReceived++

                val isKeyFrame = isKeyFrame(data)
                callback?.onFrameReceived(data, isKeyFrame)

                // Log progress every 30 frames
                if (totalFramesReceived % 30 == 0L) {
                    Log.d(TAG, "Client received $totalFramesReceived frames, ${totalBytesReceived / 1024}KB")
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Socket read timeout, retrying...")
                continue
            } catch (e: java.io.EOFException) {
                if (isRunning.get()) {
                    Log.d(TAG, "Connection closed by server")
                    callback?.onDisconnected()
                }
                break
            } catch (e: java.io.IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Receive error", e)
                    callback?.onDisconnected()
                }
                break
            }
        }
        Log.d(TAG, "Receive loop exited")
    }

    /**
     * Check if H.264 frame is a key frame (IDR).
     * NAL unit type 5 indicates IDR frame.
     */
    private fun isKeyFrame(data: ByteArray): Boolean {
        if (data.size < 5) return false
        // H.264 NAL unit starts with 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01
        var nalStart = 0
        if (data[0].toInt() == 0x00 && data[1].toInt() == 0x00) {
            if (data[2].toInt() == 0x00 && data[3].toInt() == 0x01) {
                nalStart = 4
            } else if (data[2].toInt() == 0x01) {
                nalStart = 3
            }
        }
        if (nalStart == 0 || nalStart >= data.size) return false
        val nalType = (data[nalStart].toInt() and 0x1F) // NAL unit type is lower 5 bits
        return nalType == 5 // IDR frame
    }

    /**
     * Send key frame request to server.
     * Called by decoder when it needs a reference frame to recover.
     */
    fun sendKeyFrameRequest() {
        synchronized(sendLock) {
            val out = output
            if (out != null && isRunning.get() && socket?.isConnected == true) {
                try {
                    out.writeByte(CTRL_REQUEST_KEY_FRAME.toInt())
                    out.flush()
                    keyFrameRequestsSent++
                    Log.d(TAG, "Key frame request sent to server (total: $keyFrameRequestsSent)")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send key frame request", e)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try { socket?.close() } catch (_: Exception) {}
        receiveThread?.interrupt()
        socket = null
        input = null
        output = null
        Log.d(TAG, "Client stopped. Received $totalFramesReceived frames, ${totalBytesReceived / 1024}KB, key frame requests sent: $keyFrameRequestsSent")
    }

    fun isRunning(): Boolean = isRunning.get()
    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed
    fun getTotalBytesReceived(): Long = totalBytesReceived
    fun getTotalFramesReceived(): Long = totalFramesReceived
    fun getKeyFrameRequestsSent(): Long = keyFrameRequestsSent
}