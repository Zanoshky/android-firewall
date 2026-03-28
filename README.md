# 🛡️ Firewall

A no-root Android firewall that blocks all app internet access by default. You control which apps get Wi-Fi and mobile data — including system apps and OEM bloatware (Xiaomi, Samsung, etc.).

## Download

**[⬇️ Download APK](release/firewall-v1.0.0.apk)**

## How it works

- Creates a local VPN tunnel — all traffic routes through it
- Apps are blocked by default (traffic goes to a black hole)
- Toggle Wi-Fi / Mobile data per app
- System apps included (MIUI analytics, Samsung services, etc.)
- Auto-starts on boot if enabled
- No root required

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Made by Marko Zanoški, free with love.
