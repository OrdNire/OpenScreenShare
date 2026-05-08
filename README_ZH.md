# OpenScreenShare

**[English](README.md)** | **[中文](README_ZH.md)**

---

一个轻量级的 Android 局域网屏幕共享应用。两台设备连接同一 WiFi，输入对方 IP 即可实时查看对方屏幕。

## 功能特性

- **WiFi 直连** — 无需服务器，两台设备通过局域网 TCP 直连
- **实时屏幕共享** — H.264 硬件编码，低延迟传输
- **多档清晰度** — 4 档预设，适配不同网络环境
- **悬浮窗保活** — 共享时显示悬浮窗指示，切后台不断连
- **双向共享** — 任意一方可发起共享，另一方查看
- **自动恢复** — 断连检测，自动返回首页

## 清晰度档位

| 档位 | 分辨率 | 码率 | 帧率 | 编码 Profile |
|------|--------|------|------|-------------|
| 流畅 | 640×360 | 2 Mbps | 30 fps | Baseline |
| 均衡（默认） | 1280×720 | 6 Mbps | 60 fps | High |
| 高清 | 1920×1080 | 12 Mbps | 60 fps | High |
| 极致 | 1920×1080 | 16 Mbps | 60 fps | High |

## 使用方法

1. 两台 Android 设备连接同一 WiFi 网络
2. 在一台设备上点击「共享我的屏幕」，选择清晰度
3. 在另一台设备上输入共享方的 IP 地址，点击「查看对方屏幕」
4. 即可实时看到对方的屏幕画面

## 系统要求

- Android 8.0 (API 26) 及以上
- 两台设备需在同一局域网
- 需要屏幕录制权限（系统弹窗授权）

## 技术架构

```
┌─────────────┐     TCP/Port 9000     ┌─────────────┐
│   共享方     │ ────────────────────→ │   查看方     │
│             │                       │             │
│ MediaCodec  │    0x01: 视频帧       │ MediaCodec  │
│ H.264 编码  │    0x02: SPS/PPS      │ H.264 解码  │
│             │ ←──────────────────── │             │
│ StreamServer│    0x01: 请求关键帧   │ StreamClient│
└─────────────┘                       └─────────────┘
```

- **编码器**：MediaCodec Surface 输入，H.264 High Profile，CBR 模式
- **传输层**：TCP + ArrayBlockingQueue 帧队列，关键帧感知丢帧
- **解码器**：MediaCodec 异步输出，自动 reset 恢复
- **保活**：TYPE_APPLICATION_OVERLAY 悬浮窗 + 前台 Service

## 项目结构

```
app/src/main/java/com/btscreenshare/
├── LanConnectActivity.kt    # 首页：IP 输入、清晰度选择、分享/查看入口
├── StreamShareActivity.kt   # 共享方界面：状态、统计、停止按钮
├── StreamViewActivity.kt    # 查看方界面：视频渲染、统计、停止按钮
├── ScreenCaptureService.kt  # 屏幕捕获前台服务
├── StreamViewService.kt     # 查看方前台服务
├── VideoEncoder.kt          # H.264 硬件编码器
├── VideoDecoder.kt          # H.264 硬件解码器
├── VideoQuality.kt          # 清晰度档位枚举
├── StreamServer.kt          # TCP 服务端（共享方）
├── StreamClient.kt          # TCP 客户端（查看方）
├── OverlayManager.kt        # 悬浮窗管理
├── BluetoothSession.kt      # 蓝牙配对（备用连接方式）
├── PairingActivity.kt       # 蓝牙配对界面
└── AspectRatioFrameLayout.kt # 自适应宽高比布局
```

## 构建

使用 Android Studio 打开项目，Build → Build APK 即可。

```
minSdk = 26 (Android 8.0)
targetSdk = 34 (Android 14)
```

## 已知限制

- 仅支持局域网，不支持远程（未来可考虑 WebRTC）
- 无音频传输
- 1080p 档位在部分设备上可能卡顿，建议根据网络选择合适档位

## License

MIT
