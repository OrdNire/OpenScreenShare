# StreamView Disconnect Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix StreamViewActivity disconnect on rotation and background by preventing activity recreation and creating a foreground service to keep the TCP connection alive.

**Architecture:** Add configChanges to prevent Activity recreation on rotation. Create StreamViewService foreground service that holds StreamClient + VideoDecoder, keeping the connection alive when Activity is backgrounded. Activity binds to service and manages decoder pause/resume via lifecycle callbacks.

**Tech Stack:** Android Service, ServiceConnection, MediaCodec, IBinder, foreground service with specialUse type.

---

## File Structure

**Modified:**
- `app/src/main/AndroidManifest.xml` - Add configChanges to StreamViewActivity, register StreamViewService
- `app/src/main/java/com/btscreenshare/StreamViewActivity.kt` - Add lifecycle handling, binding to service, onConfigurationChanged

**Created:**
- `app/src/main/java/com/btscreenshare/StreamViewService.kt` - Foreground service holding StreamClient + VideoDecoder

---

### Task 1: Update AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add configChanges to StreamViewActivity**

Find the StreamViewActivity declaration (lines 72-75) and add `android:configChanges` attribute:

```xml
<activity
    android:name=".StreamViewActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"
    android:theme="@style/Theme.BTScreenShare" />
```

- [ ] **Step 2: Add foreground service permission for specialUse**

Add after line 21 (after FOREGROUND_SERVICE_MEDIA_PROJECTION):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

- [ ] **Step 3: Register StreamViewService**

Add after the ScreenCaptureService declaration (after line 81):

```xml
<service
    android:name=".StreamViewService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE"
        android:value="stream_view" />
</service>
```

- [ ] **Step 4: Commit manifest changes**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: add configChanges to StreamViewActivity and register StreamViewService"
```

---

### Task 2: Create StreamViewService

**Files:**
- Create: `app/src/main/java/com/btscreenshare/StreamViewService.kt`

- [ ] **Step 1: Write StreamViewService class**

Create the service that holds StreamClient + VideoDecoder and provides a binder:

```kotlin
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
        videoDecoder?.feedSpsPps(sps, pps)
    }

    fun decodeFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (!isPaused) {
            videoDecoder?.decodeFrame(data, isKeyFrame)
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
```

- [ ] **Step 2: Commit service creation**

```bash
git add app/src/main/java/com/btscreenshare/StreamViewService.kt
git commit -m "feat: create StreamViewService foreground service for stream persistence"
```

---

### Task 3: Update StreamViewActivity

**Files:**
- Modify: `app/src/main/java/com/btscreenshare/StreamViewActivity.kt`

- [ ] **Step 1: Add imports and service binding fields**

Add imports at top of file (after line 11):

```kotlin
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.IBinder
```

Add service connection fields after line 36 (after `frozenWarningShown`):

```kotlin
private var streamViewService: StreamViewService? = null
private var isServiceBound = false
```

- [ ] **Step 2: Add service connection**

Add after the fields (around line 38):

```kotlin
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val binder = service as StreamViewService.LocalBinder
        streamViewService = binder.getService()
        isServiceBound = true
        Log.d(TAG, "Service connected")
    }

    override fun onServiceDisconnected(name: ComponentName) {
        streamViewService = null
        isServiceBound = false
        Log.d(TAG, "Service disconnected")
    }
}
```

- [ ] **Step 3: Modify onCreate to start and bind service**

Replace the decoder initialization (lines 66-91) with service-based approach:

```kotlin
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
        Log.d(TAG, "Surface created, configuring decoder via service")
        streamViewService?.configureDecoder(holder.surface)

        // Set decoder callback
        streamViewService?.setDecoderCallback(object : VideoDecoder.Callback {
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

        // Connect via service
        connectToServer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        // Don't stop decoder here - service keeps it alive
    }
})
```

- [ ] **Step 4: Modify connectToServer to use service**

Replace the entire `connectToServer()` method (lines 114-146):

```kotlin
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
}
```

- [ ] **Step 5: Modify stopViewing**

Replace `stopViewing()` method (lines 148-153):

```kotlin
private fun stopViewing() {
    stopStatsUpdate()
    stopWatchdog()

    // Unbind from service
    if (isServiceBound) {
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
```

- [ ] **Step 6: Modify startStatsUpdate to use service**

Replace `startStatsUpdate()` method (lines 155-168):

```kotlin
private fun startStatsUpdate() {
    statsRunnable = object : Runnable {
        override fun run() {
            val service = streamViewService
            if (service != null && service.isStreamRunning()) {
                val (bytes, frames) = service.getStats()
                tvStatsInfo.text = "Frames: $frames | Data: ${bytes / 1024}KB"
            }
            handler.postDelayed(this, 1000)
        }
    }
    handler.postDelayed(statsRunnable!!, 1000)
}
```

- [ ] **Step 7: Add onStart lifecycle method**

Add after `startWatchdog()` method (around line 192):

```kotlin
override fun onStart() {
    super.onStart()
    streamViewService?.resumeDecoder()
    Log.d(TAG, "Activity started, decoder resumed")
}
```

- [ ] **Step 8: Add onStop lifecycle method**

Add after `onStart()`:

```kotlin
override fun onStop() {
    super.onStop()
    streamViewService?.pauseDecoder()
    Log.d(TAG, "Activity stopped, decoder paused")
}
```

- [ ] **Step 9: Add onConfigurationChanged override**

Add after `onStop()`:

```kotlin
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    Log.d(TAG, "Configuration changed: ${newConfig.orientation}")
    // Activity is not recreated, decoder continues running
}
```

- [ ] **Step 10: Modify onDestroy**

Replace `onDestroy()` method (lines 197-203):

```kotlin
override fun onDestroy() {
    stopStatsUpdate()
    stopWatchdog()

    if (isServiceBound) {
        unbindService(serviceConnection)
        isServiceBound = false
    }

    super.onDestroy()
}
```

- [ ] **Step 11: Remove old streamClient and videoDecoder fields**

Remove these lines (29-30):
```kotlin
private var streamClient: StreamClient? = null
private var videoDecoder: VideoDecoder? = null
```

- [ ] **Step 12: Commit activity changes**

```bash
git add app/src/main/java/com/btscreenshare/StreamViewActivity.kt
git commit -m "feat: bind StreamViewActivity to StreamViewService, add lifecycle handling"
```

---

### Task 4: Build and Verify

- [ ] **Step 1: Build the project**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Check for compilation errors**

If build fails, check error messages and fix any issues in the modified files.

- [ ] **Step 3: Final commit if any fixes needed**

```bash
git add -A
git commit -m "fix: resolve build issues in stream view service integration"
```

---

## Self-Review Checklist

**1. Spec coverage:**
- ✓ configChanges added to StreamViewActivity (Task 1 Step 1)
- ✓ onStart/onStop lifecycle handling (Task 3 Steps 7-8)
- ✓ onConfigurationChanged override (Task 3 Step 9)
- ✓ StreamViewService foreground service created (Task 2)
- ✓ Service holds StreamClient + VideoDecoder (Task 2 Step 1)
- ✓ Activity binds to service (Task 3 Steps 1-3)
- ✓ Service registered with foregroundServiceType=specialUse (Task 1 Step 3)

**2. Placeholder scan:**
- No TBD/TODO found
- All code blocks contain complete implementation
- No "implement later" or "add validation" placeholders

**3. Type consistency:**
- `StreamViewService.LocalBinder.getService()` returns `StreamViewService` ✓
- Service callbacks use same types as Activity previously used ✓
- Surface, ByteArray, String types consistent throughout ✓