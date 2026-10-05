# Firewall

A firewall for Android that needs no root, with tracker blocking, DNS over HTTPS, and a log of everything your phone looks up. You decide, app by app, what the firewall does with it.

No ads. No tracking. No analytics. No data collection. No external servers. Everything runs locally on your device.

## How it works

Firewall runs a local VPN, but it does not carry your traffic. The tunnel claims one small, reserved range of addresses: its own resolver, the sinkhole that blocked names point at, and the handful of public resolvers that apps reach for directly. Your photos, your video and your downloads never enter the app at all. They leave the phone over the real network at full speed.

What does come through the tunnel is every name your phone looks up. That is the point where a firewall can actually decide something, and it is cheap: lookups are small and rare, so there is nothing keeping the CPU awake and nothing to slow a download down.

A name that is blocked is answered with an address that goes nowhere, and the connection that follows is refused on the spot. A name that is allowed is resolved for real, encrypted if you asked for that.

## How each app is handled

Every app is in one of three modes, and the Apps tab says which:

- **Bypass**: outside the tunnel. Untouched, unfiltered, costs nothing. For an app that has to use the network's own resolver, or that you simply do not want the firewall near.
- **Filtered**: inside the tunnel. Its lookups go through the firewall, so encryption, tracker blocking and your domain rules all apply to it. This is the mode that makes blocking a website actually work in your browser.
- **Blocked**: inside the tunnel and given no working addresses at all, so it cannot open anything new.

Changes take effect on the app's very next lookup. There is no waiting and no restarting the firewall.

Two things worth knowing. An app that is already running can hold on to connections it opened before you changed the rule, so close it fully and reopen it. And blocking works by refusing names: an app with an address written into its code can still try that one address.

The old Wi-Fi and mobile toggles are gone. They said nothing about what happened to the traffic, and an app you allowed was excluded from the tunnel entirely, which is why blocking a domain never did anything to it.

## Features

### Domain rules

Your own block list and allow list for ordinary websites, kept apart from the downloaded tracker lists.

- A rule covers the domain and everything under it, so blocking `google.com` also blocks `www.google.com` and `mail.google.com`
- The more specific rule wins, so you can block a domain and allow one name inside it
- The allow list is also how you undo a tracker list that got something wrong
- Paste a full URL if that is easier; it is reduced to the host name
- Rules apply at once, to every app set to Filtered

### Tracker and ad blocking

Every lookup is checked against a list of known tracker, ad and fingerprinting domains.

- Bundled default list, active out of the box
- Optional community lists: HaGeZi Light, OISD Small, 1Hosts Lite, Steven Black Unified, AdGuard DNS Filter
- Downloaded once and kept on the phone
- Can be turned off without turning the firewall off

### DNS over HTTPS

With this on, lookups are encrypted and sent to a provider of your choice instead of travelling in plain text, so your network operator and anyone else on the same Wi-Fi cannot read which sites you open.

- Cloudflare, Google or Quad9
- Falls back to the network's own resolver if the encrypted path fails, rather than leaving you with no answer
- Works alongside tracker blocking: a blocked name is stopped before any query is sent

Apps that do their own DNS over HTTPS take lookups away from the firewall, and there is nothing a firewall without root can do about that.

### Private DNS

Android's Private DNS setting only gets in the way when it names a provider, such as `dns.google`. Then Android sends every lookup straight to that provider and the firewall never sees them. Automatic and Off are both fine.

When a provider is set, the Activity tab says so, and a tap on the notice offers a fix:

- **With one-time permission**: the firewall switches Private DNS to Automatic while it runs and puts your setting back the moment you turn it off. If you change the setting yourself in the meantime, your change stands. Your lookups stay encrypted, because DNS over HTTPS is turned on in its place, with the same provider for Google, Cloudflare and Quad9. Grant the permission once from a computer with USB debugging on:

  ```
  adb shell pm grant com.zanoshky.firewall android.permission.WRITE_SECURE_SETTINGS
  ```

- **Without it**: the app explains what to change, copies the command above for you, and opens Network and internet, where the setting lives.

