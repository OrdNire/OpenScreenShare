package com.btscreenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface

class StreamViewService : Service() {

    companion object {
        private const val TAG = "StreamViewService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "stream_view_channel"
        const val ACTION_START = "com.btscreenshare.START_STREAM_VIEW"
        const val ACTION_STOP = "com.btscreenshare.STOP_STREAM_VIEW"
        const val EXTRA_SERVER_IP = "server_ip"
    }

    private var streamClient: StreamClient? = null
    private var videoDecoder: VideoDecoder? = null
    private var serverIp: String? = null
    private var isPaused = false
    private val binder = LocalBinder()

    // Saved SPS/PPS for decoder recreation
    private var savedSps: ByteArray? = null
    private var savedPps: ByteArray? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamViewService = this@StreamViewService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                serverIp = intent.getStringExtra(EXTRA_SERVER_IP)
                if (serverIp != null) {
                    startForeground(NOTIFICATION_ID, createNotification(serverIp!!))
                    initializeComponents()
                    Log.d(TAG, "Service started foreground for $serverIp")
                }
            }
            ACTION_STOP -> {
                stopComponents()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun initializeComponents() {
        videoDecoder = VideoDecoder()
        streamClient = StreamClient()
        Log.d(TAG, "Components initialized")
    }

    fun configureDecoder(surface: Surface) {
        videoDecoder?.configure(surface)
        Log.d(TAG, "Decoder configured with surface")
    }

    fun startStream(serverIp: String, callback: StreamClient.Callback) {
        this.serverIp = serverIp
        streamClient?.setCallback(callback)
        streamClient?.connect(serverIp)
        Log.d(TAG, "Stream started to $serverIp")
    }

    fun setDecoderCallback(callback: VideoDecoder.Callback) {
        videoDecoder?.setCallback(callback)
    }

    fun feedSpsPps(sps: ByteArray, pps: ByteArray) {
        savedSps = sps
        savedPps = pps
        try {
            videoDecoder?.feedSpsPps(sps, pps)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "feedSpsPps failed: decoder released or surface invalid")
        }
    }

    fun decodeFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (!isPaused) {
            try {
                videoDecoder?.decodeFrame(data, isKeyFrame)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "decodeFrame failed: decoder released or surface invalid")
            }
        }
    }

    fun pauseDecoder() {
        isPaused = true
        Log.d(TAG, "Decoder paused")
    }

    fun resumeDecoder() {
        isPaused = false
        Log.d(TAG, "Decoder resumed")
    }

    fun releaseDecoder() {
        videoDecoder?.stop()
        videoDecoder = null
        Log.d(TAG, "Decoder released")
    }

    fun recreateDecoder(surface: Surface) {
        videoDecoder = VideoDecoder()
        videoDecoder?.configure(surface)
        // Feed saved SPS/PPS to new decoder
        savedSps?.let { sps ->
            savedPps?.let { pps ->
                try {
                    videoDecoder?.feedSpsPps(sps, pps)
                    Log.d(TAG, "Fed saved SPS/PPS to recreated decoder")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Failed to feed SPS/PPS to recreated decoder")
                }
            }
        }
        Log.d(TAG, "Decoder recreated with surface")
    }

    fun requestKeyFrame() {
        streamClient?.sendKeyFrameRequest()
    }

    fun isStreamRunning(): Boolean = streamClient?.isRunning() == true

    fun getStats(): Pair<Long, Long> {
        val bytes = streamClient?.getTotalBytesReceived() ?: 0L
        val frames = streamClient?.getTotalFramesReceived() ?: 0L
        return Pair(bytes, frames)
    }

    private fun stopComponents() {
        streamClient?.stop()
        streamClient = null
        videoDecoder?.stop()
        videoDecoder = null
        Log.d(TAG, "Components stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stream View Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps stream connection alive in background"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(serverIp: String): Notification {
        val intent = Intent(this, StreamViewActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Viewing Stream")
            .setContentText("Connected to $serverIp")
            .setSmallIcon(R.drawable.ic_share_screen)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopComponents()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
}