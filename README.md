# Firewall

A no-root Android firewall with tracker blocking, DNS-over-HTTPS, and connection logging. You choose which apps get online - and which trackers get shut down.

No ads. No tracking. No analytics. No data collection. No external servers. Everything runs locally on your device.

## How it works

Firewall creates a local VPN on your device. All network traffic routes through it. Apps you have not whitelisted hit a dead end - their packets go nowhere. Apps you allow bypass the VPN and connect normally.

On top of that, the firewall intercepts DNS queries to block known trackers, ad networks, and fingerprinting services. With DNS-over-HTTPS enabled, your DNS lookups are encrypted so your ISP cannot see what domains you are visiting.

The VPN is entirely local. No traffic leaves your device through a remote server. Blocked traffic is dropped on-device, and blocked tracker domains receive an instant NXDOMAIN response without ever reaching the internet.

## Features

### Per-App Firewall

- All apps are blocked by default - whitelist only what you trust
- Separate Wi-Fi and mobile data toggles for each app
- Block system apps and OEM bloatware (MIUI, Samsung, Huawei, etc.)
- Filter apps by All, User, or System with a search bar
- Bulk "Allow All" and "Block All" buttons for the current filtered view
- Rule changes are picked up by the firewall automatically within a second

**Important - restart the app you are blocking or unblocking.** Rule changes apply to *new* connections. An app that is already running may hold on to connections it opened earlier, and Android caches its network state. To block an app reliably, fully close it first (swipe it away from recents, or use Force Stop in system settings), then flip the toggle. The same applies in reverse: after unblocking an app, close and reopen it so it picks up its restored internet access.

### Tracker and Ad Blocking

The firewall intercepts every DNS query and checks it against a blocklist of known tracker, ad, and fingerprinting domains. Matched domains are instantly blocked with an NXDOMAIN response - the request never reaches the internet.

- Bundled default blocklist with 130+ domains, active out of the box
- Download community blocklists from trusted sources:
  - HaGeZi Light (~140K domains)
  - OISD Small (~57K domains)
  - 1Hosts Lite (~195K domains)
  - Steven Black Unified (~170K domains)
  - AdGuard DNS Filter (~166K domains)
- Add your own custom blocked domains
- Whitelist specific domains to prevent false positives
- Toggle tracker blocking on or off independently from the firewall
- Running count of total trackers blocked and domains loaded

### DNS over HTTPS (DoH)

When enabled, all DNS queries from your device are encrypted and sent to a trusted DNS provider over HTTPS instead of plain text. This prevents your ISP, network operator, or anyone on the same Wi-Fi from seeing which websites you visit.

- Three providers to choose from: Cloudflare, Google, or Quad9
- Switch providers with a single tap
- Toggle DoH on or off independently from the firewall and tracker blocking
- Running count of total DoH queries resolved
- Works alongside tracker blocking - blocked domains are caught before the DoH query is sent

### Auto-Start on Boot

The firewall automatically restarts when your phone reboots, so you are always protected without needing to open the app. This works even before you unlock your device (direct boot aware). The firewall also restarts automatically after an app update.

For this to work reliably, disable battery optimization for the Firewall app in your device settings (Settings > Battery > Battery Optimization > Firewall > Don't Optimize).

### Quick Settings Tile

Toggle the firewall on or off directly from your notification shade without opening the app. Pull down your quick settings panel, tap "Edit", and add the Firewall tile. When App Lock is enabled, turning protection off from the tile opens the app for PIN entry.

### App Lock

Require a PIN (4-8 digits) before the app can be opened. Protects your rules from being changed by someone with physical access to your device.

- PIN is hashed with PBKDF2 - never stored in plain text
- Brute-force lockout after 5 wrong attempts (30 second cooldown)
- Lock engages whenever the app leaves the foreground
- No recovery mechanism - if you forget your PIN, you must reinstall

### Backup and Restore

Export all your settings to a single JSON file and restore them on the same or a different device.

- Exports: per-app rules, custom blocked domains, whitelisted domains, blocklist sources, tracker blocking and DoH settings
- Does not export: PIN, logs, traffic stats
- Restore replaces all current rules and re-downloads blocklists
- Plain JSON, no encryption - store it somewhere safe

### Connection Logs

- Real-time log of every connection attempt passing through the VPN
- Each entry shows the originating app (Android 10+), destination IP, port, protocol, domain name, and packet size
- Color-coded entries: green for allowed, red for blocked, orange for tracker-blocked
- One-tap filter chips: All, Blocked, Allowed, Trackers
- Search by app name, IP address, or domain
- Export logs to a CSV file and share it anywhere - useful for spotting data leaks
- Auto-refreshes every 3 seconds
- Logs older than 7 days are automatically pruned

### Traffic Stats

- Global download and upload byte totals
- Total blocked, allowed, and overall connection counts
- Top apps ranked by traffic volume
- Per-app connection count breakdown
- All stats auto-refresh every 5 seconds
- Session uptime displayed on the main screen

### Dashboard

The main screen shows a status card with:

- Firewall on/off toggle
- Protection status (Protected / Inactive)
- Session uptime
- Blocked and allowed app counts
- Total apps monitored
- Total connections processed
- Download and upload totals

## Getting Started

1. Install the app
2. Tap the toggle on the main screen to enable the firewall
3. Android will ask you to approve the VPN connection - tap OK
4. Go to the Apps tab and allow the apps you want to have internet access
5. Go to the Trackers tab to enable tracker blocking and optionally enable DoH

The firewall is now running. All apps not explicitly allowed are blocked.

Tip: when you block or unblock an app that is currently running, close it fully and reopen it - running apps can keep using connections they opened before the rule changed.

## Permissions

| Permission | Why |
|---|---|
| VPN Service | Creates a local VPN to filter traffic and intercept DNS. Nothing leaves your device through a remote server. |
| Query All Packages | Lists all installed apps so you can control each one individually. |
| Internet | Required for the VPN tunnel, DoH queries, and blocklist downloads. |
| Boot Completed | Restarts the firewall after reboot if it was enabled. |
| Foreground Service | Keeps the firewall running reliably in the background. |
| Notifications | Shows a status notification when the firewall is active (required on Android 13+). |

## Privacy

This app collects zero data. There are no analytics, no crash reporting, no telemetry, and no network calls to external servers. The only outbound connections the app makes are:

- DoH queries to your chosen DNS provider (Cloudflare, Google, or Quad9), only when you enable DoH
- Blocklist downloads from GitHub, only when you tap download on a source

Your firewall rules, connection logs, traffic stats, and blocklist data are stored in a local database on your device and never leave it.

## Compatibility

- Android 6.0 (Marshmallow) through Android 16
- No root required
- Works on all devices including Samsung, Xiaomi, Huawei, Pixel, OnePlus, etc.

## What's new

See the [changelog](CHANGELOG.md) for release history. Highlights of 1.8: rule changes apply instantly without toggling, automatic recovery when Android kills the service, App Lock with PIN protection, and backup/restore.

## License

MIT License - do whatever you want with it.
