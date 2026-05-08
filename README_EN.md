# OpenScreenShare

**[English](README_EN.md)** | **[中文](README_ZH.md)**

---

A lightweight Android screen sharing app over LAN. Connect two devices to the same WiFi, enter the partner's IP address, and view their screen in real time.

## Features

- **WiFi Direct** — No server needed, peer-to-peer TCP connection over LAN
- **Real-time Screen Sharing** — H.264 hardware encoding, low-latency streaming
- **Multiple Quality Presets** — 4 presets to adapt to different network conditions
- **Floating Overlay** — Persistent overlay indicator during sharing, stays alive in background
- **Bidirectional** — Either side can initiate sharing
- **Auto Recovery** — Automatic disconnect detection, returns to home screen

## Quality Presets

| Preset | Resolution | Bitrate | Frame Rate | Profile |
|--------|-----------|---------|------------|---------|
| Smooth | 640×360 | 2 Mbps | 30 fps | Baseline |
| Balanced (default) | 1280×720 | 6 Mbps | 60 fps | High |
| HD | 1920×1080 | 12 Mbps | 60 fps | High |
| Ultra | 1920×1080 | 16 Mbps | 60 fps | High |

## How to Use

1. Connect two Android devices to the same WiFi network
2. On one device, tap "Share My Screen" and select quality
3. On the other device, enter the sharer's IP address and tap "View Partner's Screen"
4. You can now see the other device's screen in real time

## Requirements

- Android 8.0 (API 26) or above
- Both devices must be on the same local network
- Screen recording permission required (system dialog prompt)

## Architecture

```
┌──────────────┐     TCP/Port 9000     ┌──────────────┐
│    Sharer    │ ────────────────────→ │    Viewer    │
│              │                       │              │
│  MediaCodec  │    0x01: Video Frame  │  MediaCodec  │
│  H.264 Enc   │    0x02: SPS/PPS      │  H.264 Dec   │
│              │ ←──────────────────── │              │
│ StreamServer │    0x01: Keyframe Req │ StreamClient │
└──────────────┘                       └──────────────┘
```

- **Encoder**: MediaCodec Surface input, H.264 High Profile, CBR mode
- **Transport**: TCP + ArrayBlockingQueue frame buffer, keyframe-aware dropping
- **Decoder**: MediaCodec async output, automatic reset recovery
- **Keep-alive**: TYPE_APPLICATION_OVERLAY floating window + Foreground Service

## Project Structure

```
app/src/main/java/com/btscreenshare/
├── LanConnectActivity.kt    # Home: IP input, quality selector, share/view entry
├── StreamShareActivity.kt   # Sharer UI: status, stats, stop button
├── StreamViewActivity.kt    # Viewer UI: video rendering, stats, stop button
├── ScreenCaptureService.kt  # Screen capture foreground service
├── StreamViewService.kt     # Viewer foreground service
├── VideoEncoder.kt          # H.264 hardware encoder
├── VideoDecoder.kt          # H.264 hardware decoder
├── VideoQuality.kt          # Quality preset enum
├── StreamServer.kt          # TCP server (sharer side)
├── StreamClient.kt          # TCP client (viewer side)
├── OverlayManager.kt        # Floating overlay management
├── BluetoothSession.kt      # Bluetooth pairing (alternative connection)
├── PairingActivity.kt       # Bluetooth pairing UI
└── AspectRatioFrameLayout.kt # Aspect-ratio preserving layout
```

## Build

Open the project in Android Studio, then Build → Build APK.

```
minSdk = 26 (Android 8.0)
targetSdk = 34 (Android 14)
```

## Known Limitations

- LAN only, no remote support (WebRTC possible in the future)
- No audio streaming
- 1080p presets may lag on some devices, choose quality based on network

## License

MIT
