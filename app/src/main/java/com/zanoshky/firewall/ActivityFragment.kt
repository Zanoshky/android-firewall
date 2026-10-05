package com.zanoshky.firewall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        headerAdapter = StatsHeaderAdapter { showPrivateDns() }

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
                        ctx.packageManager.getInstalledApplications(0)
                            .count { it.uid > 1000 && it.packageName != ctx.packageName }
                    } catch (_: Exception) { 0 }
                }
            }

            val logs = withContext(Dispatchers.IO) { logDao.getFiltered(status, query, 300) }
            val hourly = withContext(Dispatchers.IO) {
                logDao.getHourly(System.currentTimeMillis() - 24 * 3_600_000L)
            }
            val topBlocked = withContext(Dispatchers.IO) { logDao.getTopBlockedDomains(5) }
            val (strictHost, managedHost) = withContext(Dispatchers.IO) {
                if (FirewallVpnService.isRunning) {
                    PrivateDns.strictHost(ctx) to PrivateDns.takenOverHost(ctx)
                } else {
                    null to null
                }
            }

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
                    privateDnsHost = strictHost,
                    privateDnsManagedHost = managedHost
                )
            )
            logAdapter.submitList(logs)
        }
    }

    /**
     * Strict Private DNS is the one setting that takes every lookup away from
     * the firewall. Fix it in place when we are allowed to write the setting,
     * otherwise say how, and open the screen where it lives.
     */
    private fun showPrivateDns() {
        val ctx = context ?: return
        val host = PrivateDns.strictHost(ctx) ?: run { load(); return }
        val name = PrivateDnsText.host(ctx, host)
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.private_dns_title)
            .setNegativeButton(R.string.action_close, null)

        if (PrivateDns.canManage(ctx)) {
            builder.setMessage(getString(R.string.private_dns_fix_body, name))
                .setPositiveButton(R.string.private_dns_fix) { _, _ ->
                    val fixed = PrivateDns.takeOver(ctx)
                    toast(getString(if (fixed) R.string.toast_private_dns_fixed else R.string.toast_private_dns_failed))
                    load()
                }
        } else {
            val command = PrivateDns.grantCommand(ctx)
            builder.setMessage(getString(R.string.private_dns_manual_body, name, command))
                .setPositiveButton(R.string.private_dns_open_settings) { _, _ -> openNetworkSettings() }
                .setNeutralButton(R.string.private_dns_copy_command) { _, _ ->
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("adb", command))
                    toast(getString(R.string.toast_command_copied))
                }
        }
        builder.show()
    }

    /** Private DNS lives under Network and internet; there is no public intent for the dialog itself. */
    private fun openNetworkSettings() {
        for (action in listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {}
        }
    }

    private fun showEntry(log: ConnectionLog) {
        val ctx = context ?: return
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(log.timestamp))
        val body = buildString {
            appendLine(getString(R.string.log_detail_app, log.appName))
            if (log.packageName.isNotEmpty()) {
                appendLine(getString(R.string.log_detail_package, log.packageName))
            }
            if (log.domain.isNotEmpty()) {
                appendLine(getString(R.string.log_detail_domain, log.domain))
            }
            appendLine(getString(R.string.log_detail_address, log.destIp, log.destPort))
            appendLine(getString(R.string.log_detail_kind, log.protocol))
            appendLine(
                getString(
                    R.string.log_detail_result,
                    getString(BlockReason.labelRes(log.blockReason))
                )
            )
            append(getString(R.string.log_detail_when, when_))
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (log.domain.isNotEmpty()) log.domain else log.appName)
            .setMessage(body)
            .setNegativeButton(R.string.action_close, null)

        // A log entry is where you notice a domain you want a rule for, so the
        // rule can be made right here instead of retyping it in the Domains tab.
        if (log.domain.isNotEmpty()) {
            if (log.blockReason == BlockReason.ALLOWED) {
                builder.setPositiveButton(R.string.action_block_domain) { _, _ ->
                    DomainRules.addBlocked(ctx, log.domain)
                    toast(getString(R.string.toast_domain_blocked_now, log.domain))
                }
            } else {
                builder.setPositiveButton(R.string.action_always_allow) { _, _ ->
                    DomainRules.addAllowed(ctx, log.domain)
                    toast(getString(R.string.toast_domain_allowed_now, log.domain))
                }
            }
        }
        builder.show()
    }

    private fun confirmReset() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.reset_activity_title)
            .setMessage(R.string.reset_activity_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset) { _, _ ->
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
                toast(getString(R.string.toast_nothing_to_export))
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
            startActivity(
                Intent.createChooser(
                    send,
                    resources.getQuantityString(
                        R.plurals.export_chooser_title, logs.size, logs.size
                    )
                )
            )
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
    /** Strict Private DNS that is taking lookups away from the tunnel, or null. */
    val privateDnsHost: String?,
    /** The strict provider the firewall has paused while it runs, or null. */
    val privateDnsManagedHost: String?
)

