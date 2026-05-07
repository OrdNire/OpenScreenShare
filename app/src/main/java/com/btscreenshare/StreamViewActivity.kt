package com.btscreenshare

import android.os.Bundle
import android.os.Handler
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

class StreamViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StreamViewActivity"
    }

    private lateinit var aspectRatioLayout: AspectRatioFrameLayout
    private lateinit var surfaceRemote: SurfaceView
    private lateinit var tvStatusTop: TextView
    private lateinit var tvStatsInfo: TextView
    private lateinit var btnStop: MaterialButton
    private lateinit var overlayStatus: View

    private var streamClient: StreamClient? = null
    private var videoDecoder: VideoDecoder? = null
    private var remoteIp: String = "Unknown"
    private val handler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    @Volatile private var lastFrameDecodedTime = 0L
    private var frozenWarningShown = false

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

        // Initialize decoder
        videoDecoder = VideoDecoder()
        videoDecoder?.setCallback(object : VideoDecoder.Callback {
            override fun onDecoderError(error: String) {
                Log.w(TAG, "Decoder error: $error")
                // Don't show toast for every error - decoder will auto-recover
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
                streamClient?.sendKeyFrameRequest()
            }

        })

        // Wait for surface to be ready, then connect
        surfaceRemote.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created, configuring decoder and connecting")
                videoDecoder?.configure(holder.surface)
                connectToServer()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
            }
        })

        startStatsUpdate()
        startWatchdog()
    }

    private fun connectToServer() {
        streamClient = StreamClient().apply {
            setCallback(object : StreamClient.Callback {
                override fun onConnected(serverIp: String) {
                    runOnUiThread {
                        tvStatusTop.text = getString(R.string.status_viewing, serverIp)
                    }
                }

                override fun onSpsPpsReceived(sps: ByteArray, pps: ByteArray) {
                    videoDecoder?.feedSpsPps(sps, pps)
                }

                override fun onFrameReceived(data: ByteArray, isKeyFrame: Boolean) {
                    videoDecoder?.decodeFrame(data, isKeyFrame)
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        tvStatusTop.text = getString(R.string.status_disconnected)
                        Toast.makeText(this@StreamViewActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        Toast.makeText(this@StreamViewActivity, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            connect(remoteIp)
        }
    }

    private fun stopViewing() {
        streamClient?.stop()
        videoDecoder?.stop()
        stopStatsUpdate()
        finish()
    }

    private fun startStatsUpdate() {
        statsRunnable = object : Runnable {
            override fun run() {
                val client = streamClient
                if (client != null && client.isRunning()) {
                    val bytes = client.getTotalBytesReceived()
                    val frames = client.getTotalFramesReceived()
                    tvStatsInfo.text = "Frames: $frames | Data: ${bytes / 1024}KB"
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
                    Toast.makeText(this@StreamViewActivity, "Video appears frozen. Check connection.", Toast.LENGTH_LONG).show()
                }

                handler.postDelayed(this, 5_000)
            }
        }
        handler.postDelayed(watchdogRunnable!!, 5_000)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        stopStatsUpdate()
        stopWatchdog()
        streamClient?.stop()
        videoDecoder?.stop()
        super.onDestroy()
    }
}
