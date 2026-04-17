package com.zanoshky.firewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        val bootActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )

        if (action !in bootActions) return

        Log.i(TAG, "Boot event received: $action")

        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) {
            Log.i(TAG, "Firewall not enabled, skipping auto-start")
            return
        }

        Log.i(TAG, "Starting firewall after boot")
        val svc = Intent(context, FirewallVpnService::class.java).apply {
            this.action = FirewallVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
