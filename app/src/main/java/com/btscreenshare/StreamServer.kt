package com.btscreenshare

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class StreamServer {

    companion object {
        private const val TAG = "StreamServer"
        const val PORT = 9000

        // Protocol constants: Server -> Client
        const val MSG_TYPE_VIDEO_FRAME: Byte = 0x01
        const val MSG_TYPE_SPS_PPS: Byte = 0x02

        // Protocol constants: Client -> Server
        const val CTRL_REQUEST_KEY_FRAME: Byte = 0x01
    }

    interface Callback {
        fun onClientConnected(clientIp: String)
        fun onClientDisconnected()
        fun onServerError(error: String)
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var callback: Callback? = null
    private var serverThread: Thread? = null
    private var sendThread: Thread? = null
    private var controlThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Encoder reference for key frame requests
    private var encoder: VideoEncoder? = null

    // Bounded frame queue: encoder adds, sender takes
    private val frameQueue = ArrayBlockingQueue<ByteArray>(10)
    private val writeLock = Object()

    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var totalBytesSent = 0L
    private var totalFramesSent = 0L
    private var keyFrameRequestsReceived = 0L

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun setEncoder(encoder: VideoEncoder) {
        this.encoder = encoder
        Log.d(TAG, "Encoder reference set")
    }

    fun start() {
        isRunning.set(true)
        serverThread = Thread({
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Server listening on port $PORT")

                val socket = serverSocket?.accept()
                if (socket != null && isRunning.get()) {
                    clientSocket = socket
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 256 * 1024
                    socket.receiveBufferSize = 64 * 1024
                    output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
                    input = DataInputStream(socket.getInputStream())
                    val clientIp = socket.inetAddress.hostAddress ?: "unknown"
                    Log.d(TAG, "Client connected: $clientIp")

                    callback?.onClientConnected(clientIp)

                    // Send SPS/PPS as first message
                    sendSpsPps()

                    // Start sender thread
                    startSendLoop()

                    // Start control receive thread (client -> server)
                    startControlReceiveLoop()
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server error", e)
                    callback?.onServerError("Server error: ${e.message}")
                }
            }
        }, "StreamServerThread").apply { start() }
    }

    private fun startSendLoop() {
        sendThread = Thread({
            while (isRunning.get()) {
                try {
                    val frame = frameQueue.take()  // blocks until frame available

                    synchronized(writeLock) {
                        val out = output ?: return@Thread
                        out.writeByte(MSG_TYPE_VIDEO_FRAME.toInt())
                        out.writeInt(frame.size)
                        out.write(frame)
                        out.flush()
                    }
                    totalBytesSent += frame.size + 5
                    totalFramesSent++

                    if (totalFramesSent % 30 == 0L) {
                        Log.d(TAG, "Server sent $totalFramesSent frames, ${totalBytesSent / 1024}KB, queue=${frameQueue.size}")
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: IOException) {
                    Log.d(TAG, "Client disconnected (broken pipe): ${e.message}")
                    break
                }
            }
        }, "StreamSendThread").apply { start() }
    }

    private fun startControlReceiveLoop() {
        controlThread = Thread({
            val inp = input ?: return@Thread
            while (isRunning.get()) {
                try {
                    // Read 1-byte control message from client
                    val msgType = inp.readByte()
                    when (msgType) {
                        CTRL_REQUEST_KEY_FRAME -> {
                            keyFrameRequestsReceived++
                            Log.d(TAG, "Key frame request received from client (total: $keyFrameRequestsReceived)")
                            encoder?.forceKeyFrame()
                        }
                        else -> {
                            Log.w(TAG, "Unknown control message type: $msgType")
                        }
                    }
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        Log.d(TAG, "Control receive: client disconnected")
                        callback?.onClientDisconnected()
                    }
                    break
                }
            }
        }, "ControlReceiveThread").apply { start() }
    }

    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        spsData = sps
        ppsData = pps
        if (isClientConnected()) {
            try {
                synchronized(writeLock) {
                    sendSpsPpsRaw()
                }
                Log.d(TAG, "Sent SPS/PPS immediately to connected client")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send SPS/PPS", e)
            }
        }
    }

    private fun sendSpsPps() {
        val sps = spsData ?: return
        val pps = ppsData ?: return
        try {
            synchronized(writeLock) {
                sendSpsPpsRaw()
            }
            Log.d(TAG, "Sent SPS/PPS to client")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send SPS/PPS", e)
        }
    }

    private fun sendSpsPpsRaw() {
        val out = output ?: return
        val sps = spsData ?: return
        val pps = ppsData ?: return
        // Protocol: [0x02][4-byte sps_len][sps][4-byte pps_len][pps]
        out.writeByte(MSG_TYPE_SPS_PPS.toInt())
        out.writeInt(sps.size)
        out.write(sps)
        out.writeInt(pps.size)
        out.write(pps)
        out.flush()
    }

    /**
     * Called by encoder callback. Adds frame to bounded queue.
     */
    fun queueFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (!isRunning.get()) return
        // If queue is full, discard oldest frame to make room
        if (!frameQueue.offer(data)) {
            frameQueue.poll()  // discard oldest
            frameQueue.offer(data)
        }
    }

    fun stop() {
        isRunning.set(false)
        frameQueue.clear()
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
        sendThread?.interrupt()
        controlThread?.interrupt()
        output = null
        input = null
        clientSocket = null
        serverSocket = null
        encoder = null
        Log.d(TAG, "Server stopped. Sent $totalFramesSent frames, ${totalBytesSent / 1024}KB, key frame requests: $keyFrameRequestsReceived")
    }

    fun isRunning(): Boolean = isRunning.get()
    fun isClientConnected(): Boolean = clientSocket?.isConnected == true && !clientSocket!!.isClosed
    fun getTotalBytesSent(): Long = totalBytesSent
    fun getTotalFramesSent(): Long = totalFramesSent
    fun getKeyFrameRequestsReceived(): Long = keyFrameRequestsReceived
}