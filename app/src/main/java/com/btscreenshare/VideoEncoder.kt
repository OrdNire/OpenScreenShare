package com.btscreenshare

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

class VideoEncoder {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    var quality: VideoQuality = VideoQuality.BALANCED

    interface Callback {
        fun onEncodedFrame(data: ByteArray, isKeyFrame: Boolean)
        fun onEncoderError(error: String)
    }

    private var encoder: MediaCodec? = null
    var inputSurface: Surface? = null
        private set
    private var callback: Callback? = null
    private var isRunning = false
    private var drainThread: Thread? = null
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var framesProduced = 0L

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun start() {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, quality.width, quality.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, quality.iFrameInterval)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // 100ms for static frames
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // Set profile based on quality setting
                if (quality.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
                // Use CBR for consistent bandwidth (prevents burst frames)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            isRunning = true

            drainThread = Thread({
                drainEncoder()
            }, "EncoderDrainThread").apply { start() }

            Log.d(TAG, "Encoder started: ${quality.width}x${quality.height} @ ${quality.bitrate / 1_000_000}Mbps ${quality.frameRate}fps (${quality.displayName}, CBR)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            callback?.onEncoderError("Failed to start encoder: ${e.message}")
        }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        val enc = encoder ?: return

        while (isRunning) {
            try {
                val outputIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout

                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = enc.outputFormat
                        Log.d(TAG, "Output format changed: $newFormat")

                        // Extract SPS and PPS from CSD buffers
                        spsData = newFormat.getByteBuffer("csd-0")?.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                        ppsData = newFormat.getByteBuffer("csd-1")?.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                        Log.d(TAG, "SPS: ${spsData?.size} bytes, PPS: ${ppsData?.size} bytes")
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = enc.getOutputBuffer(outputIndex) ?: continue
                        if (bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (!isConfig) {
                                framesProduced++
                                if (framesProduced % 30 == 0L) {
                                    Log.d(TAG, "Encoder produced $framesProduced frames, size=${data.size} keyFrame=$isKeyFrame")
                                }
                                callback?.onEncodedFrame(data, isKeyFrame)
                            }
                        }
                        enc.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet, continue loop
                    }
                }
            } catch (e: IllegalStateException) {
                if (isRunning) {
                    Log.e(TAG, "Encoder drain error", e)
                    isRunning = false
                    callback?.onEncoderError("Encoder drain error: ${e.message}")
                }
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Encoder drain exception", e)
                    isRunning = false
                    callback?.onEncoderError("Encoder drain exception: ${e.message}")
                }
                break
            }
        }
        Log.d(TAG, "Drain thread exited, isRunning=$isRunning")
    }

    fun getSpsData(): ByteArray? = spsData
    fun getPpsData(): ByteArray? = ppsData

    fun forceKeyFrame() {
        val enc = encoder
        if (enc != null && isRunning) {
            try {
                val params = Bundle()
                params.putInt("request-sync", 0)
                enc.setParameters(params)
                Log.d(TAG, "Key frame requested via request-sync")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request key frame", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        drainThread?.join(2000)
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
        encoder = null
        inputSurface = null
        drainThread = null
        Log.d(TAG, "Encoder stopped")
    }

    fun isEncoding(): Boolean = isRunning
}
