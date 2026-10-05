package com.zanoshky.firewall

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one app did, from the firewall's side: the tracker companies it reached,
 * whether the firewall stopped them, and the names it looked up most.
 *
 * It reads the kept log, which covers the last week, so the window is stated
 * on screen rather than implied. Apps set to Bypass never pass through the
 * firewall, and the sheet says so instead of showing an empty page as if the
 * app had been quiet.
 */
class AppDetailSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PACKAGE = "package"
        private const val ARG_NAME = "name"
        private const val TAG = "app_detail"
        private const val DOMAIN_ROWS = 25

        fun show(host: androidx.fragment.app.FragmentManager, packageName: String, name: String) {
            if (host.findFragmentByTag(TAG) != null) return
            AppDetailSheet().apply {
                arguments = bundleOf(ARG_PACKAGE to packageName, ARG_NAME to name)
            }.show(host, TAG)
        }
    }

    private val packageName: String get() = requireArguments().getString(ARG_PACKAGE).orEmpty()
    private val appName: String get() = requireArguments().getString(ARG_NAME).orEmpty()

    private var shareText: String? = null
    private var sharing = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_app_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.txtDetailName).text = appName
        view.findViewById<TextView>(R.id.txtDetailPackage).text = packageName
        val icon = view.findViewById<ImageView>(R.id.imgDetailIcon)
        try {
            icon.setImageDrawable(ctx.packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {
            icon.visibility = View.GONE
        }
        bindMode(view.findViewById(R.id.txtDetailMode), RuleStore.modeOf(packageName))
        view.findViewById<TextView>(R.id.btnDetailShare).setOnClickListener { share() }
        load(view)
    }

    override fun onStop() {
        // The lock overlay lives in the activity, under this sheet's own window.
        // Leaving the sheet up across a relock would show the app's activity to
        // whoever picks the phone up next, so it goes when the app does. The
        // share sheet is the one exit that is not leaving.
        val keep = sharing || activity?.isChangingConfigurations == true ||
            !AppLock.isEnabled(requireContext())
        sharing = false
        if (!keep) dismissAllowingStateLoss()
        super.onStop()
    }

    private fun bindMode(view: TextView, mode: Int) {
        val (label, colour) = when (mode) {
            AppMode.BYPASS -> R.string.mode_bypass to R.color.mode_bypass
            AppMode.FILTERED -> R.string.mode_filtered to R.color.accent
            else -> R.string.mode_blocked to R.color.danger
        }
        view.setText(label)
        view.setTextColor(requireContext().getColor(colour))
    }

    private fun load(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val since = System.currentTimeMillis() - RuleDatabase.KEEP_MS
            val dao = RuleDatabase.get(ctx).connectionLogDao()
            val (totals, rows, report) = withContext(Dispatchers.IO) {
                val totals = dao.getTotalsForApp(packageName, since)
                val rows = dao.getDomainsForApp(packageName, since, 2_000)
                Triple(totals, rows, TrackerCompanies.get(ctx).report(rows))
            }
            if (!isAdded) return@launch
            bind(view, totals, rows, report)
        }
    }

    private fun bind(view: View, totals: PeriodTotals, rows: List<DomainActivity>, report: TrackerReport) {
        val ctx = requireContext()
        val days = Format.daysSince(totals.since)
        val period = resources.getQuantityString(R.plurals.period_days, days, days)
        val companies = report.companies.size

        view.findViewById<TextView>(R.id.txtDetailLookups).text = Format.count(ctx, totals.total.toLong())
        view.findViewById<TextView>(R.id.txtDetailBlocked).text = Format.count(ctx, totals.blocked.toLong())
        view.findViewById<TextView>(R.id.txtDetailCompanies).text = companies.toString()

        val headline = view.findViewById<TextView>(R.id.txtDetailHeadline)
        headline.text = when {
            totals.total == 0 && RuleStore.modeOf(packageName) == AppMode.BYPASS ->
                getString(R.string.detail_headline_bypass)
            totals.total == 0 -> getString(R.string.detail_headline_quiet, period)
            companies == 0 && report.otherDomains == 0 ->
                getString(R.string.detail_headline_clean, period)
            else -> headlineFor(report, period)
        }

        bindCompanies(view, report)
        bindDomains(view, rows)

        view.findViewById<TextView>(R.id.txtDetailFooter).text =
            getString(R.string.detail_footer, period)

        shareText = if (companies > 0) {
            resources.getQuantityString(
                R.plurals.detail_share_text, companies,
                appName, companies, period, report.companies.take(5).joinToString(", ") { it.name }
            )
        } else null
        view.findViewById<View>(R.id.btnDetailShare).visibility =
            if (shareText != null) View.VISIBLE else View.GONE
    }

    /**
     * Reached and stopped are the two numbers that matter: the first says what
     * the app tried, the second whether the firewall is doing its job for it.
     */
    private fun headlineFor(report: TrackerReport, period: String): String {
        val companies = report.companies.size
        val hits = report.companies.sumOf { it.hits }
        val blocked = report.companies.sumOf { it.blocked }
        val reached = resources.getQuantityString(
            R.plurals.detail_headline_reached, companies, companies, period
        )
        val outcome = when {
            hits == 0 -> ""
            blocked >= hits -> getString(R.string.detail_headline_all_stopped)
            blocked == 0 -> getString(R.string.detail_headline_none_stopped)
            else -> getString(R.string.detail_headline_some_stopped, blocked * 100 / hits)
        }
        return if (outcome.isEmpty()) reached else "$reached $outcome"
    }

    private fun bindCompanies(view: View, report: TrackerReport) {
        val ctx = requireContext()
        val container = view.findViewById<LinearLayout>(R.id.containerCompanies)
        val title = view.findViewById<View>(R.id.txtDetailCompaniesTitle)
        container.removeAllViews()
        title.visibility = if (report.isEmpty) View.GONE else View.VISIBLE

        for (company in report.companies) {
            val row = layoutInflater.inflate(R.layout.item_company, container, false)
            row.findViewById<TextView>(R.id.txtCompanyName).text = company.name
            row.findViewById<TextView>(R.id.txtCompanyDomains).text =
                resources.getQuantityString(R.plurals.detail_company_domains, company.domains, company.domains)
            row.findViewById<TextView>(R.id.txtCompanyHits).text =
                resources.getQuantityString(R.plurals.app_lookups, company.hits, company.hits)

            val (status, colour) = when {
                company.blocked >= company.hits -> R.string.detail_status_stopped to R.color.accent
                company.blocked == 0 -> R.string.detail_status_reached to R.color.danger
                else -> R.string.detail_status_partly to R.color.accent_orange
            }
            row.findViewById<TextView>(R.id.txtCompanyStatus).apply {
                setText(status)
                setTextColor(ctx.getColor(colour))
            }
            container.addView(row)
        }

        val other = view.findViewById<TextView>(R.id.txtDetailOther)
        if (report.otherDomains > 0) {
            other.text = resources.getQuantityString(
                R.plurals.detail_other_trackers, report.otherDomains, report.otherDomains
            )
            other.visibility = View.VISIBLE
        } else {
            other.visibility = View.GONE
        }
    }

    private fun bindDomains(view: View, rows: List<DomainActivity>) {
        val ctx = requireContext()
        val container = view.findViewById<LinearLayout>(R.id.containerDomains)
        val visible = if (rows.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.txtDetailDomainsTitle).visibility = visible
        view.findViewById<View>(R.id.txtDetailDomainsHint).visibility = visible
        container.removeAllViews()

        for (entry in rows.take(DOMAIN_ROWS)) {
            val row = layoutInflater.inflate(R.layout.item_domain_count, container, false)
            row.findViewById<TextView>(R.id.txtDomainName).text = entry.domain
            row.findViewById<TextView>(R.id.txtDomainHits).apply {
                text = Format.count(ctx, entry.hits.toLong())
                setTextColor(
                    ctx.getColor(
                        when {
                            entry.blocked >= entry.hits -> R.color.danger
                            entry.blocked > 0 -> R.color.accent_orange
                            else -> R.color.text_secondary
                        }
                    )
                )
            }
            row.isClickable = true
            row.isFocusable = true
            row.setBackgroundResource(android.R.drawable.list_selector_background)
            row.setOnClickListener { showDomain(entry) }
            container.addView(row)
        }
    }

    /** The same block or allow choice the Activity log offers, from here. */
    private fun showDomain(entry: DomainActivity) {
        val ctx = context ?: return
        val company = TrackerCompanies.get(ctx).companyOf(entry.domain, packageName)
        val body = buildString {
            append(
                resources.getQuantityString(
                    R.plurals.detail_domain_body, entry.hits, entry.hits, entry.blocked
                )
            )
            if (company != null) {
                append("\n")
                append(getString(R.string.detail_domain_company, company.name))
            }
        }
        val stopped = entry.blocked >= entry.hits
        MaterialAlertDialogBuilder(ctx)
            .setTitle(entry.domain)
            .setMessage(body)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(if (stopped) R.string.action_always_allow else R.string.action_block_domain) { _, _ ->
                if (stopped) {
                    DomainRules.addAllowed(ctx, entry.domain)
                    toast(getString(R.string.toast_domain_allowed_now, entry.domain))
                } else {
                    DomainRules.addBlocked(ctx, entry.domain)
                    toast(getString(R.string.toast_domain_blocked_now, entry.domain))
                }
            }
            .show()
    }

    private fun share() {
        val text = shareText ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        // The share sheet backgrounds the activity; that is not the user leaving.
        AppLock.suppressNextRelock()
        sharing = true
        startActivity(Intent.createChooser(send, getString(R.string.detail_share)))
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}
