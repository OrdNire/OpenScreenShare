package com.btscreenshare

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object OverlayManager {

    private const val TAG = "OverlayManager"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false

    fun show(context: Context) {
        if (isShowing) {
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Create overlay view
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 100 // Below status bar
            }

            overlayView = createOverlayView(context)

            windowManager?.addView(overlayView, layoutParams)
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (!isShowing) {
            return
        }

        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        overlayView = null
        windowManager = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing

    private fun createOverlayView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x80000000.toInt()) // Semi-transparent black
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Status indicator dot and text
        val statusText = TextView(context).apply {
            text = "● Sharing"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 16
            }
        }

        // Stop button
        val stopButton = Button(context).apply {
            text = "Stop"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x44FF0000.toInt()) // Semi-transparent red
            textSize = 12f
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                stopScreenCapture(context)
            }
        }

        container.addView(statusText)
        container.addView(stopButton)

        // Set container size
        container.minimumWidth = 200
        container.minimumHeight = 48

        return container
    }

    private fun stopScreenCapture(context: Context) {
        // Stop StreamServer first so the receiver detects the disconnect
        StreamServerHolder.streamServer?.stop()
        StreamServerHolder.streamServer = null

        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(intent)
        hide()
    }
}