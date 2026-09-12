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

Real domain blocking, tracker blocking and DNS over HTTPS. No root.

---

## Full Description (4000 chars max)

A firewall for Android that needs no root, with tracker blocking, DNS over HTTPS, and a log of everything your phone looks up. You decide, app by app, what the firewall does with it.

No ads. No tracking. No analytics. No data collection. No external servers. Everything runs locally on your device.

HOW IT WORKS

Firewall runs a local VPN, but it does not carry your traffic. The tunnel claims one small, reserved range of addresses, and what comes through it is every name your phone looks up. That is the point where a firewall can actually decide something, and it is cheap: your photos, your video and your downloads never enter the app at all, so nothing is slowed down and nothing keeps the CPU awake.

A name that is blocked is answered with an address that goes nowhere, and the connection that follows is refused on the spot. A name that is allowed is resolved for real, encrypted if you asked for that.

HOW EACH APP IS HANDLED

Bypass: outside the firewall. Untouched, unfiltered, costs nothing.
Filtered: its lookups go through the firewall, so encryption, tracker blocking and your domain rules all apply.
Blocked: no working addresses at all, so it cannot open anything new.

Changes take effect on the app's next lookup. Nothing has to be restarted.

FEATURES

Domain rules
- Your own block list and allow list for ordinary websites
- A rule covers the domain and everything under it
- The more specific rule wins, so block a domain and allow one name inside it
- Rules apply the moment you add them, in every filtered app

Tracker and ad blocking
- Checks every lookup against known tracker, ad and fingerprinting domains
- Bundled list active out of the box
- Optional community lists: HaGeZi, OISD, 1Hosts, Steven Black, AdGuard

DNS over HTTPS
- Encrypts your lookups so your network operator cannot read which sites you open
- Cloudflare, Google or Quad9
- Falls back to the network's own resolver rather than leaving you with no answer

Activity
- Lookups seen, how many were stopped, and what share were encrypted
- Which did the stopping: tracker lists, your own rules, or a blocked app
- An hour by hour chart of the last day, and the names blocked most often
- The full log, searchable, with the app behind every entry
- Tap an entry to block that domain, or to always allow it
- Export to CSV

App Lock
- A passcode before the app opens, letters and symbols included
- Hashed with PBKDF2, locked out after repeated wrong tries

Backup and restore
- Export how each app is handled, your domain rules and your settings to JSON
- Restore here or on another phone

Starting on boot
- Restarts after a reboot or an app update, before you unlock

Quick settings tile
- Toggle from the notification shade, and it respects App Lock

PRIVACY

Nothing is collected. No analytics, no crash reporting, no telemetry. The only outbound connections are encrypted lookups to the provider you chose, and tracker list downloads when you tap download.

COMPATIBILITY

Android 6.0 through Android 16. No root. Works on all devices.

---

## What's New (v1.11)

Blocking a domain now actually blocks it. Apps you allowed used to sit outside the firewall, so the domain list never saw what they looked up.

Every app is now Bypass, Filtered or Blocked, and a change applies at once. The Wi-Fi and mobile toggles are gone.

New Domains tab for your own block and allow lists.

Logs and stats are one Activity screen: what was stopped and why, an hourly chart, and the names blocked most often.

The passcode can be letters and symbols, not only digits.

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
