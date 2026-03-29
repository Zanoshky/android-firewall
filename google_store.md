# Google Play Store Listing — Firewall by Zanoshky

## App Details

- **Package name:** com.zanoshky.firewall
- **App name:** Firewall — Block Internet & Trackers
- **Developer name:** Marko Zanoški
- **Category:** Tools
- **Content rating:** Everyone
- **Price:** Free

---

## Short Description (80 chars max)

Block internet per app. Block trackers. DNS over HTTPS. No root needed.

---

## Full Description (4000 chars max)

Firewall gives you full control over which apps can access the internet — and blocks trackers, ads, and fingerprinting services across your entire device. No root required.

Every app is blocked by default. You decide which ones get Wi-Fi access, mobile data access, or both. This includes system apps and manufacturer services from Xiaomi, Samsung, Huawei, and others.

HOW IT WORKS
Firewall creates a local VPN on your device. All network traffic passes through this VPN. Apps you haven't allowed are blocked — their traffic goes nowhere. Apps you allow bypass the VPN and connect normally. No data is collected, no traffic leaves your device, no external servers involved.

PER-APP FIREWALL
• Block all apps by default — whitelist only what you trust
• Separate Wi-Fi and mobile data toggles per app
• Block system apps and OEM bloatware (MIUI analytics, Samsung services, etc.)
• Filter apps by User / System with search
• Auto-start on boot — stays active after reboot

TRACKER & AD BLOCKING
• Blocks 130+ known tracker, ad, and fingerprinting domains out of the box
• Download community blocklists: Steven Black, Energized, AdGuard, OISD
• Add your own custom blocked domains
• Whitelist domains to prevent false positives
• Toggle tracker blocking on/off independently from the firewall

DNS OVER HTTPS
• Encrypt your DNS queries so your ISP can't see what sites you visit
• Choose your provider: Cloudflare, Google, or Quad9
• Blocked tracker domains get instant NXDOMAIN — never reach the internet
• Toggle DoH on/off independently

CONNECTION LOGS
• See every connection attempt in real time
• Shows destination IP, port, protocol, and domain name
• Color-coded: allowed (green), blocked (red), tracker (orange)
• Filter by app name or domain
• Auto-refreshes every 3 seconds

TRAFFIC STATS & CHARTS
• Global download and upload totals
• Blocked vs allowed pie chart
• Top apps by traffic bar chart
• Per-app traffic breakdown with byte counts
• All charts update live every 5 seconds

WHO IS THIS FOR
• Privacy-conscious users who want to stop apps from phoning home
• Anyone who wants to block ads and trackers system-wide without root
• Users who want encrypted DNS to prevent ISP snooping
• Parents who want to restrict internet access for specific apps
• Users on limited data plans who want to control which apps use data
• Anyone tired of bloatware connecting to the internet in the background

PERMISSIONS EXPLAINED
• VPN Service: Creates a local VPN to filter traffic and intercept DNS. No data is sent externally.
• Query All Packages: Needed to list all installed apps so you can control each one.
• Internet: Required for the VPN tunnel, DoH queries, and optional blocklist downloads.
• Boot Completed: Auto-starts the firewall after device reboot if it was enabled.
• Foreground Service: Keeps the firewall running reliably in the background.
• Notifications: Shows a persistent notification when the firewall is active.

No ads. No tracking. No analytics. No data collection. Free and open source.
Made by Marko Zanoški, free with love.

---

## Privacy Policy

A privacy policy is required. Host this at a URL you control (GitHub Pages works).

### Privacy Policy Text:

Firewall by Zanoshky ("the App") does not collect, store, transmit, or share any personal data or user information.

The App creates a local VPN service on your device to filter network traffic. All processing happens entirely on your device. No traffic is routed to external servers. No analytics, tracking, or telemetry of any kind is included.

When DNS-over-HTTPS is enabled by the user, DNS queries are sent to the user's chosen provider (Cloudflare, Google, or Quad9) for encrypted resolution. No other data is sent. When the user chooses to download community blocklists, the App fetches publicly available text files from GitHub. No user data is transmitted during these downloads.

The App stores firewall rules, connection logs, and blocklist data locally on your device using an on-device database. This data never leaves your device.

Permissions used:
- VPN Service: Used to create a local VPN for traffic filtering and DNS interception.
- Query All Packages: Used to display the list of installed apps for per-app control.
- Internet: Required for the local VPN tunnel, DNS-over-HTTPS queries, and optional blocklist downloads.
- Boot Completed: Used to restart the firewall after device reboot.
- Foreground Service: Used to keep the firewall running reliably.
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
→ See: store_assets/icon_512.png

### Feature Graphic (1024x500 PNG)
→ See: store_assets/feature_graphic_1024x500.png

### Screenshots (minimum 2, recommended 4-8)
→ Must be captured from the running app.

Required sizes:
- Phone: 1080x1920 or 1440x2560

Recommended screenshots:
1. Main screen with firewall ON, showing stats cards
2. Apps tab with Wi-Fi/Mobile toggles
3. Logs tab showing blocked packets and tracker blocks
4. Stats tab with pie chart and bar chart
5. Trackers tab with blocklist toggle and DoH toggle
6. Trackers tab showing downloaded sources
7. System apps being blocked

---

## Store Listing Checklist

- [ ] Google Play Developer account ($25) — play.google.com/console
- [ ] Signed release AAB (`./gradlew bundleRelease`)
- [ ] App icon 512x512 PNG
- [ ] Feature graphic 1024x500 PNG
- [ ] 4+ phone screenshots (1080x1920)
- [ ] Short description filled
- [ ] Full description filled
- [ ] Privacy policy hosted at a public URL
- [ ] QUERY_ALL_PACKAGES declaration submitted
- [ ] VPN permission declaration submitted
- [ ] Content rating questionnaire completed
- [ ] Target audience and content section completed
- [ ] Data safety form completed (declare: no data collected, DNS queries sent to user-chosen provider when DoH enabled)
