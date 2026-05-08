package com.btscreenshare

import android.media.MediaCodecInfo

enum class VideoQuality(
    val displayName: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val iFrameInterval: Int,
    val profile: Int,
    val queueCapacity: Int
) {
    LOW(
        displayName = "流畅",
        width = 640,
        height = 360,
        bitrate = 2_000_000,
        frameRate = 30,
        iFrameInterval = 2,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        queueCapacity = 5
    ),
    BALANCED(
        displayName = "均衡",
        width = 1280,
        height = 720,
        bitrate = 6_000_000,
        frameRate = 60,
        iFrameInterval = 3,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        queueCapacity = 10
    ),
    HIGH(
        displayName = "高清",
        width = 1920,
        height = 1080,
        bitrate = 12_000_000,
        frameRate = 60,
        iFrameInterval = 5,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        queueCapacity = 15
    ),
    ULTRA(
        displayName = "极致",
        width = 1920,
        height = 1080,
        bitrate = 16_000_000,
        frameRate = 60,
        iFrameInterval = 5,
        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        queueCapacity = 20
    )
}
