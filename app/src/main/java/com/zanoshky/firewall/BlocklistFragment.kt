package com.zanoshky.firewall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlocklistFragment : Fragment() {

    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var customAdapter: DomainAdapter
    private lateinit var whitelistAdapter: DomainAdapter
    private lateinit var txtStatus: TextView
    private lateinit var txtTrackersBlocked: TextView
    private lateinit var txtDomainsLoaded: TextView
    private lateinit var txtDohQueries: TextView
    private lateinit var txtDohStatus: TextView
    private lateinit var layoutDohProvider: LinearLayout
    private lateinit var btnCloudflare: TextView
    private lateinit var btnGoogle: TextView
    private lateinit var btnQuad9: TextView

    private val ctx get() = context // nullable safe access

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_blocklist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        txtStatus = view.findViewById(R.id.txtBlocklistStatus)
        txtTrackersBlocked = view.findViewById(R.id.txtTrackersBlocked)
        txtDomainsLoaded = view.findViewById(R.id.txtDomainsLoaded)
        txtDohQueries = view.findViewById(R.id.txtDohQueries)
        txtDohStatus = view.findViewById(R.id.txtDohStatus)
        layoutDohProvider = view.findViewById(R.id.layoutDohProvider)
        btnCloudflare = view.findViewById(R.id.btnProviderCloudflare)
        btnGoogle = view.findViewById(R.id.btnProviderGoogle)
        btnQuad9 = view.findViewById(R.id.btnProviderQuad9)

        // Tracker blocking toggle - async load
        val switchBlocklist = view.findViewById<MaterialSwitch>(R.id.switchBlocklist)
        switchBlocklist.isChecked = BlocklistManager.isEnabled
        switchBlocklist.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                val c = ctx ?: return@launch
                BlocklistManager.setEnabledAsync(c, isChecked)
                withContext(Dispatchers.Main) { updateStatus() }
            }
        }

        // DoH toggle
        val switchDoh = view.findViewById<MaterialSwitch>(R.id.switchDoh)
        switchDoh.isChecked = DohResolver.isEnabled
        layoutDohProvider.visibility = if (DohResolver.isEnabled) View.VISIBLE else View.GONE
        switchDoh.setOnCheckedChangeListener { _, isChecked ->
            val c = ctx ?: return@setOnCheckedChangeListener
            DohResolver.setEnabled(c, isChecked)
            layoutDohProvider.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateDohStatus()
        }

        // DoH provider buttons
        updateProviderButtons()
        btnCloudflare.setOnClickListener { selectProvider("cloudflare") }
        btnGoogle.setOnClickListener { selectProvider("google") }
        btnQuad9.setOnClickListener { selectProvider("quad9") }

        // Sources list
        sourceAdapter = SourceAdapter { source, isDownloaded ->
            if (isDownloaded) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val c = ctx ?: return@launch
                    BlocklistManager.deleteSource(c, source.id)
                    withContext(Dispatchers.Main) {
                        if (isAdded) { refreshSources(); updateStatus() }
                    }
                }
            } else {
                downloadSource(source)
            }
        }
        view.findViewById<RecyclerView>(R.id.recyclerSources).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sourceAdapter
        }

        // Custom domains
        customAdapter = DomainAdapter { domain ->
            val c = ctx ?: return@DomainAdapter
            BlocklistManager.removeCustomDomain(c, domain)
            refreshCustomDomains()
            updateStatus()
        }
        view.findViewById<RecyclerView>(R.id.recyclerCustomDomains).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = customAdapter
        }

        val editAdd = view.findViewById<EditText>(R.id.editAddDomain)
        view.findViewById<TextView>(R.id.btnAddDomain).setOnClickListener {
            val c = ctx ?: return@setOnClickListener
            val domain = editAdd.text.toString().trim()
            if (domain.isNotEmpty() && domain.contains('.')) {
                BlocklistManager.addCustomDomain(c, domain)
                editAdd.text.clear()
                refreshCustomDomains()
                updateStatus()
            } else {
                Toast.makeText(c, "Enter a valid domain", Toast.LENGTH_SHORT).show()
            }
        }

        // Whitelist
        whitelistAdapter = DomainAdapter { domain ->
            viewLifecycleOwner.lifecycleScope.launch {
                val c = ctx ?: return@launch
                BlocklistManager.removeWhitelistedDomain(c, domain)
                withContext(Dispatchers.Main) {
                    if (isAdded) { refreshWhitelist(); updateStatus() }
                }
            }
        }
        view.findViewById<RecyclerView>(R.id.recyclerWhitelist).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = whitelistAdapter
        }

        val editWhitelist = view.findViewById<EditText>(R.id.editAddWhitelist)
        view.findViewById<TextView>(R.id.btnAddWhitelist).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val c = ctx ?: return@launch
                val domain = editWhitelist.text.toString().trim()
                if (domain.isNotEmpty() && domain.contains('.')) {
                    BlocklistManager.addWhitelistedDomain(c, domain)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            editWhitelist.text.clear()
                            refreshWhitelist()
                            updateStatus()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(c, "Enter a valid domain", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        refreshSources()
        refreshCustomDomains()
        refreshWhitelist()
        updateStatus()
        updateDohStatus()
    }

    private fun selectProvider(id: String) {
        val c = ctx ?: return
        DohResolver.setProvider(c, id)
        updateProviderButtons()
        updateDohStatus()
    }

    private fun updateProviderButtons() {
        val c = ctx ?: return
        val active = DohResolver.provider
        val onBg = R.drawable.bg_toggle_on
        val offBg = R.drawable.bg_toggle_off
        val onColor = ContextCompat.getColor(c, R.color.accent_blue)
        val offColor = ContextCompat.getColor(c, R.color.text_secondary)

        btnCloudflare.setBackgroundResource(if (active == "cloudflare") onBg else offBg)
        btnCloudflare.setTextColor(if (active == "cloudflare") onColor else offColor)
        btnGoogle.setBackgroundResource(if (active == "google") onBg else offBg)
        btnGoogle.setTextColor(if (active == "google") onColor else offColor)
        btnQuad9.setBackgroundResource(if (active == "quad9") onBg else offBg)
        btnQuad9.setTextColor(if (active == "quad9") onColor else offColor)
    }

    private fun updateDohStatus() {
        if (!isAdded) return
        val c = ctx ?: return
        val providerName = DohResolver.provider.replaceFirstChar { it.uppercase() }
        txtDohStatus.text = if (DohResolver.isEnabled) "Active - $providerName" else "Off - $providerName"

        val prefs = c.getSharedPreferences("firewall_prefs", 0)
        val total = prefs.getLong("doh_queries", 0) + FirewallVpnService.dohQueriesSession.get()
        txtDohQueries.text = total.toString()
    }

    private fun updateStatus() {
        if (!isAdded) return
        val c = ctx ?: return
        val count = BlocklistManager.getActiveCount()
        val enabled = BlocklistManager.isEnabled
        val loading = BlocklistManager.isLoading
        txtStatus.text = when {
            loading -> "Loading domains..."
            enabled -> "Active - $count domains loaded"
            else -> "Off - $count domains loaded"
        }
        txtDomainsLoaded.text = count.toString()

        val prefs = c.getSharedPreferences("firewall_prefs", 0)
        val total = prefs.getLong("trackers_blocked", 0) + FirewallVpnService.trackersBlockedSession.get()
        txtTrackersBlocked.text = total.toString()
        updateDohStatus()
    }

    private fun refreshSources() {
        if (!isAdded) return
        val c = ctx ?: return
        val sources = BlocklistManager.getSources(c)
        viewLifecycleOwner.lifecycleScope.launch {
            val counts = mutableMapOf<String, Int>()
            val items = sources.map { source ->
                val downloaded = BlocklistManager.isSourceDownloaded(c, source.id)
                if (downloaded) counts[source.id] = BlocklistManager.getDownloadedSourceCount(c, source.id)
                Pair(source, downloaded)
            }
            withContext(Dispatchers.Main) {
                if (isAdded) sourceAdapter.submitList(items, counts)
            }
        }
    }

    private fun refreshCustomDomains() {
        if (!isAdded) return
        val c = ctx ?: return
        customAdapter.submitList(BlocklistManager.getCustomDomains(c))
    }

    private fun refreshWhitelist() {
        if (!isAdded) return
        val c = ctx ?: return
        whitelistAdapter.submitList(BlocklistManager.getWhitelistedDomains(c))
    }

    private fun downloadSource(source: BlocklistSource) {
        val c = ctx ?: return
        Toast.makeText(c, "Downloading ${source.name}...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = BlocklistManager.downloadSource(c, source)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess { count ->
                    Toast.makeText(ctx, "Downloaded $count domains", Toast.LENGTH_SHORT).show()
                    refreshSources()
                    updateStatus()
                }
                result.onFailure { e ->
                    Toast.makeText(ctx, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
