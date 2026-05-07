package com.btscreenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        const val ACTION_START = "com.btscreenshare.START_CAPTURE"
        const val ACTION_STOP = "com.btscreenshare.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private var currentMediaProjection: MediaProjection? = null
        private var currentEncoder: VideoEncoder? = null

        fun getEncoder(): VideoEncoder? = currentEncoder
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var screenWidth = VideoEncoder.WIDTH
    private var screenHeight = VideoEncoder.HEIGHT
    private var screenDpi = 320
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Use the smaller of screen size or 720p to avoid encoding issues
        screenWidth = minOf(metrics.widthPixels, VideoEncoder.WIDTH)
        screenHeight = minOf(metrics.heightPixels, VideoEncoder.HEIGHT)
        screenDpi = metrics.densityDpi
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDpi}dpi")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultData != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "No result data provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY // Changed from START_NOT_STICKY to help restart if killed
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        // Acquire WakeLock to prevent CPU sleep
        acquireWakeLock()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        currentMediaProjection = mediaProjection

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
            }
        }, null)

        // Create encoder
        encoder = VideoEncoder()
        currentEncoder = encoder

        // Set up the encoder callback to feed the StreamServer
        encoder?.setCallback(object : VideoEncoder.Callback {
            override fun onEncodedFrame(data: ByteArray, isKeyFrame: Boolean) {
                // Feed frame to StreamServer via queue
                StreamServerHolder.streamServer?.queueFrame(data, isKeyFrame)
            }

            override fun onEncoderError(error: String) {
                Log.e(TAG, "Encoder error: $error")
            }
        })

        // Start encoder to get the input surface
        encoder?.start()
        val inputSurface = encoder?.inputSurface

        // Pass encoder reference to StreamServer for key frame requests
        StreamServerHolder.streamServer?.setEncoder(encoder!!)

        if (inputSurface != null) {
            // Create VirtualDisplay that captures the screen into the encoder's input surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "BTScreenShare",
                screenWidth,
                screenHeight,
                screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )
            Log.d(TAG, "VirtualDisplay created, capturing screen at ${screenWidth}x${screenHeight}")

            // Show overlay to keep process foreground on Honor/Huawei devices
            OverlayManager.show(this)

            // Set SPS/PPS on the server once encoder produces them
            Thread {
                // Wait for SPS/PPS to be available
                var attempts = 0
                while (encoder?.getSpsData() == null && attempts < 100) {
                    Thread.sleep(50)
                    attempts++
                }
                val sps = encoder?.getSpsData()
                val pps = encoder?.getPpsData()
                if (sps != null && pps != null) {
                    StreamServerHolder.streamServer?.setSpsPps(sps, pps)
                    Log.d(TAG, "SPS/PPS set on StreamServer")
                }
            }.start()
        } else {
            Log.e(TAG, "Failed to get encoder input surface")
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BTScreenShare:ScreenCaptureWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10 minutes max, will be re-acquired if needed
                Log.d(TAG, "WakeLock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun stopCapture() {
        // Hide overlay
        OverlayManager.hide()

        releaseWakeLock()
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.stop()
        encoder = null
        currentEncoder = null
        mediaProjection?.stop()
        mediaProjection = null
        currentMediaProjection = null
        Log.d(TAG, "Capture stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_HIGH // Changed from LOW to HIGH
        ).apply {
            description = "Screen capture service notification"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, StreamShareActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_share_screen)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH) // Added for older devices
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}

/**
 * Singleton holder to bridge between the service and activities
 */
object StreamServerHolder {
    var streamServer: StreamServer? = null
}