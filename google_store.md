# Google Play Store Listing - Firewall by Zanoshky

## App Details

- **Package name:** com.zanoshky.firewall
- **App name:** Firewall - No Root Internet Blocker
- **Developer name:** Marko Zanoski
- **Category:** Tools
- **Content rating:** Everyone
- **Price:** Free

---

## Short Description (80 chars max)

Block apps from internet. Block trackers and ads. Encrypted DNS. No root.

---

## Full Description (4000 chars max)

Take back control of your phone's internet access. Firewall lets you decide exactly which apps can go online and blocks trackers, ads, and fingerprinting services across your entire device. No root required. No ads. No data collection. Completely free and open source.

WHAT MAKES FIREWALL DIFFERENT
Unlike other firewall apps, Firewall blocks everything by default. You whitelist only the apps you trust. Every other app - including system apps, OEM bloatware, and background services - is silently blocked. Your data stays on your device. No remote servers, no accounts, no analytics.

HOW IT WORKS
Firewall creates a local VPN on your device. All network traffic routes through it. Blocked apps hit a dead end - their packets go nowhere. Allowed apps bypass the VPN and connect normally. The VPN is entirely local. No traffic leaves your device through an external server.

PER-APP INTERNET CONTROL
- Every app is blocked by default - whitelist only what you trust
- Separate Wi-Fi and mobile data toggles for each app
- Block system apps and OEM bloatware (MIUI analytics, Samsung services, Huawei telemetry, etc.)
- Bulk "Allow All" and "Block All" buttons for quick setup
- Filter by All, User, or System apps with instant search
- Changes apply immediately without restarting the firewall

TRACKER, AD, AND FINGERPRINT BLOCKING
The firewall intercepts every DNS query and checks it against a blocklist of known tracker, advertising, and fingerprinting domains. Matched domains are instantly blocked with an NXDOMAIN response - the request never reaches the internet.

- 130+ tracker and ad domains blocked out of the box
- Download community blocklists with hundreds of thousands of domains:
  - HaGeZi Light (~140K domains)
  - OISD Small (~57K domains)
  - 1Hosts Lite (~195K domains)
  - Steven Black Unified (~170K domains)
  - AdGuard DNS Filter (~166K domains)
- Add your own custom blocked domains
- Whitelist specific domains to prevent false positives
- Toggle tracker blocking independently from the firewall
- Live counter of total trackers blocked

DNS OVER HTTPS (ENCRYPTED DNS)
Your ISP and network operator can normally see every domain you visit through plain-text DNS queries. DNS over HTTPS encrypts these queries so nobody can monitor your browsing activity.

- Choose from Cloudflare, Google, or Quad9 DNS providers
- Switch providers with a single tap
- Toggle DoH on or off independently
- Works alongside tracker blocking - blocked domains are caught before the DoH query is sent
- Live counter of encrypted DNS queries resolved

ALWAYS-ON PROTECTION
- Auto-starts on device boot, even before you unlock your phone
- Restarts automatically after app updates
- Direct boot aware for maximum reliability
- Quick Settings tile to toggle from your notification shade

REAL-TIME CONNECTION LOGS
- Live log of every connection attempt through the VPN
- Shows destination IP, port, protocol, and domain name
- Color-coded: green (allowed), red (blocked), orange (tracker-blocked)
- Filter logs by app name or domain
- Auto-refreshes every 3 seconds
- Logs older than 7 days are automatically cleaned up

TRAFFIC STATISTICS AND MONITORING
- Global download and upload totals
- Total blocked, allowed, and overall connection counts
- Top apps ranked by traffic volume
- Per-app connection count breakdown
- Session uptime on the main dashboard

WHO IS THIS FOR
- Privacy-conscious users who want to stop apps from phoning home
- Anyone who wants system-wide ad and tracker blocking without root
- Users who want encrypted DNS to prevent ISP snooping
- Parents who want to restrict internet access for specific apps
- Users on limited data plans who want to control which apps use mobile data
- Anyone tired of bloatware and background services wasting bandwidth

PRIVACY AND TRUST
- Zero data collection - no analytics, no crash reporting, no telemetry
- No advertising SDKs, no third-party services, no user accounts
- Fully open source - audit every line of code yourself
- All data stored locally on your device and never transmitted

DEVICE COMPATIBILITY
- Android 6.0 (Marshmallow) through Android 15
- No root required
- Works on Samsung, Xiaomi, Huawei, Pixel, OnePlus, Motorola, and all other Android devices

Free and open source. Made with care by Marko Zanoski.

---

## What's New (v1.5)

- Auto-start on boot: The firewall now starts automatically when your phone reboots, even before you unlock your device. It also restarts after app updates.
- Quick Settings tile: Toggle the firewall on or off directly from your notification shade without opening the app.
- Improved reliability on Android 10+ with direct boot awareness.

---

## ASO Keyword Strategy

### Primary Keywords (high volume, high relevance)
- firewall
- no root firewall
- block internet
- block apps
- internet blocker
- app blocker

### Secondary Keywords (medium volume, high intent)
- tracker blocker
- ad blocker no root
- dns over https
- block trackers
- privacy firewall
- block ads android

### Long-tail Keywords (lower volume, very high intent)
- block app internet access
- block system apps internet
- per app firewall android
- block bloatware internet
- encrypted dns android
- stop apps phoning home

### Competitor Keywords
- netguard
- afwall
- noroot firewall
- droidwall
- adguard
- blokada

### Keyword Placement Summary
- Title: "Firewall - No Root Internet Blocker" (primary: firewall, no root, internet blocker)
- Short desc: "Block apps from internet. Block trackers and ads. Encrypted DNS. No root." (block apps, block trackers, ads, encrypted DNS, no root)
- Full desc: All primary, secondary, and long-tail keywords appear naturally in feature descriptions and the "WHO IS THIS FOR" section

---

## Privacy Policy

A privacy policy is required. Host this at a URL you control (GitHub Pages works).

See: PRIVACY_POLICY.md

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
- [ ] Signed release AAB (run: bundle exec fastlane build_release)
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
- [ ] Data safety form completed
