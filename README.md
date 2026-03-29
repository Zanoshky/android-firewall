# 🛡️ Firewall

A no-root Android firewall with tracker blocking, DNS-over-HTTPS, and connection logging. You choose which apps get online — and which trackers get shut down.

No ads. No tracking. No analytics. No data collection. No external servers. Everything runs locally on your device.

## How it works

Firewall creates a local VPN on your device. All network traffic routes through it. Apps you haven't whitelisted hit a dead end — their packets go nowhere. Apps you allow bypass the VPN and connect normally.

On top of that, the firewall intercepts DNS queries to block known trackers, ad networks, and fingerprinting services. With DNS-over-HTTPS enabled, your DNS lookups are encrypted so your ISP can't see what domains you're visiting.

## Features

### Per-App Firewall
- Block all apps by default — whitelist only what you trust
- Separate Wi-Fi and mobile data toggles per app
- Block system apps and OEM bloatware (MIUI, Samsung, Huawei, etc.)
- Filter by All / User / System with search
- Auto-starts on boot

### Tracker Blocking
- Bundled blocklist with 130+ known tracker, ad, and fingerprinting domains
- Download community blocklists (Steven Black, Energized, AdGuard, OISD, Disconnect)
- Add custom blocked domains
- Whitelist domains to prevent false positives
- Toggle on/off independently from the firewall

### DNS over HTTPS
- Encrypt DNS queries to prevent ISP snooping
- Choose provider: Cloudflare, Google, or Quad9
- Blocked tracker domains get instant NXDOMAIN responses
- Toggle on/off independently

### Connection Logs
- Real-time log of every connection attempt
- Shows destination IP, port, protocol, and domain name
- Color-coded: green (allowed), red (blocked), orange (tracker)
- Filter logs by app name or domain
- Auto-refreshes every 3 seconds
- Auto-prunes logs older than 7 days

### Traffic Stats
- Global download/upload totals
- Blocked vs allowed pie chart
- Top apps by traffic bar chart
- Per-app traffic breakdown
- All charts auto-refresh every 5 seconds

### General
- Clean Material Design light theme
- 4-tab layout: Apps, Logs, Stats, Trackers
- Persistent notification when active
- Minimal battery impact
- Works on Android 6 through 15

## Download

**[⬇️ Download APK](release/firewall-v1.1.0.apk)**

Or build it yourself:

```bash
git clone https://github.com/AquaRegia/firewall.git
cd firewall
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions

| Permission | Why |
|---|---|
| VPN Service | Local VPN to filter traffic and intercept DNS. Nothing leaves your device. |
| Query All Packages | Lists all installed apps so you can control each one. |
| Internet | Required for the VPN tunnel, DoH queries, and blocklist downloads. |
| Boot Completed | Restarts firewall after reboot if it was enabled. |
| Foreground Service | Keeps the firewall running reliably. |
| Notifications | Shows status notification when active (Android 13+). |

## Privacy

This app collects zero data. There are no analytics, no crash reporting, no telemetry, no network calls to external servers (except DoH queries to your chosen DNS provider and optional blocklist downloads from GitHub, both initiated only by you).

Your firewall rules, connection logs, and blocklist data are stored in a local database on your device and never leave it.

The VPN is entirely local. Traffic from blocked apps is dropped on-device. DNS queries for blocked tracker domains receive an immediate NXDOMAIN response without ever reaching the internet.

## Tech stack

- Kotlin
- Android VpnService API
- Room (local database)
- Material 3
- MPAndroidChart
- Coroutines with AtomicLong counters
- DNS wire-format parsing (RFC 8484)
- AGP 9.0 with built-in Kotlin

## License

MIT License — do whatever you want with it.
