package com.zanoshky.firewall

import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FirewallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
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

    private fun updateTile() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Firewall"
        tile.updateTile()
    }
}
