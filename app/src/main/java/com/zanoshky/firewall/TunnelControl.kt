package com.zanoshky.firewall

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Single place that asks the VPN service to rebuild its tunnel.
 *
 * Per-app rules are read once per tunnel build (see [FirewallVpnService.runVpn]),
 * so any change to the rules table only takes effect after a rebuild. Re-sending
 * ACTION_START is the rebuild signal; the service debounces it internally with
 * its own isRebuilding guard.
 *
 * Always pass a context whose lifetime outlives the caller's view - a dropped
 * rebuild would leave a newly blocked app sitting in the tunnel's bypass list
 * with full network access.
 */
object TunnelControl {

    /** No-op when the firewall is switched off; there is no tunnel to rebuild. */
    fun requestRebuild(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return

        val intent = Intent(appCtx, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appCtx.startForegroundService(intent)
        } else {
            appCtx.startService(intent)
        }
    }
}
