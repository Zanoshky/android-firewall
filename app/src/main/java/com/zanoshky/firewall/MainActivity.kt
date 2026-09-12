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
    private lateinit var txtHeroFiltered: TextView
    private lateinit var txtHeroBypass: TextView
    private lateinit var txtHeroBlocked: TextView
    private lateinit var txtHeroStopped: TextView
    private lateinit var txtUptime: TextView
    private lateinit var txtHeroSummary: TextView
    private lateinit var switchFirewall: MaterialSwitch

    /** True while the code is correcting the switch, so its listener stays quiet. */
    private var syncingSwitch = false

    private lateinit var lockOverlay: View
    private lateinit var editPasscode: EditText
    private lateinit var txtLockError: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var liveRunnable: Runnable? = null
    private var lockoutRunnable: Runnable? = null
    private val vpnRequestCode = 100

    /** Apps with a uid of their own, counted once; the modes change, this does not. */
    private var installedApps = 0

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
        txtHeroFiltered = findViewById(R.id.txtHeroFiltered)
        txtHeroBypass = findViewById(R.id.txtHeroBypass)
        txtHeroBlocked = findViewById(R.id.txtHeroBlocked)
        txtHeroStopped = findViewById(R.id.txtHeroStopped)
        txtUptime = findViewById(R.id.txtUptime)
        txtHeroSummary = findViewById(R.id.txtHeroSummary)

        setupLockOverlay()
        onBackPressedDispatcher.addCallback(this, lockedBackCallback)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.isUserInputEnabled = false
        viewPager.offscreenPageLimit = 1

        bottomNav.setOnItemSelectedListener { item ->
            viewPager.currentItem = when (item.itemId) {
                R.id.nav_activity -> 1
                R.id.nav_domains -> 2
                R.id.nav_trackers -> 3
                R.id.nav_settings -> 4
                else -> 0
            }
            true
        }

        // Keep the nav highlight in step with the pager. ViewPager2 restores its own
        // position across recreate(), which the restore flow relies on.
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = when (position) {
                    1 -> R.id.nav_activity
                    2 -> R.id.nav_domains
                    3 -> R.id.nav_trackers
                    4 -> R.id.nav_settings
                    else -> R.id.nav_apps
                }
                if (bottomNav.selectedItemId != itemId) bottomNav.selectedItemId = itemId
            }
        })

        switchFirewall = findViewById(R.id.switchFirewall)
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        switchFirewall.isChecked = prefs.getBoolean("enabled", false)
        updateStatusUI(switchFirewall.isChecked)

        switchFirewall.setOnCheckedChangeListener { _, isChecked ->
            // Programmatic corrections must not be mistaken for a tap, or resuming
            // the activity would start or stop the tunnel all over again.
            if (syncingSwitch) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("enabled", isChecked).apply()
            updateStatusUI(isChecked)
            if (isChecked) startFirewall() else stopFirewall()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // The permission dialog can background us; do not treat that as leaving.
                AppLock.suppressNextRelock()
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        BlocklistManager.init(this)
        DohResolver.init(this)
        DomainRules.init(this)
        RuleStore.ensureLoaded(this)
        lifecycleScope.launch {
            installedApps = withContext(Dispatchers.IO) {
                try { packageManager.getInstalledApplications(0).count { it.uid > 1000 } }
                catch (_: Exception) { 0 }
            }
        }
        startLiveUpdates()
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
    }

    /**
     * Reconcile the switch with reality. Covers the service being killed for
     * battery or memory while the preference still said enabled, another VPN
     * revoking ours, and coming back from the consent dialog after a refusal.
     */
    private fun syncFirewallSwitch() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val wantEnabled = prefs.getBoolean("enabled", false)
        if (wantEnabled && !FirewallVpnService.isRunning) launchVpnService()
        setSwitchSilently(wantEnabled)
        updateStatusUI(wantEnabled)
    }

    /** Move the switch to [checked] without the listener treating it as a tap. */
    private fun setSwitchSilently(checked: Boolean) {
        if (switchFirewall.isChecked == checked) return
        syncingSwitch = true
        switchFirewall.isChecked = checked
        syncingSwitch = false
    }

    override fun onDestroy() {
        liveRunnable?.let { handler.removeCallbacks(it) }
        lockoutRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    // --- App Lock ---

    private fun setupLockOverlay() {
        lockOverlay = findViewById(R.id.lockOverlay)
        editPasscode = findViewById(R.id.editLockPasscode)
        txtLockError = findViewById(R.id.txtLockError)

        findViewById<TextView>(R.id.btnUnlock).setOnClickListener { attemptUnlock() }
        editPasscode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptUnlock(); true } else false
        }
    }

    private fun showLock() {
        lockOverlay.visibility = View.VISIBLE
        lockedBackCallback.isEnabled = true
        editPasscode.text.clear()
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

    /** Reflects the cool-down after repeated wrong tries, counting down once a second. */
    private fun refreshLockout() {
        lockoutRunnable?.let { handler.removeCallbacks(it) }
        val remaining = AppLock.lockoutRemainingMs()
        if (remaining <= 0) {
            editPasscode.isEnabled = true
            return
        }
        editPasscode.isEnabled = false
        val seconds = (remaining + 999) / 1000
        txtLockError.text = "Too many tries. Wait ${seconds}s"
        lockoutRunnable = Runnable { refreshLockout() }
        handler.postDelayed(lockoutRunnable!!, 1000)
    }

    private fun attemptUnlock() {
        if (AppLock.lockoutRemainingMs() > 0) {
            refreshLockout()
            return
        }
        val passcode = editPasscode.text.toString()
        if (passcode.isEmpty()) {
            txtLockError.text = "Enter your passcode"
            return
        }
        lifecycleScope.launch {
            // PBKDF2, so keep it off the main thread.
            val ok = withContext(Dispatchers.Default) { AppLock.verify(this@MainActivity, passcode) }
            if (ok) {
                hideLock()
            } else {
                editPasscode.text.clear()
                if (AppLock.lockoutRemainingMs() > 0) {
                    refreshLockout()
                } else {
                    val left = AppLock.attemptsRemaining()
                    txtLockError.text = "Wrong passcode. $left ${if (left == 1) "try" else "tries"} left"
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editPasscode.windowToken, 0)
    }

    // --- Hero card ---

    private fun startLiveUpdates() {
        liveRunnable = object : Runnable {
            override fun run() {
                updateLiveStats()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(liveRunnable!!)
    }

    private fun updateLiveStats() {
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val active = prefs.getBoolean("enabled", false)
        val totals = Stats.totals(this)

        txtHeroStopped.text = formatCount(totals.blocked)
        refreshModeCounts()

        if (active && Stats.sessionStart > 0) {
            txtUptime.text = formatDuration(System.currentTimeMillis() - Stats.sessionStart)
            txtHeroSummary.text = "${formatCount(totals.queries)} lookups seen"
        } else {
            txtUptime.text = ""
            txtHeroSummary.text = ""
        }
    }

    /**
     * Called by the Apps tab the moment a mode changes, so the card never lags
     * behind a tap. The two second tick recomputes the same thing from the rule
     * store, which covers the app being opened on any other tab.
     */
    fun updateCounts(allApps: List<AppInfo>) {
        if (allApps.isEmpty()) return
        if (installedApps == 0) installedApps = allApps.size
        txtHeroFiltered.text = allApps.count { it.mode == AppMode.FILTERED }.toString()
        txtHeroBypass.text = allApps.count { it.mode == AppMode.BYPASS }.toString()
        txtHeroBlocked.text = allApps.count { it.mode == AppMode.BLOCKED }.toString()
    }

    private fun refreshModeCounts() {
        if (installedApps == 0) return
        val modes = RuleStore.snapshot()
        val filtered = modes.count { it.value == AppMode.FILTERED }
        val bypass = modes.count { it.value == AppMode.BYPASS }
        txtHeroFiltered.text = filtered.toString()
        txtHeroBypass.text = bypass.toString()
        txtHeroBlocked.text = (installedApps - filtered - bypass).coerceAtLeast(0).toString()
    }

    private fun updateStatusUI(active: Boolean) {
        if (active) {
            heroCard.setBackgroundResource(R.drawable.bg_hero_card)
            txtStatus.text = "Protected"
            txtSubtitle.text = if (DohResolver.isEnabled) {
                "Lookups filtered and encrypted"
            } else {
                "Lookups filtered"
            }
        } else {
            heroCard.setBackgroundResource(R.drawable.bg_hero_card_inactive)
            txtStatus.text = "Inactive"
            txtSubtitle.text = "Tap the switch to start"
        }
    }

    fun startFirewall() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // The system consent dialog backgrounds us; do not treat that as leaving.
            AppLock.suppressNextRelock()
            startActivityForResult(vpnIntent, vpnRequestCode)
        } else {
            launchVpnService()
        }
    }

    private fun stopFirewall() {
        startService(Intent(this, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_STOP
        })
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
        if (requestCode != vpnRequestCode) return
        if (resultCode == Activity.RESULT_OK) {
            launchVpnService()
        } else {
            // The user refused VPN consent. Roll the preference and switch back.
            getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", false).apply()
            setSwitchSilently(false)
            updateStatusUI(false)
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}
