# Google Play Store Listing — Firewall by Zanoshky

## App Details

- **Package name:** com.zanoshky.firewall
- **App name:** Firewall — Block Internet Access
- **Developer name:** Marko Zanoški
- **Category:** Tools
- **Content rating:** Everyone
- **Price:** Free

---

## Short Description (80 chars max)

Block internet for any app. No root. Control Wi-Fi & mobile data per app.

---

## Full Description (4000 chars max)

Firewall gives you full control over which apps can access the internet on your Android device — no root required.

Every app is blocked by default. You decide which ones get Wi-Fi access, mobile data access, or both. This includes system apps and manufacturer services from Xiaomi, Samsung, Huawei, and others.

HOW IT WORKS
Firewall creates a local VPN on your device. All network traffic passes through this VPN. Apps you haven't allowed are sent to a dead end — their traffic goes nowhere. Apps you allow bypass the VPN and connect normally. No data is collected, no traffic leaves your device, no external servers involved.

FEATURES
• Block all apps by default — whitelist only what you trust
• Separate Wi-Fi and mobile data toggles per app
• Block system apps and OEM bloatware (MIUI analytics, Samsung services, etc.)
• Filter apps by User / System with search
• Auto-start on boot — stays active after reboot
• Foreground notification shows firewall status
• Beautiful dark glassmorphism UI
• Lightweight — minimal battery impact
• No ads, no tracking, no data collection
• Free and open source

WHO IS THIS FOR
• Privacy-conscious users who want to stop apps from phoning home
• Parents who want to restrict internet access for specific apps
• Users on limited data plans who want to control which apps use mobile data
• Anyone tired of bloatware connecting to the internet in the background

PERMISSIONS EXPLAINED
• VPN Service: Creates a local VPN to filter traffic. No data is sent externally.
• Query All Packages: Needed to list all installed apps so you can control each one.
• Internet: Required for the VPN tunnel to function.
• Boot Completed: Auto-starts the firewall after device reboot if it was enabled.
• Foreground Service: Keeps the firewall running reliably in the background.
• Notifications: Shows a persistent notification when the firewall is active.

Made by Marko Zanoški, free with love.

---

## Privacy Policy

A privacy policy is required. Host this at a URL you control (GitHub Pages works).

### Privacy Policy Text:

Firewall by Zanoshky ("the App") does not collect, store, transmit, or share any personal data or user information.

The App creates a local VPN service on your device to filter network traffic. All processing happens entirely on your device. No traffic is routed to external servers. No analytics, tracking, or telemetry of any kind is included.

The App stores your per-app firewall rules locally on your device using an on-device database. This data never leaves your device.

Permissions used:
- VPN Service: Used to create a local VPN for traffic filtering. No data is sent externally.
- Query All Packages: Used to display the list of installed apps for per-app control.
- Internet: Required for the local VPN tunnel to function.
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
Firewall uses Android's VpnService API to create a local on-device VPN. This VPN does not route traffic to any external server. It is used solely to intercept and filter network traffic locally, allowing users to block or allow internet access per app. This is the standard approach used by firewall apps on Android without root access. No user data is collected or transmitted.

---

## Graphics Required

### App Icon (512x512 PNG)
→ See: store_assets/icon_512.png (generated below)

### Feature Graphic (1024x500 PNG)
→ Must be created. Suggested design:
  - Dark background (#050510)
  - Purple and cyan gradient orbs (matching app theme)
  - Shield icon centered
  - Text: "Firewall" in white, "Control your internet" below
  - Clean, minimal, dark aesthetic

### Screenshots (minimum 2, recommended 4-8)
→ Must be captured from the running app.

Required sizes:
- Phone: 1080x1920 or 1440x2560
- Tablet (optional): 1200x1920

Recommended screenshots:
1. Main screen with firewall ON, showing blocked/allowed counts
2. App list showing user apps with W/M toggles
3. System tab showing OEM bloatware being blocked
4. Search filtering apps

**How to capture:**
```bash
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png screenshot_1.png
```

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
- [ ] Data safety form completed (declare: no data collected)
