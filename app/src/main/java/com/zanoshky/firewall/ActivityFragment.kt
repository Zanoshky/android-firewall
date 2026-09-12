package com.zanoshky.firewall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the firewall has been doing, in one place.
 *
 * Logs and stats used to be two tabs, which meant the numbers and the entries
 * that produced them were never on screen together. They are one list now: the
 * summary is the first row, the entries follow, and the filter stays pinned
 * above both so it still reaches the list after you have scrolled past the
 * charts.
 */
class ActivityFragment : Fragment() {

    private lateinit var logAdapter: LogAdapter
    private lateinit var headerAdapter: StatsHeaderAdapter
    private lateinit var logDao: ConnectionLogDao
    private lateinit var chips: List<TextView>

    private var filterQuery = ""
    private var statusFilter = 0 // 0 all, 1 blocked, 2 allowed, 3 trackers
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var searchDebounce: Runnable? = null
    private var loadJob: Job? = null
    private var installedCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_activity, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logDao = RuleDatabase.get(requireContext()).connectionLogDao()
        logAdapter = LogAdapter { log -> showEntry(log) }
        headerAdapter = StatsHeaderAdapter()

        view.findViewById<RecyclerView>(R.id.recyclerActivity).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headerAdapter, logAdapter)
        }

        chips = listOf(
            view.findViewById(R.id.chipAll),
            view.findViewById(R.id.chipBlocked),
            view.findViewById(R.id.chipAllowed),
            view.findViewById(R.id.chipTrackers)
        )
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                if (statusFilter != index) {
                    statusFilter = index
                    updateChips()
                    load()
                }
            }
        }
        updateChips()

        view.findViewById<TextView>(R.id.btnClearLogs).setOnClickListener { confirmReset() }
        view.findViewById<TextView>(R.id.btnExportLogs).setOnClickListener { exportCsv() }

        view.findViewById<EditText>(R.id.editFilterLogs)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    filterQuery = s?.toString()?.trim() ?: ""
                    searchDebounce?.let { handler.removeCallbacks(it) }
                    searchDebounce = Runnable { load() }
                    handler.postDelayed(searchDebounce!!, 250)
                }
                override fun afterTextChanged(s: Editable?) {}
            })

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
        startAutoRefresh()
    }

    override fun onPause() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        refreshRunnable = null
        super.onPause()
    }

    override fun onDestroyView() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        searchDebounce?.let { handler.removeCallbacks(it) }
        loadJob?.cancel()
        super.onDestroyView()
    }

    private fun startAutoRefresh() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        refreshRunnable = object : Runnable {
            override fun run() {
                if (isAdded) load()
                handler.postDelayed(this, 4000)
            }
        }
        handler.postDelayed(refreshRunnable!!, 4000)
    }

    private fun updateChips() {
        val ctx = context ?: return
        chips.forEachIndexed { index, chip ->
            chip.setBackgroundResource(
                if (index == statusFilter) R.drawable.bg_chip_active else R.drawable.bg_chip
            )
            chip.setTextColor(
                ctx.getColor(if (index == statusFilter) R.color.accent else R.color.text_secondary)
            )
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val status = statusFilter
            val query = filterQuery

            if (installedCount == 0) {
                installedCount = withContext(Dispatchers.IO) {
                    try {
                        ctx.packageManager.getInstalledApplications(0).count { it.uid > 1000 }
                    } catch (_: Exception) { 0 }
                }
            }

            val logs = withContext(Dispatchers.IO) { logDao.getFiltered(status, query, 300) }
            val hourly = withContext(Dispatchers.IO) {
                logDao.getHourly(System.currentTimeMillis() - 24 * 3_600_000L)
            }
            val topBlocked = withContext(Dispatchers.IO) { logDao.getTopBlockedDomains(5) }

            if (!isAdded) return@launch

            val modes = RuleStore.snapshot()
            val filtered = modes.count { it.value == AppMode.FILTERED }
            val bypass = modes.count { it.value == AppMode.BYPASS }
            val blocked = (installedCount - filtered - bypass).coerceAtLeast(0)

            headerAdapter.submit(
                ActivitySummary(
                    totals = Stats.totals(ctx),
                    hourly = hourly,
                    topBlocked = topBlocked,
                    appsFiltered = filtered,
                    appsBypass = bypass,
                    appsBlocked = blocked,
                    trackerDomains = BlocklistManager.getActiveCount(),
                    trackerBlockingOn = BlocklistManager.isEnabled,
                    blockedRules = DomainRules.blockedCount(),
                    allowedRules = DomainRules.allowedCount(),
                    dohOn = DohResolver.isEnabled,
                    dohProvider = DohResolver.providerLabel(),
                    privateDnsActive = FirewallVpnService.privateDnsActive
                )
            )
            logAdapter.submitList(logs)
        }
    }

    private fun showEntry(log: ConnectionLog) {
        val ctx = context ?: return
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(log.timestamp))
        val body = buildString {
            appendLine("App: ${log.appName}")
            if (log.packageName.isNotEmpty()) appendLine("Package: ${log.packageName}")
            if (log.domain.isNotEmpty()) appendLine("Domain: ${log.domain}")
            appendLine("Address: ${log.destIp}:${log.destPort}")
            appendLine("Kind: ${log.protocol}")
            appendLine("Result: ${BlockReason.label(log.blockReason)}")
            append("When: $when_")
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (log.domain.isNotEmpty()) log.domain else log.appName)
            .setMessage(body)
            .setNegativeButton("Close", null)

        // A log entry is where you notice a domain you want a rule for, so the
        // rule can be made right here instead of retyping it in the Domains tab.
        if (log.domain.isNotEmpty()) {
            if (log.blockReason == BlockReason.ALLOWED) {
                builder.setPositiveButton("Block this domain") { _, _ ->
                    DomainRules.addBlocked(ctx, log.domain)
                    toast("${log.domain} is blocked from now on")
                }
            } else {
                builder.setPositiveButton("Always allow") { _, _ ->
                    DomainRules.addAllowed(ctx, log.domain)
                    toast("${log.domain} is allowed from now on")
                }
            }
        }
        builder.show()
    }

    private fun confirmReset() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Reset activity?")
            .setMessage("Clears the entries below and sets every counter back to zero. Your app modes, domain rules and blocklists are not touched.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { logDao.deleteAll() }
                    Stats.reset(ctx)
                    if (isAdded) load()
                }
            }
            .show()
    }

    private fun exportCsv() {
        val appCtx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                logDao.getFiltered(statusFilter, filterQuery, 10000)
            }
            if (!isAdded) return@launch
            if (logs.isEmpty()) {
                toast("Nothing to export")
                return@launch
            }

            val file = withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(appCtx.cacheDir, "exports").apply { mkdirs() }
                val out = File(dir, "firewall-activity-$stamp.csv")
                val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                out.bufferedWriter().use { writer ->
                    writer.appendLine("timestamp,app,package,domain,address,port,kind,result")
                    for (log in logs) {
                        writer.appendLine(
                            listOf(
                                iso.format(Date(log.timestamp)),
                                csv(log.appName),
                                csv(log.packageName),
                                csv(log.domain),
                                log.destIp,
                                log.destPort.toString(),
                                log.protocol,
                                BlockReason.label(log.blockReason)
                            ).joinToString(",")
                        )
                    }
                }
                out
            }

            if (!isAdded) return@launch
            val uri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The share sheet backgrounds the activity; that is not the user leaving.
            AppLock.suppressNextRelock()
            startActivity(Intent.createChooser(send, "Export ${logs.size} entries"))
        }
    }

    private fun csv(field: String): String =
        if (field.contains(',') || field.contains('"'))
            "\"${field.replace("\"", "\"\"")}\"" else field

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}