/**
 * The summary, as the single first row of the activity list. Being an adapter
 * rather than a view above the list is what lets the log rows keep recycling.
 */
class StatsHeaderAdapter(
    private val onPrivateDnsClick: () -> Unit
) : RecyclerView.Adapter<StatsHeaderAdapter.ViewHolder>() {

    private var summary: ActivitySummary? = null

    fun submit(summary: ActivitySummary) {
        this.summary = summary
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_activity_stats, parent, false)
        return ViewHolder(view).also { holder ->
            holder.txtPrivateDns.setOnClickListener { onPrivateDnsClick() }
        }
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
        val txtPrivateDns: TextView = view.findViewById(R.id.txtPrivateDnsWarning)

        fun bind(s: ActivitySummary) {
            val t = s.totals
            txtLookups.text = count(t.queries)
            txtBlocked.text = count(t.blocked)
            txtEncrypted.text = ctx.getString(R.string.percent_value, t.encryptedShare)
            txtTrackers.text = count(t.trackers)
            txtRules.text = count(t.domains)
            txtApps.text = count(t.appBlocked)

            txtShare.text = ctx.getString(R.string.percent_value, t.blockedShare)
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

            val lists = if (s.trackerBlockingOn) {
                ctx.getString(R.string.summary_lists_on, count(s.trackerDomains.toLong()))
            } else {
                ctx.getString(R.string.summary_lists_off)
            }
            val rules = ctx.getString(R.string.summary_rules, s.blockedRules, s.allowedRules)
            val doh = if (s.dohOn) {
                ctx.getString(R.string.summary_doh_on, s.dohProvider)
            } else {
                ctx.getString(R.string.summary_doh_off)
            }
            txtLists.text = ctx.getString(R.string.summary_lists_format, lists, rules, doh)

            bindPrivateDns(s)
        }

        private fun bindPrivateDns(s: ActivitySummary) {
            val strict = s.privateDnsHost
            val managed = s.privateDnsManagedHost
            when {
                strict != null -> {
                    txtPrivateDns.text = ctx.getString(
                        R.string.private_dns_warning, PrivateDnsText.host(ctx, strict)
                    )
                    txtPrivateDns.setTextColor(ctx.getColor(R.color.accent_orange))
                    txtPrivateDns.isClickable = true
                    txtPrivateDns.visibility = View.VISIBLE
                }
                managed != null -> {
                    txtPrivateDns.text = ctx.getString(
                        R.string.private_dns_managed, PrivateDnsText.host(ctx, managed)
                    )
                    txtPrivateDns.setTextColor(ctx.getColor(R.color.text_secondary))
                    txtPrivateDns.isClickable = false
                    txtPrivateDns.visibility = View.VISIBLE
                }
                else -> txtPrivateDns.visibility = View.GONE
            }
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

        /**
         * The number itself follows the reader's own language, so a Russian
         * phone gets 1,2 rather than 1.2; the K and M suffixes are strings.
         */
        private fun count(n: Long): String {
            val locale = Locale.getDefault()
            return when {
                n >= 1_000_000 -> ctx.getString(
                    R.string.count_millions, String.format(locale, "%.1f", n / 1_000_000.0)
                )
                n >= 10_000 -> ctx.getString(
                    R.string.count_thousands, String.format(locale, "%.0f", n / 1_000.0)
                )
                n >= 1_000 -> ctx.getString(
                    R.string.count_thousands, String.format(locale, "%.1f", n / 1_000.0)
                )
                else -> n.toString()
            }
        }

        private fun duration(ms: Long): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 ->
                    ctx.getString(R.string.duration_days, days.toInt(), (hours % 24).toInt())
                hours > 0 ->
                    ctx.getString(R.string.duration_hours, hours.toInt(), (minutes % 60).toInt())
                minutes > 0 -> ctx.getString(R.string.duration_minutes_only, minutes.toInt())
                else -> ctx.getString(R.string.duration_seconds, seconds.toInt())
            }
        }
    }
}

/** How a Private DNS provider is named on screen. */
private object PrivateDnsText {
    fun host(context: Context, host: String): String =
        host.ifEmpty { context.getString(R.string.private_dns_unknown_host) }
}
