package com.btscreenshare

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoDecoder {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }

    interface Callback {
        fun onDecoderError(error: String)
        fun onFrameDecoded()
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onKeyFrameRequest()
    }

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var callback: Callback? = null
    private var isRunning = false
    private var isConfigured = false
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var frameCount = 0L

    // Recovery state
    @Volatile private var isWaitingForKeyFrame = false
    private var consecutiveDecodeErrors = 0
    private var framesSkippedWaitingForKey = 0L
    private var keyFramesReceived = 0L
    private var drainProducedOutput = false

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun configure(surface: Surface) {
        this.surface = surface
    }

    fun feedSpsPps(sps: ByteArray, pps: ByteArray) {
        spsData = sps
        ppsData = pps
        Log.d(TAG, "Received SPS (${sps.size} bytes) and PPS (${pps.size} bytes)")

        if (!isConfigured && surface != null) {
            initDecoder()
        }
    }

    private fun initDecoder() {
        val sps = spsData ?: return
        val pps = ppsData ?: return
        val surf = surface ?: return

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            }

            decoder = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                configure(format, surf, null, 0)
                start()
            }

            isRunning = true
            isConfigured = true
            isWaitingForKeyFrame = false
            consecutiveDecodeErrors = 0
            Log.d(TAG, "Decoder initialized and started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize decoder", e)
            callback?.onDecoderError("Failed to initialize decoder: ${e.message}")
        }
    }

    private fun resetDecoder() {
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder during reset", e)
        }
        decoder = null
        isConfigured = false

        initDecoder()
        isWaitingForKeyFrame = true
        Log.i(TAG, "Decoder reset and restarted")
    }

    private fun drainOutput() {
        val dec = decoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = dec.dequeueOutputBuffer(bufferInfo, 1000)
            when {
                outputIndex >= 0 -> {
                    dec.releaseOutputBuffer(outputIndex, true)
                    frameCount++
                    consecutiveDecodeErrors = 0
                    drainProducedOutput = true
                    callback?.onFrameDecoded()
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = dec.outputFormat
                    Log.d(TAG, "Decoder output format: $newFormat")
                    val videoWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val videoHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    callback?.onVideoSizeChanged(videoWidth, videoHeight)
                }
                else -> break
            }
        }
    }

    fun decodeFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (!isRunning) return
        val decoder = this.decoder ?: return

        if (isWaitingForKeyFrame) {
            if (isKeyFrame) {
                Log.i(TAG, "Key frame received, resuming decode")
                isWaitingForKeyFrame = false
                consecutiveDecodeErrors = 0
            } else {
                // Still waiting, but drain output to clear any buffered frames
                drainOutput()
                return
            }
        }

        // Drain output BEFORE queuing input to prevent output buffer backup
        drainOutput()

        try {
            val inputIndex = decoder.dequeueInputBuffer(5000)
            if (inputIndex < 0) {
                Log.w(TAG, "No available input buffer, draining output")
                drainProducedOutput = false
                drainOutput()
                if (!drainProducedOutput) {
                    Log.w(TAG, "Drain produced no output, resetting decoder")
                    resetDecoder()
                    return
                }
                return
            }
            val inputBuffer = decoder.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            inputBuffer.put(data)
            decoder.queueInputBuffer(inputIndex, 0, data.size, 0, 0)

            // Drain output AFTER queuing to process the frame
            drainOutput()
        } catch (e: MediaCodec.CodecException) {
            handleDecodeError("code exception", e)
        } catch (e: IllegalStateException) {
            handleDecodeError("illegal state", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error decoding frame", e)
            handleDecodeError("unexpected error", e)
        }
    }

    private fun handleDecodeError(errorType: String, e: Exception) {
        consecutiveDecodeErrors++
        Log.e(TAG, "Decoder $errorType (consecutive: $consecutiveDecodeErrors)", e)

        // DON'T flush - flush clears SPS/PPS and makes recovery impossible
        // Just wait for a keyframe, the decoder can recover naturally

        if (!isWaitingForKeyFrame) {
            isWaitingForKeyFrame = true
            Log.w(TAG, "Requesting key frame to recover from $errorType")
            callback?.onKeyFrameRequest()
        }

        callback?.onDecoderError("$errorType: ${e.message}")
    }

    fun stop() {
        isRunning = false
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder", e)
        }
        decoder = null
        isConfigured = false
        frameCount = 0
        isWaitingForKeyFrame = false
        consecutiveDecodeErrors = 0
        Log.d(TAG, "Decoder stopped")
    }

    fun isDecoding(): Boolean = isRunning
    fun getFrameCount(): Long = frameCount
    fun isWaitingForKeyFrame(): Boolean = isWaitingForKeyFrame
    fun getFramesSkippedWaitingForKey(): Long = framesSkippedWaitingForKey
    fun getKeyFramesReceived(): Long = keyFramesReceived
}