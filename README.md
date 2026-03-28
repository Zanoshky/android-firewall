# 🛡️ Firewall

A no-root Android firewall that blocks all internet access by default. You choose which apps get online.

No ads. No tracking. No analytics. No data collection. No external servers. Everything runs locally on your device.

## How it works

Firewall creates a local VPN on your device. All network traffic routes through it. Apps you haven't whitelisted hit a dead end — their packets go nowhere. Apps you allow bypass the VPN and connect normally.

This covers everything — user apps, system apps, and OEM bloatware from Xiaomi (MIUI), Samsung (OneUI), Huawei, etc.

## Features

- Block all apps by default
- Per-app Wi-Fi and mobile data toggles
- Block system apps and manufacturer services
- Filter by All / User / System with search
- Auto-starts on boot
- Persistent notification when active
- Dark glassmorphism UI
- Minimal battery impact
- Works on Android 6 through 15

## Download

**[⬇️ Download APK](release/firewall-v1.0.0.apk)**

Or build it yourself:

```bash
git clone https://github.com/YOUR_USERNAME/firewall.git
cd firewall
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions

| Permission | Why |
|---|---|
| VPN Service | Local VPN to filter traffic. Nothing leaves your device. |
| Query All Packages | Lists all installed apps so you can control each one. |
| Internet | Required for the VPN tunnel to function. |
| Boot Completed | Restarts firewall after reboot if it was enabled. |
| Foreground Service | Keeps the firewall running reliably. |
| Notifications | Shows status notification when active (Android 13+). |

## Privacy

This app collects zero data. There are no analytics, no crash reporting, no telemetry, no network calls to external servers. Your firewall rules are stored in a local database on your device and never leave it.

The VPN is entirely local. Traffic from blocked apps is simply dropped — it is not logged, inspected, or forwarded anywhere.

## Tech stack

- Kotlin
- Android VpnService API
- Room (local database)
- Material 3
- Coroutines
- AGP 9.0 with built-in Kotlin

## License

MIT License — do whatever you want with it.
