# Privacy Policy — Firewall by Zanoshky

**Last updated:** March 2026

## Summary

This app collects nothing. Zero. Nada.

## Details

Firewall ("the App") does not collect, store, transmit, or share any personal data or user information of any kind.

- No analytics
- No crash reporting
- No telemetry
- No advertising SDKs
- No third-party services
- No network calls to external servers
- No user accounts

The App creates a local VPN service on your device to filter network traffic. All processing happens entirely on your device. No traffic is routed to, inspected by, or forwarded to external servers.

Your per-app firewall rules are stored in a local on-device database (Room/SQLite). This data never leaves your device.

## Permissions

- **VPN Service:** Creates a local VPN for on-device traffic filtering. No data is sent externally.
- **Query All Packages:** Displays the list of installed apps for per-app firewall control.
- **Internet:** Required for the local VPN tunnel mechanism to function.
- **Boot Completed:** Restarts the firewall after device reboot if it was previously enabled.
- **Foreground Service:** Keeps the firewall running reliably in the background.
- **Post Notifications:** Shows a persistent notification when the firewall is active (Android 13+).

## Open Source

This app is fully open source. You can audit every line of code yourself.

## Contact

Marko Zanoški
GitHub: https://github.com/YOUR_USERNAME/firewall