/** Everything the summary row shows, gathered in one pass. */
data class ActivitySummary(
    val totals: Stats.Totals,
    val hourly: List<HourBucket>,
    val topBlocked: List<DomainCount>,
    val appsFiltered: Int,
    val appsBypass: Int,
    val appsBlocked: Int,
    val trackerDomains: Int,
    val trackerBlockingOn: Boolean,
    val blockedRules: Int,
    val allowedRules: Int,
    val dohOn: Boolean,
    val dohProvider: String,
    val privateDnsActive: Boolean
)

/**
 * The summary, as the single first row of the activity list. Being an adapter
 * rather than a view above the list is what lets the log rows keep recycling.
 */
class StatsHeaderAdapter : RecyclerView.Adapter<StatsHeaderAdapter.ViewHolder>() {

    private var summary: ActivitySummary? = null

    fun submit(summary: ActivitySummary) {
        this.summary = summary
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_activity_stats, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        summary?.let { holder.bind(it) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ctx = view.context
        private val txtLookups: TextView = view.findViewById(R.id.txtStatLookups)
        private val txtBlocked: TextView = view.findViewById(R.id.txtStatBlocked)
        private val txtEncrypted: TextView = view.findViewById(R.id.txtStatEncrypted)
        private val txtTrackers: TextView = view.findViewById(R.id.txtStatTrackers)
        private val txtRules: TextView = view.findViewById(R.id.txtStatRules)
        private val txtApps: TextView = view.findViewById(R.id.txtStatApps)
        private val txtShare: TextView = view.findViewById(R.id.txtBlockedShare)
        private val meterBlocked: View = view.findViewById(R.id.meterBlocked)
        private val meterRest: View = view.findViewById(R.id.meterRest)
        private val chart: BarChartView = view.findViewById(R.id.chartHourly)
        private val txtAppsFiltered: TextView = view.findViewById(R.id.txtAppsFiltered)
        private val txtAppsBypass: TextView = view.findViewById(R.id.txtAppsBypass)
        private val txtAppsBlocked: TextView = view.findViewById(R.id.txtAppsBlocked)
        private val txtProtected: TextView = view.findViewById(R.id.txtProtectedTime)
        private val containerTop: LinearLayout = view.findViewById(R.id.containerTopBlocked)
        private val txtNoBlocked: TextView = view.findViewById(R.id.txtNoBlockedYet)
        private val txtLists: TextView = view.findViewById(R.id.txtListsSummary)
        private val txtPrivateDns: TextView = view.findViewById(R.id.txtPrivateDnsWarning)

        fun bind(s: ActivitySummary) {
            val t = s.totals
            txtLookups.text = count(t.queries)
            txtBlocked.text = count(t.blocked)
            txtEncrypted.text = "${t.encryptedShare}%"
            txtTrackers.text = count(t.trackers)
            txtRules.text = count(t.domains)
            txtApps.text = count(t.appBlocked)

            txtShare.text = "${t.blockedShare}%"
            setWeight(meterBlocked, t.blockedShare.toFloat())
            setWeight(meterRest, (100 - t.blockedShare).toFloat())

            chart.setBuckets(s.hourly)

            txtAppsFiltered.text = s.appsFiltered.toString()
            txtAppsBypass.text = s.appsBypass.toString()
            txtAppsBlocked.text = s.appsBlocked.toString()
            txtProtected.text = duration(t.protectedMs)

            containerTop.removeAllViews()
            txtNoBlocked.visibility = if (s.topBlocked.isEmpty()) View.VISIBLE else View.GONE
            for (entry in s.topBlocked) {
                containerTop.addView(domainRow(entry))
            }

            txtLists.text = buildString {
                append(
                    if (s.trackerBlockingOn) "Tracker lists on, ${count(s.trackerDomains.toLong())} names loaded"
                    else "Tracker lists off"
                )
                append(". ")
                append("${s.blockedRules} blocked and ${s.allowedRules} allowed by your own rules. ")
                append(if (s.dohOn) "Lookups encrypted through ${s.dohProvider}." else "Lookups not encrypted.")
            }

            txtPrivateDns.visibility = if (s.privateDnsActive) View.VISIBLE else View.GONE
        }

        private fun domainRow(entry: DomainCount): View {
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_domain_count, containerTop, false)
            row.findViewById<TextView>(R.id.txtDomainName).text = entry.domain
            row.findViewById<TextView>(R.id.txtDomainHits).text = entry.hits.toString()
            return row
        }

        private fun setWeight(view: View, weight: Float) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.weight = weight
            view.layoutParams = params
        }

        private fun count(n: Long): String = when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 10_000 -> String.format(Locale.US, "%.0fK", n / 1_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
            else -> n.toString()
        }

        private fun duration(ms: Long): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> "${days}d ${hours % 24}h"
                hours > 0 -> "${hours}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m"
                else -> "${seconds}s"
            }
        }
    }
}
