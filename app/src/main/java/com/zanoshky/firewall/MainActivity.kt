package com.zanoshky.firewall

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private lateinit var dao: RuleDao
    private var allApps: List<AppInfo> = emptyList()
    private var currentFilter = Filter.ALL
    private var searchQuery = ""

    private lateinit var txtStatus: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtBlockedCount: TextView
    private lateinit var txtAllowedCount: TextView

    private val VPN_REQUEST_CODE = 100

    private enum class Filter { ALL, USER, SYSTEM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dao = RuleDatabase.get(this).ruleDao()

        txtStatus = findViewById(R.id.txtStatus)
        txtSubtitle = findViewById(R.id.txtSubtitle)
        txtBlockedCount = findViewById(R.id.txtBlockedCount)
        txtAllowedCount = findViewById(R.id.txtAllowedCount)

        adapter = AppAdapter(
            onToggleWifi = { app -> saveRule(app) },
            onToggleMobile = { app -> saveRule(app) }
        )

        val recycler = findViewById<RecyclerView>(R.id.recyclerApps)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val switchFirewall = findViewById<MaterialSwitch>(R.id.switchFirewall)
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        switchFirewall.isChecked = prefs.getBoolean("enabled", false)
        updateStatusUI(switchFirewall.isChecked)

        switchFirewall.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled", isChecked).apply()
            updateStatusUI(isChecked)
            if (isChecked) startFirewall() else stopFirewall()
        }

        // Animate ambient orbs
        animateOrbs()

        // Tab filter
        findViewById<TabLayout>(R.id.tabFilter).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = when (tab.position) {
                    1 -> Filter.USER
                    2 -> Filter.SYSTEM
                    else -> Filter.ALL
                }
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Search
        findViewById<EditText>(R.id.editSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.lowercase() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        loadApps()
    }

    private fun animateOrbs() {
        val orbPurple = findViewById<View>(R.id.orbPurple)
        val orbCyan = findViewById<View>(R.id.orbCyan)

        // Purple orb: slow diagonal drift
        ObjectAnimator.ofFloat(orbPurple, "translationY", 0f, 50f, 0f).apply {
            duration = 8000
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(orbPurple, "translationX", 0f, 30f, 0f).apply {
            duration = 10000
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }

        // Cyan orb: slow vertical float
        ObjectAnimator.ofFloat(orbCyan, "translationY", 0f, -40f, 0f).apply {
            duration = 7000
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(orbCyan, "translationX", 0f, -25f, 0f).apply {
            duration = 9000
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun updateStatusUI(active: Boolean) {
        if (active) {
            txtStatus.text = "● Protected"
            txtStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            txtStatus.setBackgroundResource(R.drawable.bg_status_badge)
            txtSubtitle.text = "Firewall is active"
        } else {
            txtStatus.text = "● Unprotected"
            txtStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            txtStatus.setBackgroundResource(R.drawable.bg_toggle_off)
            txtSubtitle.text = "All traffic blocked"
        }
    }

    private fun updateCounts() {
        val allowed = allApps.count { it.allowWifi || it.allowMobile }
        val blocked = allApps.size - allowed
        animateCounter(txtBlockedCount, blocked)
        animateCounter(txtAllowedCount, allowed)
    }

    private fun animateCounter(view: TextView, target: Int) {
        val current = view.text.toString().toIntOrNull() ?: 0
        ValueAnimator.ofInt(current, target).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.text = (it.animatedValue as Int).toString() }
            start()
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { AppRepository.getInstalledApps(this@MainActivity) }
            val rules = withContext(Dispatchers.IO) { dao.getAll() }
            val ruleMap = rules.associateBy { it.packageName }

            apps.forEach { app ->
                ruleMap[app.packageName]?.let { rule ->
                    app.allowWifi = rule.allowWifi
                    app.allowMobile = rule.allowMobile
                }
            }

            allApps = apps
            applyFilter()
            updateCounts()

            // Replay layout animation
            findViewById<RecyclerView>(R.id.recyclerApps).scheduleLayoutAnimation()
        }
    }

    private fun applyFilter() {
        val filtered = allApps.filter { app ->
            val matchesTab = when (currentFilter) {
                Filter.ALL -> true
                Filter.USER -> !app.isSystem
                Filter.SYSTEM -> app.isSystem
            }
            val matchesSearch = searchQuery.isEmpty() ||
                app.name.lowercase().contains(searchQuery) ||
                app.packageName.lowercase().contains(searchQuery)
            matchesTab && matchesSearch
        }
        adapter.submitList(filtered)
    }

    private fun saveRule(app: AppInfo) {
        lifecycleScope.launch(Dispatchers.IO) {
            dao.upsert(AppRule(app.packageName, app.allowWifi, app.allowMobile))
        }
        updateCounts()
        val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("enabled", false)) {
            startFirewall()
        }
    }

    private fun startFirewall() {
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
}
