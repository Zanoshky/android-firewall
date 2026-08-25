package com.zanoshky.firewall

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/** Android only binds quick settings tiles from API 24 onwards. */
@RequiresApi(Build.VERSION_CODES.N)
class FirewallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)

        // The tile can reach the firewall without going through the UI, so App Lock
        // would be trivially bypassable from the notification shade. Turning
        // protection on is never a threat; turning it off is, so that path is sent
        // through the app where the PIN prompt lives.
        if (enabled && AppLock.needsUnlock(this)) {
            openAppForUnlock()
            return
        }

        prefs.edit().putBoolean("enabled", !enabled).apply()

        if (!enabled) {
            val intent = Intent(this, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            val intent = Intent(this, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_STOP
            }
            startService(intent)
        }
        updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppForUnlock() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
        tile.state = if (enabled && FirewallVpnService.isRunning) Tile.STATE_ACTIVE
                     else if (enabled) Tile.STATE_ACTIVE // starting up
                     else Tile.STATE_INACTIVE
        tile.label = "Firewall"
        tile.updateTile()
    }
}
