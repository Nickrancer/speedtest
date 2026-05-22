# ⚡ SpeedTest Android App

A modern, real internet speed test app for Android — built with Kotlin, featuring a custom animated speedometer gauge and real-world network measurement.

![Build APK](https://github.com/YOUR_USERNAME/speedtest/actions/workflows/build-apk.yml/badge.svg)

## 📱 Features

- **Real Speed Measurement** — Downloads from Cloudflare CDN, Hetzner, and OVH for accurate results
- **Live Animated Speedometer** — Custom-drawn gauge with glowing neon needle and gradient arc
- **Ping & Jitter Testing** — TCP socket-based latency measurement
- **ISP Detection** — Automatically detects your ISP name and server location
- **Connection Rating** — Rates your connection from ❌ Very Slow to ⚡ Blazing Fast
- **Network Type Detection** — Shows Wi-Fi, Cellular, or Ethernet
- **Dark Theme UI** — Modern dark UI with glassmorphism cards
- **Phase Progress Indicators** — Visual steps for Ping → Download → Upload

## 🏗️ GitHub Actions Build

The APK is built automatically on every push via GitHub Actions.

### 📥 Download the APK

1. Go to the **[Actions](../../actions)** tab
2. Click the latest **"Build SpeedTest APK"** workflow run
3. Download the APK from **Artifacts** at the bottom

### 🏷️ Create a Release

Push a version tag to trigger a GitHub Release with the APK attached:
```bash
git tag v1.0.0
git push origin v1.0.0
```

## 🛠️ Build Locally

### Requirements
- JDK 17+
- Android SDK (API 24–35)
- Gradle 8.7

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/speedtest.git
cd speedtest

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# APK output location:
# app/build/outputs/apk/release/app-release.apk
```

## 📐 Architecture

```
app/
├── src/main/
│   ├── java/com/speedtest/app/
│   │   ├── MainActivity.kt        # UI controller & test orchestration
│   │   ├── SpeedTestEngine.kt     # Core speed measurement engine
│   │   └── SpeedometerView.kt     # Custom animated gauge widget
│   └── res/
│       ├── layout/activity_main.xml
│       ├── drawable/              # Buttons, cards, indicators
│       └── values/                # Colors, themes, strings
└── build.gradle.kts
```

## 🌐 Test Endpoints

| Purpose | URL |
|---------|-----|
| Download | `speed.cloudflare.com/__down?bytes=25000000` |
| Download (fallback) | `proof.ovh.net/files/10Mb.dat` |
| Upload | `speed.cloudflare.com/__up` |
| ISP Info | `speed.cloudflare.com/meta` |
| Ping | TCP socket to `speed.cloudflare.com:80` |

## 📋 Requirements

- Android 7.0 (API 24) or higher
- Internet connection

## 📄 License

MIT License
