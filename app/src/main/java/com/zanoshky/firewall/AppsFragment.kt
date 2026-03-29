package com.zanoshky.firewall

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsFragment : Fragment() {

    private lateinit var adapter: AppAdapter
    private lateinit var dao: RuleDao
    private lateinit var trafficDao: TrafficDao
    @Volatile private var allApps: List<AppInfo> = emptyList()
    private var currentFilter = 0
    private var searchQuery = ""
    private val restartHandler = Handler(Looper.getMainLooper())
    private var restartPending: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_apps, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val db = RuleDatabase.get(requireContext())
        dao = db.ruleDao()
        trafficDao = db.trafficDao()

        adapter = AppAdapter(
            onToggleWifi = { app -> saveRule(app) },
            onToggleMobile = { app -> saveRule(app) }
        )

        view.findViewById<RecyclerView>(R.id.recyclerApps).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }

        view.findViewById<TabLayout>(R.id.tabFilter).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = tab.position
                applyFilter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        view.findViewById<EditText>(R.id.editSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.lowercase() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
    }

    private fun loadApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val apps = withContext(Dispatchers.IO) { AppRepository.getInstalledApps(ctx) }
            val rules = withContext(Dispatchers.IO) { dao.getAll() }
            val stats = withContext(Dispatchers.IO) { trafficDao.getAll() }
            val ruleMap = rules.associateBy { it.packageName }
            val statsMap = stats.associateBy { it.packageName }

            apps.forEach { app ->
                ruleMap[app.packageName]?.let { rule ->
                    app.allowWifi = rule.allowWifi
                    app.allowMobile = rule.allowMobile
                }
                statsMap[app.packageName]?.let { stat ->
                    app.blockedRequests = stat.blockedRequests
                    app.allowedRequests = stat.allowedRequests
                }
            }

            allApps = apps
            if (!isAdded) return@launch
            applyFilter()
            (activity as? MainActivity)?.updateCounts(allApps)
        }
    }

    private fun applyFilter() {
        if (!isAdded) return
        val snapshot = allApps // local ref for thread safety
        val filtered = snapshot.filter { app ->
            val matchesTab = when (currentFilter) {
                1 -> !app.isSystem
                2 -> app.isSystem
                else -> true
            }
            val matchesSearch = searchQuery.isEmpty() ||
                app.name.lowercase().contains(searchQuery) ||
                app.packageName.lowercase().contains(searchQuery)
            matchesTab && matchesSearch
        }
        adapter.submitList(filtered)
    }

    private fun saveRule(app: AppInfo) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            dao.upsert(AppRule(app.packageName, app.allowWifi, app.allowMobile))
        }
        (activity as? MainActivity)?.updateCounts(allApps)

        // Debounce VPN restart — wait 500ms so rapid toggles don't cause overlapping restarts
        restartPending?.let { restartHandler.removeCallbacks(it) }
        restartPending = Runnable {
            (activity as? MainActivity)?.let {
                val prefs = it.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("enabled", false)) {
                    it.startFirewall()
                }
            }
        }
        restartHandler.postDelayed(restartPending!!, 500)
    }

    override fun onDestroyView() {
        restartPending?.let { restartHandler.removeCallbacks(it) }
        super.onDestroyView()
    }
}
