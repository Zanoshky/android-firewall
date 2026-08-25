# Changelog

All notable user-facing changes to Firewall.

## 1.8 — August 2026

### Fixed

- **Rule changes now apply instantly.** Allowing or blocking an app takes effect within a second without toggling the firewall off and on. A bug in the tunnel rebuild logic silently dropped every rebuild request after the first, so rule changes were only picked up on a full restart.
- **The firewall recovers silently if Android kills it.** When the OS terminated the service for battery or memory, the switch showed "Protected" but no VPN was running. Opening the app now detects this and restarts the service automatically.
- **VPN consent denial no longer leaves the switch stuck on.** If you tap the toggle but decline Android's VPN permission dialog, the switch rolls back to off.
- **Another VPN revoking the tunnel now flips the switch off** instead of leaving it claiming protection is active.
- **Screen rotation no longer re-locks the app** when App Lock is enabled.
- **Restored settings (DoH, tracker blocking) no longer revert** after a backup restore. Toggle views no longer overwrite freshly restored preferences with stale state.

### New

- **App Lock.** Require a PIN to open the app so someone with physical access cannot change your rules. Includes brute-force lockout after repeated wrong attempts.
- **Backup and Restore.** Export your per-app rules, custom domains, whitelist, blocklist sources, and DoH settings to a JSON file. Restore on the same or a different device.
- **Quick Settings tile** respects App Lock: turning protection off from the shade opens the app for PIN entry when the lock is enabled.

## 1.6 — July 2026

The big one: this release fixes every reported bug, cuts battery use dramatically, and gives the app a new look.

### Fixed

- **Apps allowed on Wi-Fi were sometimes blocked anyway.** Once the VPN was running, the firewall could mistake your Wi-Fi connection for mobile data and apply the wrong rule set — making it look like everything was blocked. Wi-Fi is now detected correctly while the VPN is active.
- **Blocking an app now always takes effect.** Previously, if you toggled an app off and left the screen right away, the change could silently fail to apply and the app kept its internet access until a later restart. Rule changes are now applied by the firewall service itself, no matter what you do in the UI. Note that a running app can keep using connections it opened before the rule changed - fully close an app before blocking it (and reopen it after unblocking) for the change to take hold immediately.
- **Closed a shared-identity loophole.** Android grants network access per app identity (UID), and some apps share one — for example the Play Store and Google Play services. A blocked app could previously slip through via an allowed app it shared an identity with. The firewall now only bypasses the VPN for an app when everything sharing its identity is allowed.
- **The app list stays current.** Newly installed apps appear immediately and uninstalled apps disappear, without restarting the app. Rules for uninstalled apps are cleaned up automatically.
- **If the VPN fails to start, the firewall now retries** instead of silently leaving your traffic unprotected.

### Battery

- Removed a wake lock that kept the CPU awake for the entire firewall session. This was the main cause of battery drain.
- The VPN tunnel no longer tears itself down and rebuilds on every minor network event (signal strength changes, metering updates). It now only rebuilds when you actually change networks.

### New

- **Find the leak.** Connection logs now show which app originated each connection (Android 10+) and the packet size, so you can see exactly who is talking and how much.
- **Filter chips in Logs.** One tap to see only Blocked, Allowed, or Tracker-blocked entries, combined with the existing text search.
- **Export to CSV.** Share your connection logs (app, domain, IP, port, protocol, status, bytes, timestamp) as a CSV file from the Logs tab — respects your active filters.

### Changed

- **New dark design.** The app now uses a dark, high-contrast theme with a single green accent, monospaced numbers for stats and IP addresses, and custom navigation icons.
- Faster Logs and Stats tabs: filtering and aggregation moved into the database, so scrolling stays smooth even with thousands of entries.
- Now targets Android 16 (API 36).
- Note: upgrading resets stored rules and logs due to a database schema change. You will need to re-allow your apps once.

## 1.5 — April 2026

- Firewall automatically starts on boot, even before the device is unlocked.
- Firewall restarts automatically after an app update.
- Refreshed interface with a lighter, cleaner layout.
- Updated bundled tracker blocklist.

## 1.2 — March 2026

- Tracker and ad blocking with downloadable community blocklists (HaGeZi, OISD, 1Hosts, Steven Black, AdGuard).
- DNS over HTTPS with a choice of Cloudflare, Google, or Quad9.
- Real-time connection logs with per-entry status.
- Traffic statistics: global totals, per-app breakdown, top talkers.
- Quick Settings tile to toggle the firewall from the notification shade.
- Material redesign.

## 1.0 — March 2026

- First release: no-root, on-device firewall with per-app Wi-Fi and mobile data rules.
- All apps blocked by default; allow only what you trust.
- Zero data collection — everything stays on your device.
