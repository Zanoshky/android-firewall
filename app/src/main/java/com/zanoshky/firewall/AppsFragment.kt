package com.zanoshky.firewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    @Volatile private var allApps: List<AppInfo> = emptyList()
    private var currentFilter = 0
    private var searchQuery = ""

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isAdded) loadApps()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_apps, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AppAdapter { app, mode ->
            // Lands in memory here and applies to the app's very next lookup; the
            // database write and, if the app moved in or out of Bypass, the tunnel
            // rebuild both follow on a scope that outlives this screen.
            context?.let { RuleStore.setMode(it, app.packageName, mode) }
            (activity as? MainActivity)?.updateCounts(allApps)
        }

        view.findViewById<RecyclerView>(R.id.recyclerApps).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }

        view.findViewById<TabLayout>(R.id.tabFilter)
            .addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    currentFilter = tab.position
                    applyFilter()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

        view.findViewById<EditText>(R.id.editSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchQuery = s?.toString()?.lowercase() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<TextView>(R.id.btnBypassAll).setOnClickListener { bulk(AppMode.BYPASS) }
        view.findViewById<TextView>(R.id.btnFilterAll).setOnClickListener { bulk(AppMode.FILTERED) }
        view.findViewById<TextView>(R.id.btnBlockAll).setOnClickListener { bulk(AppMode.BLOCKED) }

        ContextCompat.registerReceiver(
            requireContext(),
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        // Reload every time, so installs and removals made elsewhere show up.
        loadApps()
    }

    private fun loadApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            RuleStore.reload(ctx)
            val apps = withContext(Dispatchers.IO) { AppRepository.getInstalledApps(ctx) }
            val perApp = withContext(Dispatchers.IO) {
                RuleDatabase.get(ctx).connectionLogDao().getActivityByApp(400)
            }

            RuleStore.purgeMissing(ctx, apps.mapTo(HashSet()) { it.packageName })

            val byPackage = perApp.associateBy { it.packageName }
            apps.forEach { app ->
                byPackage[app.packageName]?.let {
                    app.lookups = it.total
                    app.blocked = it.blocked
                }
            }

            allApps = apps
            if (!isAdded) return@launch
            applyFilter()
            (activity as? MainActivity)?.updateCounts(allApps)
        }
    }

    /** Apply one mode to every app the current filter and search are showing. */
    private fun bulk(mode: Int) {
        val visible = getFilteredApps()
        if (visible.isEmpty()) return
        visible.forEach { it.mode = mode }
        context?.let { RuleStore.setModes(it, visible.map { app -> app.packageName }, mode) }
        applyFilter()
        adapter.refreshAll()
        (activity as? MainActivity)?.updateCounts(allApps)
    }

    private fun getFilteredApps(): List<AppInfo> {
        val snapshot = allApps
        return snapshot.filter { app ->
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
    }

    private fun applyFilter() {
        if (!isAdded) return
        adapter.submitList(getFilteredApps())
    }

    override fun onDestroyView() {
        try { requireContext().unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        super.onDestroyView()
    }
}
