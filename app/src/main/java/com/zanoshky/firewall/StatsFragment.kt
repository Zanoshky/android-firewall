package com.zanoshky.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.PercentFormatter
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
    private var firstLoad = true

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

        firstLoad = true
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

            // Global traffic from prefs + live session (same source as header bar)
            val prefs = c.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
            val totalIn = prefs.getLong("total_bytes_in", 0) + FirewallVpnService.sessionBytesIn.get()
            val totalOut = prefs.getLong("total_bytes_out", 0) + FirewallVpnService.sessionBytesOut.get()

            // Blocked vs allowed from connection_logs (actual recorded data)
            val totalBlocked = withContext(Dispatchers.IO) { logDao.getBlockedCount() }
            val totalCount = withContext(Dispatchers.IO) { logDao.getCount() }
            val totalAllowed = totalCount - totalBlocked

            // Per-app breakdown from connection_logs
            val recentLogs = withContext(Dispatchers.IO) { logDao.getRecent(5000) }

            if (!isAdded) return@launch
            val v = view ?: return@launch
            v.findViewById<TextView>(R.id.txtGlobalDownload).text = formatBytes(totalIn)
            v.findViewById<TextView>(R.id.txtGlobalUpload).text = formatBytes(totalOut)

            val animate = firstLoad
            firstLoad = false

            setupPieChart(v.findViewById(R.id.chartBlockedAllowed), totalBlocked, totalAllowed, animate)
            setupBarChart(v.findViewById(R.id.chartTopApps), recentLogs, animate)
            setupTrafficList(recentLogs)
        }
    }

    private fun setupPieChart(chart: PieChart, blocked: Long, allowed: Long, animate: Boolean) {
        val ctx = context ?: return
        val entries = mutableListOf<PieEntry>()
        if (blocked > 0) entries.add(PieEntry(blocked.toFloat(), "Blocked"))
        if (allowed > 0) entries.add(PieEntry(allowed.toFloat(), "Allowed"))

        if (entries.isEmpty()) {
            entries.add(PieEntry(1f, "No data"))
        }

        val colors = mutableListOf<Int>()
        if (blocked > 0) colors.add(ContextCompat.getColor(ctx, R.color.accent_pink))
        if (allowed > 0) colors.add(ContextCompat.getColor(ctx, R.color.accent_cyan))
        if (colors.isEmpty()) colors.add(Color.LTGRAY)

        val dataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextSize = 12f
            valueTextColor = Color.WHITE
            valueFormatter = PercentFormatter(chart)
        }

        chart.apply {
            data = PieData(dataSet)
            setUsePercentValues(true)
            description.isEnabled = false
            legend.textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
            setEntryLabelColor(Color.WHITE)
            setHoleColor(Color.TRANSPARENT)
            setTransparentCircleColor(Color.TRANSPARENT)
            if (animate) animateY(600)
            notifyDataSetChanged()
            invalidate()
        }
    }

    private fun setupBarChart(chart: HorizontalBarChart, logs: List<ConnectionLog>, animate: Boolean) {
        val ctx = context ?: return

        // Aggregate bytes per app from logs
        data class AppBytes(var bytesIn: Long = 0, var bytesOut: Long = 0)
        val appMap = HashMap<String, AppBytes>()
        for (log in logs) {
            val key = log.appName.removePrefix("Tracker: ").removePrefix("DoH: ")
            val entry = appMap.getOrPut(key) { AppBytes() }
            entry.bytesIn += log.bytes
        }

        val sorted = appMap.entries
            .sortedByDescending { it.value.bytesIn + it.value.bytesOut }
            .take(10)

        if (sorted.isEmpty()) {
            chart.setNoDataText("No traffic data yet")
            chart.invalidate()
            return
        }

        val labels = mutableListOf<String>()
        val entries = mutableListOf<BarEntry>()

        sorted.forEachIndexed { i, (name, bytes) ->
            labels.add(if (name.length > 18) name.take(18) + "…" else name)
            entries.add(BarEntry(i.toFloat(), (bytes.bytesIn + bytes.bytesOut).toFloat()))
        }

        val dataSet = BarDataSet(entries, "Traffic (bytes)").apply {
            color = ContextCompat.getColor(ctx, R.color.chart_download)
            valueTextSize = 0f
        }

        chart.apply {
            data = BarData(dataSet).apply { barWidth = 0.7f }
            xAxis.apply {
                valueFormatter = IndexAxisValueFormatter(labels)
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
                textSize = 10f
                setDrawGridLines(false)
            }
            axisLeft.apply {
                textColor = ContextCompat.getColor(ctx, R.color.text_hint)
                textSize = 9f
            }
            axisRight.isEnabled = false
            description.isEnabled = false
            legend.textColor = ContextCompat.getColor(ctx, R.color.text_secondary)
            setFitBars(true)
            if (animate) animateY(600)
            notifyDataSetChanged()
            invalidate()
        }
    }

    private fun setupTrafficList(logs: List<ConnectionLog>) {
        val ctx = context ?: return
        val pm = ctx.packageManager

        // Aggregate per unique appName
        data class Agg(var bytes: Long = 0, var count: Int = 0)
        val map = HashMap<String, Agg>()
        for (log in logs) {
            val key = log.appName
            val agg = map.getOrPut(key) { Agg() }
            agg.bytes += log.bytes
            agg.count++
        }

        val items = map.entries
            .sortedByDescending { it.value.bytes }
            .take(50)
            .map { (name, agg) ->
                // Try to get icon from package name if it looks like one
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
}
