# Changelog

All notable user-facing changes to Firewall.

## 1.11, September 2026

### Blocking a domain now actually blocks it

This is the reason for the release. Blocking `google.com` used to do nothing in your browser, and the explanation was uncomfortable: an app you allowed was excluded from the tunnel altogether, so the firewall never saw a single thing it looked up. The domain list could only ever act on apps that were already cut off, which is to say it did nothing at all.

The tunnel was rebuilt around that. It no longer carries your traffic; it carries the lookups, which is where the decision actually happens. A blocked name is answered with an address that leads nowhere and the connection is refused at once, so a blocked site fails instantly instead of hanging. Lookups sent straight to a hardcoded public resolver are caught too.

Because ordinary traffic never enters the app, this costs less battery than the old design rather than more. Nothing is copied through the firewall while a video plays.

### Every app is handled one of three ways

The Wi-Fi and mobile toggles are gone. They never said what would happen to the traffic, and one of them silently disabled filtering for that app. Each app is now Bypass, Filtered or Blocked, and the label says which:

- **Bypass**: outside the firewall. Untouched, unfiltered, free.
- **Filtered**: lookups go through the firewall, so encryption, tracker blocking and your domain rules apply.
- **Blocked**: no working addresses, so nothing new can be opened.

Changes take effect on the app's next lookup. Nothing has to be toggled or restarted. Apps that were allowed before become Filtered, and everything else stays Blocked.

Blocking an app now works by refusing names, so an app with an address written into its code can still try that one address. Everything it would normally look up is gone.

### New: a Domains tab

Your own block list and allow list for ordinary websites, separate from the tracker lists. A rule covers the domain and everything under it, and the more specific rule wins, so you can block a domain and allow one name inside it. The allow list is also how you undo a tracker list that got a site wrong. Rules apply the moment you add them.

### Logs and stats are one screen

They were two tabs, so the numbers and the entries that produced them were never visible together. The Activity tab now opens on the summary and scrolls straight into the log, with the filter pinned above both.

There is a lot more in the summary than there used to be: how many lookups the firewall has seen, how many it stopped and what share that is, how many were encrypted, which of tracker lists, your own rules or a blocked app did the stopping, an hour by hour bar chart of the last day, where your apps stand, how long protection has been on, and the names blocked most often. Tapping a log entry offers to block that domain, or to always allow it.

### The passcode can be a real password

App Lock accepted four to twelve digits, which is a number small enough to work through by hand. It now takes letters, digits and symbols, up to sixty four characters. Existing numeric PINs keep working.

### Also

- The app warns you when Android's own Private DNS setting is on, because that takes lookups away from the firewall and nothing would be filtered.
- Lookups fall back to the network's own resolver when the encrypted one cannot be reached, instead of leaving apps with no answer.
- Counts for each app in the app list, and the app name on every log entry, for lookups as well as connections.
- The tunnel no longer rebuilds when you switch between Wi-Fi and mobile, because rules no longer depend on which one you are on.
- Upgrading keeps your rules. Activity history and the old byte counters start fresh, since they no longer have a matching shape.

## 1.9 — September 2026

### Fixed

- **Blocking or allowing an app is never lost again.** The rule was written on the screen's own coroutine scope, so leaving the Apps tab, rotating, or backgrounding the app right after a toggle cancelled the write. The tunnel then rebuilt from the old rules and the change silently did not apply. The write now runs for the lifetime of the process, and the rebuild is sequenced after it instead of racing it on a timer.
- **Resuming the app no longer restarts or stops the tunnel.** Correcting the switch from code fired its own listener, which was indistinguishable from a tap.
- **All five community blocklists are listed.** The lists sat in a plain `ScrollView`, which lays out only the first row of a `RecyclerView`, so only the first source was ever shown. The same bug hid every per-app row but one on the Stats tab, and the custom and whitelisted domain lists.
- **The HaGeZi Light download works again.** Its `hosts/` path no longer exists upstream; the source now points at the maintained `adblock/` list, and its domain count in the description matches what is actually downloaded.

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
