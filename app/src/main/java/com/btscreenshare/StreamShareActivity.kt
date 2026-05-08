package com.btscreenshare

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.graphics.Bitmap
import android.graphics.Color

class StreamShareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StreamShareActivity"
    }

    private lateinit var surfacePreview: SurfaceView
    private lateinit var tvStatusTop: TextView
    private lateinit var tvStatsInfo: TextView
    private lateinit var btnStop: MaterialButton
    private lateinit var overlayStatus: View

    private var streamServer: StreamServer? = null
    private var remoteIp: String = "Unknown"
    private val handler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var qrDialog: AlertDialog? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "屏幕录制权限被拒绝", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Check again after user interaction
        checkBatteryOptimizationAndProceed()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Check overlay permission after user returns from settings
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission granted")
            requestMediaProjection()
        } else {
            Toast.makeText(this, "需要悬浮窗权限以确保稳定共享", Toast.LENGTH_LONG).show()
            // Allow user to continue anyway
            requestMediaProjection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_share)

        surfacePreview = findViewById(R.id.surfacePreview)
        tvStatusTop = findViewById(R.id.tvStatusTop)
        tvStatsInfo = findViewById(R.id.tvStatsInfo)
        btnStop = findViewById(R.id.btnStop)
        overlayStatus = findViewById(R.id.overlayStatus)

        remoteIp = intent.getStringExtra(PairingActivity.EXTRA_REMOTE_IP) ?: "Unknown"
        tvStatusTop.text = getString(R.string.status_waiting)

        btnStop.setOnClickListener {
            stopSharing()
        }

        // Start StreamServer
        streamServer = StreamServer(LanConnectActivity.selectedQuality.queueCapacity).apply {
            setCallback(object : StreamServer.Callback {
                override fun onClientConnected(clientIp: String) {
                    runOnUiThread {
                        // Dismiss QR dialog when viewer connects
                        dismissQrDialog()
                        tvStatusTop.text = getString(R.string.status_streaming, clientIp)
                    }
                }

                override fun onClientDisconnected() {
                    runOnUiThread {
                        tvStatusTop.text = getString(R.string.status_disconnected)
                    }
                }

                override fun onServerError(error: String) {
                    runOnUiThread {
                        Toast.makeText(this@StreamShareActivity, "服务器错误: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            start()
        }
        StreamServerHolder.streamServer = streamServer

        // Show QR code dialog before sharing stats
        showQrCodeDialog()

        // Check battery optimization before requesting screen capture
        checkBatteryOptimizationAndProceed()

        // Start stats update
        startStatsUpdate()
    }

    private fun showQrCodeDialog() {
        val ip = NetworkUtils.getPrimaryIp()
        if (ip == null) {
            Log.w(TAG, "No IP available for QR code")
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)
        val ivQrCode = dialogView.findViewById<ImageView>(R.id.ivQrCode)
        val tvQrIp = dialogView.findViewById<TextView>(R.id.tvQrIp)
        val btnSkipQr = dialogView.findViewById<MaterialButton>(R.id.btnSkipQr)

        // Generate QR code bitmap
        try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val writer = QRCodeWriter()
            val bitMatrix: BitMatrix = writer.encode(ip, BarcodeFormat.QR_CODE, 500, 500, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code", e)
            ivQrCode.visibility = View.GONE
        }

        // Show IP address
        tvQrIp.text = ip

        // Skip button dismisses dialog but sharing continues
        btnSkipQr.setOnClickListener {
            dismissQrDialog()
        }

        // Build dialog
        qrDialog = AlertDialog.Builder(this)
            .setTitle("等待连接")
            .setView(dialogView)
            .setCancelable(true)
            .setOnDismissListener {
                qrDialog = null
            }
            .show()
    }

    private fun dismissQrDialog() {
        qrDialog?.dismiss()
        qrDialog = null
    }

    private fun checkBatteryOptimizationAndProceed() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

        if (isIgnoring) {
            Log.d(TAG, "App is ignoring battery optimizations")
            checkOverlayPermissionAndProceed()
        } else {
            Log.d(TAG, "App is NOT ignoring battery optimizations, showing dialog")
            showBatteryOptimizationDialog()
        }
    }

    private fun checkOverlayPermissionAndProceed() {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission already granted")
            requestMediaProjection()
        } else {
            Log.d(TAG, "Overlay permission NOT granted, requesting")
            showOverlayPermissionDialog()
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_allow) { _, _ ->
                requestIgnoreBatteryOptimizations()
            }
            .setNegativeButton(R.string.battery_optimization_continue) { _, _ ->
                checkOverlayPermissionAndProceed()
            }
            .setCancelable(false)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_allow) { _, _ ->
                requestOverlayPermission()
            }
            .setNegativeButton(R.string.overlay_permission_continue) { _, _ ->
                requestMediaProjection()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            batteryOptimizationLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
            // Fallback to general battery settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                batteryOptimizationLauncher.launch(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open battery settings", e2)
                checkOverlayPermissionAndProceed()
            }
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request overlay permission", e)
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_VIDEO_QUALITY, LanConnectActivity.selectedQuality.name)
        }
        startForegroundService(serviceIntent)
        tvStatusTop.text = getString(R.string.status_streaming, remoteIp)
    }

    private fun stopSharing() {
        // Dismiss QR dialog if still showing
        dismissQrDialog()

        // Stop capture service
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(serviceIntent)

        // Stop server
        streamServer?.stop()
        StreamServerHolder.streamServer = null

        stopStatsUpdate()

        // Navigate back to home (LanConnectActivity) instead of just finishing
        val homeIntent = Intent(this, LanConnectActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    private fun startStatsUpdate() {
        statsRunnable = object : Runnable {
            override fun run() {
                val server = streamServer
                if (server != null && server.isRunning()) {
                    val bytes = server.getTotalBytesSent()
                    val frames = server.getTotalFramesSent()
                    tvStatsInfo.text = "帧数: $frames | 数据: ${bytes / 1024}KB | 已连接: ${server.isClientConnected()}"
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(statsRunnable!!, 1000)
    }

    private fun stopStatsUpdate() {
        statsRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        dismissQrDialog()
        stopStatsUpdate()
        streamServer?.stop()
        StreamServerHolder.streamServer = null
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        try { startService(serviceIntent) } catch (_: Exception) {}
        super.onDestroy()
    }
}
