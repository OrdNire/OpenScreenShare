package com.btscreenshare

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class StreamViewActivity : AppCompatActivity(), StreamViewService.DisconnectListener {

    companion object {
        private const val TAG = "StreamViewActivity"
    }

    private lateinit var aspectRatioLayout: AspectRatioFrameLayout
    private lateinit var surfaceRemote: SurfaceView
    private lateinit var tvStatusTop: TextView
    private lateinit var tvStatsInfo: TextView
    private lateinit var btnStop: MaterialButton
    private lateinit var overlayStatus: View

    private var streamViewService: StreamViewService? = null
    private var isServiceBound = false
    private var isSurfaceReady = false
    private var decoderWasReleased = false
    private var remoteIp: String = "Unknown"
    private val handler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    @Volatile private var lastFrameDecodedTime = 0L
    private var frozenWarningShown = false
    @Volatile private var isNavigatingHome = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as StreamViewService.LocalBinder
            streamViewService = binder.getService()
            streamViewService?.setDisconnectListener(this@StreamViewActivity)
            isServiceBound = true
            Log.d(TAG, "Service connected")
            initIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            streamViewService?.setDisconnectListener(null)
            streamViewService = null
            isServiceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_stream_view)

        aspectRatioLayout = findViewById(R.id.aspectRatioLayout)
        surfaceRemote = findViewById(R.id.surfaceRemote)
        tvStatusTop = findViewById(R.id.tvStatusTop)
        tvStatsInfo = findViewById(R.id.tvStatsInfo)
        btnStop = findViewById(R.id.btnStop)
        overlayStatus = findViewById(R.id.overlayStatus)

        remoteIp = intent.getStringExtra(PairingActivity.EXTRA_REMOTE_IP) ?: "Unknown"
        tvStatusTop.text = getString(R.string.status_waiting)

        btnStop.setOnClickListener {
            stopViewing()
        }

        // Start foreground service
        val serviceIntent = Intent(this, StreamViewService::class.java).apply {
            action = StreamViewService.ACTION_START
            putExtra(StreamViewService.EXTRA_SERVER_IP, remoteIp)
        }
        startForegroundService(serviceIntent)

        // Bind to service
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Wait for surface to be ready, then configure decoder
        surfaceRemote.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created")
                isSurfaceReady = true
                initIfReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
                isSurfaceReady = false
                decoderWasReleased = true
                streamViewService?.releaseDecoder()
                Log.d(TAG, "Decoder released due to surface destruction")
            }
        })

        startStatsUpdate()
        startWatchdog()
    }

    private fun initIfReady() {
        if (!isSurfaceReady || !isServiceBound) {
            Log.d(TAG, "initIfReady: waiting for surface=${isSurfaceReady}, service=${isServiceBound}")
            return
        }
        val service = streamViewService
        val surface = surfaceRemote.holder.surface
        if (service == null || !surface.isValid) {
            Log.w(TAG, "initIfReady: service or surface invalid")
            return
        }

        if (decoderWasReleased) {
            // Surface was recreated after destruction - recreate decoder
            Log.d(TAG, "initIfReady: recreating decoder after surface recreation")
            service.recreateDecoder(surface)
            decoderWasReleased = false
            Thread { service.requestKeyFrame() }.start()
        } else {
            // Initial creation
            Log.d(TAG, "initIfReady: configuring decoder")
            service.configureDecoder(surface)
        }

        // Set decoder callback
        service.setDecoderCallback(object : VideoDecoder.Callback {
            override fun onDecoderError(error: String) {
                Log.w(TAG, "Decoder error: $error")
            }

            override fun onFrameDecoded() {
                lastFrameDecodedTime = System.currentTimeMillis()
                frozenWarningShown = false
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                runOnUiThread {
                    Log.d(TAG, "Video size changed: ${width}x${height}")
                    aspectRatioLayout.setAspectRatio(width, height)
                }
            }

            override fun onKeyFrameRequest() {
                Log.d(TAG, "Decoder requesting key frame")
                streamViewService?.requestKeyFrame()
            }
        })

        // Resume decoder if it was paused
        service.resumeDecoder()

        // Connect via service (only if not already connected)
        if (!service.isStreamRunning()) {
            connectToServer()
        }
    }

    private fun connectToServer() {
        streamViewService?.startStream(remoteIp, object : StreamClient.Callback {
            override fun onConnected(serverIp: String) {
                runOnUiThread {
                    tvStatusTop.text = getString(R.string.status_viewing, serverIp)
                }
            }

            override fun onSpsPpsReceived(sps: ByteArray, pps: ByteArray) {
                streamViewService?.feedSpsPps(sps, pps)
            }

            override fun onFrameReceived(data: ByteArray, isKeyFrame: Boolean) {
                streamViewService?.decodeFrame(data, isKeyFrame)
            }

            override fun onDisconnected() {
                Log.d(TAG, "StreamClient disconnected")
                // Notify the service, which will relay to the activity's DisconnectListener
                streamViewService?.notifyRemoteDisconnected()
            }

            override fun onError(error: String) {
                Log.e(TAG, "StreamClient error: $error")
                // Connection errors should also trigger disconnect flow
                streamViewService?.notifyRemoteDisconnected()
            }
        })
    }

    /**
     * Called by StreamViewService when the remote sharer disconnects.
     * Must be called on the main thread (service should ensure this).
     */
    override fun onRemoteDisconnected() {
        if (isNavigatingHome || isFinishing || isDestroyed) {
            Log.d(TAG, "onRemoteDisconnected: already navigating or destroyed, skipping")
            return
        }
        isNavigatingHome = true
        Log.d(TAG, "Remote sharer disconnected, navigating to home")

        tvStatusTop.text = getString(R.string.status_disconnected)
        Toast.makeText(this, "共享已结束", Toast.LENGTH_SHORT).show()

        // Brief delay so the user can see the message, then navigate home
        handler.postDelayed({
            navigateToHome()
        }, 1500)
    }

    /**
     * Clean up all resources and navigate back to LanConnectActivity (home page).
     */
    private fun navigateToHome() {
        if (isFinishing || isDestroyed) return

        stopStatsUpdate()
        stopWatchdog()

        // Unbind and stop the service
        if (isServiceBound) {
            streamViewService?.setDisconnectListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        val stopIntent = Intent(this, StreamViewService::class.java).apply {
            action = StreamViewService.ACTION_STOP
        }
        startService(stopIntent)

        // Navigate back to home
        val homeIntent = Intent(this, LanConnectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun stopViewing() {
        isNavigatingHome = true
        stopStatsUpdate()
        stopWatchdog()

        // Unbind from service
        if (isServiceBound) {
            streamViewService?.setDisconnectListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // Stop the service
        val stopIntent = Intent(this, StreamViewService::class.java).apply {
            action = StreamViewService.ACTION_STOP
        }
        startService(stopIntent)

        finish()
    }

    private fun startStatsUpdate() {
        statsRunnable = object : Runnable {
            override fun run() {
                val service = streamViewService
                if (service != null && service.isStreamRunning()) {
                    val (bytes, frames) = service.getStats()
                    tvStatsInfo.text = "帧数: $frames | 数据: ${bytes / 1024}KB"
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(statsRunnable!!, 1000)
    }

    private fun stopStatsUpdate() {
        statsRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun startWatchdog() {
        lastFrameDecodedTime = System.currentTimeMillis()
        watchdogRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val timeSinceLastFrame = now - lastFrameDecodedTime

                if (timeSinceLastFrame > 10_000 && !frozenWarningShown) {
                    Log.w(TAG, "Video appears frozen - no frames for ${timeSinceLastFrame}ms")
                    frozenWarningShown = true
                    Toast.makeText(this@StreamViewActivity, "视频似乎卡住了，请检查连接。", Toast.LENGTH_LONG).show()
                }

                handler.postDelayed(this, 5_000)
            }
        }
        handler.postDelayed(watchdogRunnable!!, 5_000)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onStart() {
        super.onStart()
        streamViewService?.resumeDecoder()
        Log.d(TAG, "Activity started, decoder resumed")
    }

    override fun onStop() {
        super.onStop()
        streamViewService?.pauseDecoder()
        Log.d(TAG, "Activity stopped, decoder paused")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: ${newConfig.orientation}")
        // Activity is not recreated, decoder continues running
    }

    override fun onDestroy() {
        stopStatsUpdate()
        stopWatchdog()

        if (isServiceBound) {
            streamViewService?.setDisconnectListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }

        super.onDestroy()
    }
}