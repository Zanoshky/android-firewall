# Google Play Store Listing - Firewall by Zanoshky

## App Details

- **Package name:** com.zanoshky.firewall
- **App name:** Firewall - Block Internet & Trackers
- **Developer name:** Marko Zanoski
- **Category:** Tools
- **Content rating:** Everyone
- **Price:** Free

---

## Short Description (80 chars max)

Block internet per app. Block trackers. DNS over HTTPS. No root needed.

---

## Full Description (4000 chars max)

Firewall gives you full control over which apps can access the internet - and blocks trackers, ads, and fingerprinting services across your entire device. No root required.

Every app is blocked by default. You decide which ones get Wi-Fi access, mobile data access, or both. This includes system apps and manufacturer services from Xiaomi, Samsung, Huawei, and others.

HOW IT WORKS
Firewall creates a local VPN on your device. All network traffic passes through it. Apps you have not allowed are blocked - their traffic goes nowhere. Apps you allow bypass the VPN and connect normally. No data is collected, no traffic leaves your device, no external servers involved.

The firewall automatically restarts after a reboot or app update, so you are always protected without needing to open the app. It starts before you even unlock your device.

PER-APP FIREWALL
- Block all apps by default - whitelist only what you trust
- Separate Wi-Fi and mobile data toggles per app
- Block system apps and OEM bloatware (MIUI analytics, Samsung services, etc.)
- Bulk "Allow All" and "Block All" for quick setup
- Filter apps by All, User, or System with search
- Changes take effect immediately, no restart needed

AUTO-START ON BOOT
- Firewall restarts automatically when your phone reboots
- Starts before you unlock your device (direct boot aware)
- Also restarts automatically after an app update
- Works on all Android versions from 6.0 to 15
- For best reliability, disable battery optimization for the app in your device settings

QUICK SETTINGS TILE
- Toggle the firewall on or off from your notification shade
- No need to open the app - pull down quick settings and tap the Firewall tile

TRACKER AND AD BLOCKING
- Blocks 130+ known tracker, ad, and fingerprinting domains out of the box
- Download community blocklists with hundreds of thousands of domains:
  - HaGeZi Light (~140K domains)
  - OISD Small (~57K domains)
  - 1Hosts Lite (~195K domains)
  - Steven Black Unified (~170K domains)
  - AdGuard DNS Filter (~166K domains)
- Add your own custom blocked domains
- Whitelist specific domains to prevent false positives
- Toggle tracker blocking on or off independently from the firewall
- Blocked domains receive an instant NXDOMAIN response - the request never reaches the internet

DNS OVER HTTPS (DoH)
When you browse the internet, your device sends DNS queries in plain text. Your ISP, network operator, or anyone on the same Wi-Fi can see every domain you visit. DNS over HTTPS encrypts these queries so nobody can snoop on your browsing.

- Choose your provider: Cloudflare, Google, or Quad9
- Switch providers with a single tap
- Toggle DoH on or off independently from the firewall
- Works alongside tracker blocking - blocked domains are caught before the DoH query is sent
- Running counter shows total DoH queries resolved

How DoH works in this app: When an app on your device looks up a domain name (like "example.com"), the query goes through the local VPN. Instead of sending it as plain text to your ISP's DNS server, the firewall encrypts it and sends it over HTTPS to your chosen provider (for example, Cloudflare). The response comes back encrypted, and the firewall delivers the answer to the app. Your ISP never sees which domains you are visiting.

Note: Third-party DNS test pages (like one.one.one.one/help or dnsleaktest.com) may not detect that DoH is active. These pages test whether your system DNS is set to their server directly. Because the firewall handles DoH inside a local VPN tunnel, the test page sees your regular network exit IP instead. Your DNS queries are still encrypted - the test page simply cannot detect it from outside the tunnel. To verify DoH is working, check the Trackers tab in the app where the DoH query counter increments with every resolved query.

CONNECTION LOGS
- Real-time log of every connection attempt
- Shows destination IP, port, protocol, and domain name
- Color-coded: allowed (green), blocked (red), tracker-blocked (orange)
- Filter by app name or domain
- Auto-refreshes every 3 seconds
- Logs older than 7 days are automatically cleaned up

TRAFFIC STATS
- Global download and upload byte totals
- Total blocked, allowed, and overall connection counts
- Top apps ranked by traffic volume
- Per-app connection count breakdown
- All stats auto-refresh every 5 seconds
- Session uptime displayed on the main screen

WHO IS THIS FOR
- Privacy-conscious users who want to stop apps from phoning home
- Anyone who wants to block ads and trackers system-wide without root
- Users who want encrypted DNS to prevent ISP snooping
- Parents who want to restrict internet access for specific apps
- Users on limited data plans who want to control which apps use data
- Anyone tired of bloatware connecting to the internet in the background

