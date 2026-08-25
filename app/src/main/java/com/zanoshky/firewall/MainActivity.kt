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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var switchFirewall: MaterialSwitch

    private lateinit var lockOverlay: View
    private lateinit var editLockPin: EditText
    private lateinit var txtLockError: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var uptimeRunnable: Runnable? = null
    private var lockoutRunnable: Runnable? = null
    private val VPN_REQUEST_CODE = 100

    /** While the lock overlay is up, back leaves the app rather than falling through. */
    private val lockedBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

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

        setupLockOverlay()
        onBackPressedDispatcher.addCallback(this, lockedBackCallback)

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
                R.id.nav_settings -> viewPager.currentItem = 4
            }
            true
        }

        // Keep the nav highlight in step with the pager. ViewPager2 restores its own
        // position across recreate(), which the restore flow relies on.
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = when (position) {
                    1 -> R.id.nav_logs
                    2 -> R.id.nav_stats
                    3 -> R.id.nav_blocklist
                    4 -> R.id.nav_settings
                    else -> R.id.nav_apps
                }
                if (bottomNav.selectedItemId != itemId) bottomNav.selectedItemId = itemId
            }
        })

        // Firewall toggle
        switchFirewall = findViewById(R.id.switchFirewall)
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
                // The permission dialog can background us; do not treat that as leaving.
                AppLock.suppressNextRelock()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        startLiveUpdates()
        BlocklistManager.init(this)
        DohResolver.init(this)
    }

    override fun onStart() {
        super.onStart()
        // Runs before the first draw, so locked content is never briefly visible.
        if (AppLock.needsUnlock(this)) showLock() else hideLock()
    }

    override fun onStop() {
        // A rotation or other config change tears the activity down and rebuilds it
        // straight away. That is not the user leaving the app, so it must not relock.
        if (!isChangingConfigurations) AppLock.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        syncFirewallSwitch()
        refreshHeroCounts()
    }

    /**
     * Reconcile the switch with reality. Covers:
     * - Service killed by OS/battery optimization while pref stayed "enabled".
     * - VPN revoked by another app (onRevoke clears the pref).
     * - Coming back from the VPN consent dialog after denial.
     *
     * If the pref says enabled but the service is dead, we restart it silently
     * (same as BootReceiver does). If the service is dead AND the pref was cleared
     * (revoke path), the switch flips off.
     */
    private fun syncFirewallSwitch() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val wantEnabled = prefs.getBoolean("enabled", false)

        if (wantEnabled && !FirewallVpnService.isRunning) {
            // Service died under us. Attempt a silent restart.
            launchVpnService()
        }

        // Avoid re-firing the listener by only setting when different.
        if (switchFirewall.isChecked != wantEnabled) {
            switchFirewall.isChecked = wantEnabled
        }
        updateStatusUI(wantEnabled)
    }

    override fun onDestroy() {
        uptimeRunnable?.let { handler.removeCallbacks(it) }
        lockoutRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    // --- App Lock ---

    private fun setupLockOverlay() {
        lockOverlay = findViewById(R.id.lockOverlay)
        editLockPin = findViewById(R.id.editLockPin)
        txtLockError = findViewById(R.id.txtLockError)

        findViewById<TextView>(R.id.btnUnlock).setOnClickListener { attemptUnlock() }
        editLockPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptUnlock()
                true
            } else {
                false
            }
        }
    }

    private fun showLock() {
        lockOverlay.visibility = View.VISIBLE
        lockedBackCallback.isEnabled = true
        editLockPin.text.clear()
        txtLockError.text = ""
        refreshLockout()
    }

    private fun hideLock() {
        lockOverlay.visibility = View.GONE
        lockedBackCallback.isEnabled = false
        lockoutRunnable?.let { handler.removeCallbacks(it) }
        lockoutRunnable = null
        hideKeyboard()
    }

    /** Reflects the cool-down after repeated wrong PINs, counting down once a second. */
    private fun refreshLockout() {
        lockoutRunnable?.let { handler.removeCallbacks(it) }
        val remaining = AppLock.lockoutRemainingMs()
        if (remaining <= 0) {
            editLockPin.isEnabled = true
            return
        }
        editLockPin.isEnabled = false
        val seconds = (remaining + 999) / 1000
        txtLockError.text = "Too many attempts. Try again in ${seconds}s"
        lockoutRunnable = Runnable { refreshLockout() }
        handler.postDelayed(lockoutRunnable!!, 1000)
    }

    private fun attemptUnlock() {
        if (AppLock.lockoutRemainingMs() > 0) {
            refreshLockout()
            return
        }
        val pin = editLockPin.text.toString()
        if (pin.isEmpty()) {
            txtLockError.text = "Enter your PIN"
            return
        }
        lifecycleScope.launch {
            // PBKDF2 - keep it off the main thread.
            val ok = withContext(Dispatchers.Default) { AppLock.verify(this@MainActivity, pin) }
            if (ok) {
                hideLock()
            } else {
                editLockPin.text.clear()
                if (AppLock.lockoutRemainingMs() > 0) {
                    refreshLockout()
                } else {
                    val left = AppLock.attemptsRemaining()
                    txtLockError.text = "Incorrect PIN. $left attempt${if (left == 1) "" else "s"} left"
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editLockPin.windowToken, 0)
    }

    // --- Dashboard ---

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
        txtTotalTraffic.text = "↓ ${formatBytes(totalIn)}  ↑ ${formatBytes(totalOut)}"

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

    /** Lightweight DB query so the hero card is never stale after recreate/rotation. */
    private fun refreshHeroCounts() {
        lifecycleScope.launch {
            val dao = RuleDatabase.get(this@MainActivity).ruleDao()
            val total = withContext(Dispatchers.IO) { dao.countAll() }
            val allowed = withContext(Dispatchers.IO) { dao.countAllowed() }
            txtBlockedCount.text = (total - allowed).toString()
            txtAllowedCount.text = allowed.toString()
            txtTotalApps.text = total.toString()
        }
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
            // The system consent dialog backgrounds us; do not treat that as leaving.
            AppLock.suppressNextRelock()
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
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                launchVpnService()
            } else {
                // User denied VPN consent. Roll back the pref and switch.
                val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("enabled", false).apply()
                switchFirewall.isChecked = false
                updateStatusUI(false)
            }
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