### Activity

Stats and log in one place, because the numbers and the entries behind them belong on the same screen.

- Lookups seen, how many were stopped, and what share of them were encrypted
- Which did the stopping: tracker lists, your own rules, or a blocked app
- A bar per hour for the last day, green for let through and red for stopped
- Where your apps stand: filtered, bypassed, blocked, and how long protection has been on
- The names blocked most often
- The full log, filterable by app, domain or address, with chips for Blocked, Allowed and Trackers
- Tap an entry to block that domain, or to always allow it
- Export to CSV

### App insights

Tap an app on the Apps tab to see what it did over the last week.

- Lookups, how many were stopped, and how many tracker companies it reached
- Each company with how often the app went for it and whether the firewall stopped it, got through, or partly stopped it
- Around 45 companies are recognised, from Google, Meta and Amazon to ByteDance, Tencent, Alibaba, Baidu, Xiaomi and Huawei, plus SDKs such as AppsFlyer, Adjust, Umeng, Getui and JPush. Only names used for ads, analytics or telemetry count, and an app's own maker is never counted against it
- The names it looked up most; tap one to block or allow it
- Share a one-line summary

### Weekly summary

Once a week, a notification with what the firewall stopped, how many tracker companies your apps went for, and which app went for the most. It is worked out on the phone from the activity kept here. Turn it off, or show it on demand, in Settings. Activity is now kept for seven days, up to 50,000 entries.

### Home screen widget

Shows whether protection is on, what was stopped today, and a switch. With App Lock on, turning protection off from the widget opens the app for the passcode, as the tile does.

### Starting on boot

The firewall restarts when your phone reboots, before you unlock it, and after an app update. For this to be reliable, exclude Firewall from battery optimisation in your device settings.

### Quick settings tile

Toggle the firewall from the notification shade. With App Lock on, turning protection off from the tile opens the app for the passcode.

### App Lock

Ask for a passcode before the app opens, so your rules cannot be changed by someone holding your phone.

- Letters, digits and symbols, four characters or more
- Hashed with PBKDF2, never stored as text
- Locked out for 30 seconds after five wrong tries
- Locks again whenever the app leaves the foreground
- No recovery: forget it and you have to reinstall

### Backup and restore

Export everything you configured to a single JSON file and restore it here or on another phone.

- Included: how each app is handled, your domain rules, which tracker lists you downloaded, and your tracker and encryption settings
- Not included: your passcode, your activity and your counters
- Restore replaces rather than merges, and fetches the tracker lists again
- Plain JSON, no encryption, so keep it somewhere safe

## Getting started

1. Install the app
2. Tap the switch on the main screen and approve the VPN prompt
3. Open the Apps tab and choose how each app is handled. "Filter all" is a good starting point, then move anything that misbehaves to Bypass
4. Open the Trackers tab to turn on tracker blocking and encrypted lookups
5. Open the Domains tab to block anything you want gone

## Permissions

| Permission | Why |
|---|---|
| VPN Service | Runs the local tunnel that carries lookups. No traffic leaves your device through a remote server. |
| Query All Packages | Lists your installed apps so each one can be handled separately. |
| Internet | The tunnel, encrypted lookups, and tracker list downloads. |
| Boot Completed | Restarts the firewall after a reboot if it was on. |
| Foreground Service | Keeps the firewall running. |
| Notifications | The status notification while the firewall is active, required on Android 13 and newer. |

## Privacy

This app collects nothing. No analytics, no crash reporting, no telemetry. The only outbound connections it makes are:

- Encrypted lookups to the provider you chose, and only when you turn that on
- Tracker list downloads from GitHub, and only when you tap download

Your rules and your activity are stored on the device and never leave it.

## Compatibility

- Android 6.0 through Android 16
- No root
- Works on Samsung, Xiaomi, Huawei, Pixel, OnePlus and the rest

## What's new

See the [changelog](CHANGELOG.md). The headline of 1.11: blocking a domain finally works in apps you allowed, apps are handled by mode rather than by network, and the logs and stats are one screen.

## License

MIT License. Do whatever you want with it.
