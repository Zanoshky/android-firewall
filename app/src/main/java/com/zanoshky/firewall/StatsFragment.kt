package com.zanoshky.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsFragment : Fragment() {

    private lateinit var logDao: ConnectionLogDao
    private lateinit var trafficAdapter: AppTrafficAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var loadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logDao = RuleDatabase.get(requireContext()).connectionLogDao()

        trafficAdapter = AppTrafficAdapter()
        view.findViewById<RecyclerView>(R.id.recyclerAppTraffic).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trafficAdapter
        }

        loadStats()
        startAutoRefresh()
    }

    override fun onDestroyView() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        loadJob?.cancel()
        super.onDestroyView()
    }

    private fun startAutoRefresh() {
        refreshRunnable = object : Runnable {
            override fun run() {
                if (isAdded) loadStats()
                handler.postDelayed(this, 5000)
            }
        }
        handler.postDelayed(refreshRunnable!!, 5000)
    }

    private fun loadStats() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val c = context ?: return@launch

            val prefs = c.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
            val totalIn = prefs.getLong("total_bytes_in", 0) + FirewallVpnService.sessionBytesIn.get()
            val totalOut = prefs.getLong("total_bytes_out", 0) + FirewallVpnService.sessionBytesOut.get()

            val totalBlocked = withContext(Dispatchers.IO) { logDao.getBlockedCount() }
            val totalCount = withContext(Dispatchers.IO) { logDao.getCount() }
            val totalAllowed = totalCount - totalBlocked

            val recentLogs = withContext(Dispatchers.IO) { logDao.getRecent(5000) }

            if (!isAdded) return@launch
            val v = view ?: return@launch

            v.findViewById<TextView>(R.id.txtGlobalDownload).text = formatBytes(totalIn)
            v.findViewById<TextView>(R.id.txtGlobalUpload).text = formatBytes(totalOut)
            v.findViewById<TextView>(R.id.txtStatsBlocked).text = formatCount(totalBlocked)
            v.findViewById<TextView>(R.id.txtStatsAllowed).text = formatCount(totalAllowed)
            v.findViewById<TextView>(R.id.txtStatsTotal).text = formatCount(totalCount)

            setupTrafficList(recentLogs)
        }
    }

    private fun setupTrafficList(logs: List<ConnectionLog>) {
        val ctx = context ?: return
        val pm = ctx.packageManager

        data class Agg(var bytes: Long = 0, var count: Int = 0)
        val map = HashMap<String, Agg>()
        for (log in logs) {
            val agg = map.getOrPut(log.appName) { Agg() }
            agg.bytes += log.bytes
            agg.count++
        }

        val items = map.entries
            .sortedByDescending { it.value.bytes }
            .take(50)
            .map { (name, agg) ->
                val icon = try {
                    if (name.contains('.')) pm.getApplicationIcon(name) else null
                } catch (_: PackageManager.NameNotFoundException) { null }
                AppTrafficInfo(
                    packageName = name,
                    appName = "$name (${agg.count})",
                    icon = icon,
                    bytesIn = agg.bytes,
                    bytesOut = 0
                )
            }
        trafficAdapter.submitList(items)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}
