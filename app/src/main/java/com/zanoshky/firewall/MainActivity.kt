package com.zanoshky.firewall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var heroCard: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtBlockedCount: TextView
    private lateinit var txtAllowedCount: TextView
    private lateinit var txtTotalDropped: TextView
    private lateinit var txtTotalApps: TextView
    private lateinit var txtUptime: TextView
    private lateinit var txtTotalTraffic: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var uptimeRunnable: Runnable? = null
    private val VPN_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        heroCard = findViewById(R.id.heroCard)
        txtStatus = findViewById(R.id.txtStatus)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtBlockedCount = findViewById(R.id.txtBlockedCount)
        txtAllowedCount = findViewById(R.id.txtAllowedCount)
        txtTotalDropped = findViewById(R.id.txtTotalDropped)
        txtTotalApps = findViewById(R.id.txtTotalApps)
        txtUptime = findViewById(R.id.txtUptime)
        txtTotalTraffic = findViewById(R.id.txtTotalTraffic)

        // ViewPager + BottomNav
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.isUserInputEnabled = false

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_apps -> viewPager.currentItem = 0
                R.id.nav_logs -> viewPager.currentItem = 1
                R.id.nav_stats -> viewPager.currentItem = 2
                R.id.nav_blocklist -> viewPager.currentItem = 3
            }
            true
        }

        // Firewall toggle
        val switchFirewall = findViewById<MaterialSwitch>(R.id.switchFirewall)
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        switchFirewall.isChecked = prefs.getBoolean("enabled", false)
        updateStatusUI(switchFirewall.isChecked)

        switchFirewall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled", isChecked).apply()
            updateStatusUI(isChecked)
            if (isChecked) startFirewall() else stopFirewall()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        startLiveUpdates()
        BlocklistManager.init(this)
        DohResolver.init(this)
    }

    override fun onDestroy() {
        uptimeRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun startLiveUpdates() {
        uptimeRunnable = object : Runnable {
            override fun run() {
                updateLiveStats()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(uptimeRunnable!!)
    }

    private fun updateLiveStats() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("enabled", false)

        val blockedHist = prefs.getLong("total_blocked", 0)
        val blockedSess = FirewallVpnService.totalBlockedSession.get()
        val allowedHist = prefs.getLong("total_allowed", 0)
        val allowedSess = FirewallVpnService.totalAllowedSession.get()

        val totalBlocked = blockedHist + blockedSess
        val totalAllowed = allowedHist + allowedSess
        txtTotalDropped.text = formatCount(totalBlocked + totalAllowed)

        val totalIn = prefs.getLong("total_bytes_in", 0) + FirewallVpnService.sessionBytesIn.get()
        val totalOut = prefs.getLong("total_bytes_out", 0) + FirewallVpnService.sessionBytesOut.get()
        txtTotalTraffic.text = "D ${formatBytes(totalIn)}  U ${formatBytes(totalOut)}"

        if (isActive && FirewallVpnService.sessionStartTime > 0) {
            val elapsed = System.currentTimeMillis() - FirewallVpnService.sessionStartTime
            txtUptime.text = formatDuration(elapsed)
        } else {
            txtUptime.text = ""
            txtTotalTraffic.text = ""
        }
    }

    fun updateCounts(allApps: List<AppInfo>) {
        val allowed = allApps.count { it.allowWifi || it.allowMobile }
        val blocked = allApps.size - allowed
        txtBlockedCount.text = blocked.toString()
        txtAllowedCount.text = allowed.toString()
        txtTotalApps.text = allApps.size.toString()
    }

    private fun updateStatusUI(active: Boolean) {
        if (active) {
            heroCard.setBackgroundResource(R.drawable.bg_hero_card)
            txtStatus.text = "Protected"
            txtSubtitle.text = "Firewall is active"
        } else {
            heroCard.setBackgroundResource(R.drawable.bg_hero_card_inactive)
            txtStatus.text = "Inactive"
            txtSubtitle.text = "Tap toggle to start"
        }
    }

    fun startFirewall() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            launchVpnService()
        }
    }

    private fun stopFirewall() {
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun launchVpnService() {
        val intent = Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            launchVpnService()
        }
    }

    private fun formatDuration(ms: Long): String {
        val secs = ms / 1000
        val mins = secs / 60
        val hours = mins / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${mins % 60}m"
            mins > 0 -> "${mins}m ${secs % 60}s"
            else -> "${secs}s"
        }
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
