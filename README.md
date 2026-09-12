# 🎥 Type-C Live Streamer Studio

A modern, mobile-first Web App (PWA) and streaming relay engine designed to capture video from a **Type-C / USB-C Video Capture Card** (HDMI-to-USB-C UVC dongle) and broadcast live to **YouTube Live** and custom **RTMP / RTMPS** destinations.

---

## 🚀 Features

- 📱 **Mobile-First Studio UI**: Dark-mode glassmorphic interface designed for both smartphones and desktops (supports landscape & portrait).
- 🔌 **USB-C Capture Card Ready**: Auto-discovers and captures any standard UVC HDMI capture card (Cam Link, MacroSilicon, Elgato, generic USB-C video grabbers).
- 🔊 **Live Audio VU Meter**: Real-time canvas VU meter with peak indicator for HDMI audio or device microphone.
- 📡 **Direct YouTube Live & RTMP Streaming**:
  - Low-latency binary streaming via WebSocket to a Node.js + FFmpeg relay.
  - Transcoded to FLV with H.264 video and AAC audio for 100% YouTube Live compatibility.
  - **Dual Multi-Streaming**: Stream simultaneously to YouTube and a second RTMP server (e.g., Twitch, Facebook, or custom RTMP).
- ⚙️ **Customizable Quality**: Choose between 1080p, 720p, 480p at 30 FPS or 60 FPS, with configurable video bitrates (1500k to 6000k).
- 📲 **Installable PWA**: Can be installed directly to home screens on Android and iOS.

---

## 📁 Architecture Overview

```
[Camera / HDMI Source]
       │ (HDMI Cable)
       ▼
[Type-C Video Capture Card (UVC)]
       │ (USB-C OTG or Direct Plug)
       ▼
[Mobile Phone / Tablet / PC Browser]  <-- Mobile Web App (PWA)
       │ (WebSocket Binary Chunks - 1000ms timeslices)
       ▼
[Node.js + FFmpeg Streaming Relay]   <-- Local Server (Port 3000)
       │ (RTMP / RTMPS FLV H.264 + AAC)
       ▼
[YouTube Live / Twitch / RTMP Server]
```

---

## 🛠️ Quick Start Guide

### 1. Prerequisites
- **Node.js** (v18+ recommended)
- **FFmpeg** installed and accessible in system PATH

### 2. Start the Server
Open PowerShell / Terminal in the project root:
```bash
cd mobile_capture_streamer/server
npm start
```
The server will output:
```
🚀 Mobile Capture Streamer Server is running on Port 3000
📱 Localhost access:   http://localhost:3000
📱 Mobile Phone / LAN access:
   👉 http://192.168.1.xxx:3000
```

### 3. Open on Mobile Device
1. Connect your smartphone/tablet to the same Wi-Fi network (or connect your PC to your phone's mobile hotspot).
2. Open Google Chrome (Android) or Safari (iOS) on your phone.
3. Enter the IP URL shown in the terminal (e.g. `http://192.168.1.xxx:3000`).

---

## 🔌 Connecting your Type-C Video Capture Card

1. **Hardware Connection**:
   - Plug your video source (DSLR camera, gaming console, laptop) into the HDMI port of your capture card.
   - Plug the USB-C side into your phone (ensure OTG is enabled on your Android phone if required).
2. **Select Device**:
   - Tap **"Capture"** in the bottom dock of the web app.
   - In the **Video Input** dropdown, select your capture card (will be labeled with `📹 (Capture Card)`).
   - In the **Audio Input** dropdown, select the capture card's audio stream (`🔊 (HDMI In)`).
   - Choose your preferred resolution (1080p or 720p) and frame rate (30fps / 60fps) and tap **"Apply Changes"**.

---

## 🔴 Streaming to YouTube Live

1. Go to [YouTube Studio Live](https://studio.youtube.com/channel/live/livestreaming).
2. Create or select a stream. Copy your **Stream Key** (e.g., `xxxx-xxxx-xxxx-xxxx-xxxx`).
3. In the Web App, tap **"RTMP"** in the bottom dock:
   - Primary RTMP Server URL: `rtmp://a.rtmp.youtube.com/live2`
   - Primary Stream Key: Paste your YouTube stream key.
   - Tap **"Save Settings"**.
4. Tap the big green **"GO LIVE"** button.
5. The button will pulse red, the status will switch to **LIVE**, and your live video and audio will immediately appear in YouTube Studio!

---

## 🌐 Multi-Streaming (Dual RTMP)

Want to stream to YouTube and Twitch/Facebook simultaneously?
1. Open **"RTMP"** settings.
2. Check the **"Dual Multi-Stream"** toggle.
3. Enter your secondary RTMP URL (e.g. `rtmp://live.twitch.tv/app`) and your secondary stream key.
4. Save and tap **"GO LIVE"**. FFmpeg will broadcast to both destinations simultaneously using its `tee` muxer.