PERMISSIONS EXPLAINED
- VPN Service: Creates a local VPN to filter traffic and intercept DNS. No data is sent externally.
- Query All Packages: Needed to list all installed apps so you can control each one.
- Internet: Required for the VPN tunnel, DoH queries, and optional blocklist downloads.
- Boot Completed: Auto-starts the firewall after device reboot if it was enabled.
- Foreground Service: Keeps the firewall running reliably in the background.
- Wake Lock: Prevents the system from stopping the VPN during deep sleep.
- Notifications: Shows a persistent notification when the firewall is active.

No ads. No tracking. No analytics. No data collection. Free and open source.
Made by Marko Zanoski, free with love.

---

## What's New (v1.5)

- Auto-start on boot: The firewall now starts automatically when your phone reboots, even before you unlock your device. It also restarts after app updates.
- Quick Settings tile: Toggle the firewall on or off directly from your notification shade without opening the app.
- Improved reliability on Android 10+ with direct boot awareness.

---

## Privacy Policy

A privacy policy is required. Host this at a URL you control (GitHub Pages works).

### Privacy Policy Text:

Firewall by Zanoshky ("the App") does not collect, store, transmit, or share any personal data or user information.

The App creates a local VPN service on your device to filter network traffic. All processing happens entirely on your device. No traffic is routed to external servers. No analytics, tracking, or telemetry of any kind is included.

When DNS-over-HTTPS is enabled by the user, DNS queries are encrypted and sent to the user's chosen provider (Cloudflare, Google, or Quad9) for resolution. Only the DNS query itself is sent - no user data, device identifiers, or other information is transmitted. When the user chooses to download community blocklists, the App fetches publicly available text files from GitHub. No user data is transmitted during these downloads.

The App stores firewall rules, connection logs, traffic stats, and blocklist data locally on your device using an on-device database. This data never leaves your device.

Permissions used:
- VPN Service: Used to create a local VPN for traffic filtering and DNS interception.
- Query All Packages: Used to display the list of installed apps for per-app control.
- Internet: Required for the local VPN tunnel, DNS-over-HTTPS queries, and optional blocklist downloads.
- Boot Completed: Used to restart the firewall after device reboot.
- Foreground Service: Used to keep the firewall running reliably.
- Wake Lock: Used to prevent the system from stopping the VPN during deep sleep.
- Post Notifications: Used to show a status notification when the firewall is active.

Contact: [marko@zanoski.com]

---

## QUERY_ALL_PACKAGES Declaration Form

Google Play requires justification for this permission.

**Core functionality that requires this permission:**
Firewall is a network access control app that allows users to manage internet access on a per-app basis. To provide this functionality, the app must enumerate all installed applications (including system apps) so users can configure Wi-Fi and mobile data permissions for each one. Without QUERY_ALL_PACKAGES, the app cannot display system apps or OEM services, which defeats its core purpose as a comprehensive firewall.

---

## VPN Permission Declaration

Google Play manually reviews apps using VPN.

**Justification:**
Firewall uses Android's VpnService API to create a local on-device VPN. This VPN does not route traffic to any external server. It is used solely to intercept and filter network traffic locally, allowing users to block or allow internet access per app, block known tracker domains via DNS interception, and optionally resolve DNS queries via encrypted DNS-over-HTTPS. No user data is collected or transmitted through the VPN.

---

## Graphics Required

### App Icon (512x512 PNG)
See: store_assets/icon_512.png

### Feature Graphic (1024x500 PNG)
See: store_assets/feature_graphic_1024x500.png

### Screenshots (minimum 2, recommended 4-8)
Must be captured from the running app.

Required sizes:
- Phone: 1080x1920 or 1440x2560

Recommended screenshots:
1. Main screen with firewall ON showing dashboard stats
2. Apps tab with Wi-Fi and mobile data toggles
3. Logs tab showing blocked connections and tracker blocks
4. Stats tab with traffic breakdown
5. Trackers tab with blocklist toggle and DoH toggle
6. Trackers tab showing downloaded community blocklists
7. Quick Settings tile in the notification shade
8. System apps being blocked

---

## Store Listing Checklist

- [ ] Google Play Developer account ($25) - play.google.com/console
- [ ] Signed release AAB (run release.sh or ./gradlew bundleRelease)
- [ ] App icon 512x512 PNG
- [ ] Feature graphic 1024x500 PNG
- [ ] 4+ phone screenshots (1080x1920)
- [ ] Short description filled
- [ ] Full description filled
- [ ] "What's New" section filled for v1.5
- [ ] Privacy policy hosted at a public URL
- [ ] QUERY_ALL_PACKAGES declaration submitted
- [ ] VPN permission declaration submitted
- [ ] Content rating questionnaire completed
- [ ] Target audience and content section completed
- [ ] Data safety form completed (declare: no data collected, DNS queries sent to user-chosen provider when DoH enabled, blocklist downloads from GitHub when user initiates)
