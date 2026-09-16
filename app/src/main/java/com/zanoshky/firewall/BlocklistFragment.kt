package com.zanoshky.firewall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tracker lists and encrypted lookups.
 *
 * Both switches take effect on the next lookup: the lists are swapped in memory
 * and the resolver reads its own flag, so nothing here waits for the tunnel to
 * be rebuilt.
 */
class BlocklistFragment : Fragment() {

    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var txtStatus: TextView
    private lateinit var txtTrackersBlocked: TextView
    private lateinit var txtDomainsLoaded: TextView
    private lateinit var txtDohQueries: TextView
    private lateinit var txtDohStatus: TextView
    private lateinit var layoutDohProvider: LinearLayout
    private lateinit var providerButtons: Map<String, TextView>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_blocklist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        txtStatus = view.findViewById(R.id.txtBlocklistStatus)
        txtTrackersBlocked = view.findViewById(R.id.txtTrackersBlocked)
        txtDomainsLoaded = view.findViewById(R.id.txtDomainsLoaded)
        txtDohQueries = view.findViewById(R.id.txtDohQueries)
        txtDohStatus = view.findViewById(R.id.txtDohStatus)
        layoutDohProvider = view.findViewById(R.id.layoutDohProvider)

        providerButtons = mapOf(
            "cloudflare" to view.findViewById(R.id.btnProviderCloudflare),
            "google" to view.findViewById(R.id.btnProviderGoogle),
            "quad9" to view.findViewById(R.id.btnProviderQuad9)
        )
        providerButtons.forEach { (id, button) ->
            button.setOnClickListener {
                val ctx = context ?: return@setOnClickListener
                DohResolver.setProvider(ctx, id)
                updateProviderButtons()
                updateDohStatus()
            }
        }

        val switchBlocklist = view.findViewById<MaterialSwitch>(R.id.switchBlocklist)
        switchBlocklist.isChecked = BlocklistManager.isEnabled
        switchBlocklist.setOnCheckedChangeListener { _, isChecked ->
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = context ?: return@launch
                BlocklistManager.setEnabledAsync(ctx, isChecked)
                withContext(Dispatchers.Main) { updateStatus() }
            }
        }

        val switchDoh = view.findViewById<MaterialSwitch>(R.id.switchDoh)
        switchDoh.isChecked = DohResolver.isEnabled
        layoutDohProvider.visibility = if (DohResolver.isEnabled) View.VISIBLE else View.GONE
        switchDoh.setOnCheckedChangeListener { _, isChecked ->
            val ctx = context ?: return@setOnCheckedChangeListener
            DohResolver.setEnabled(ctx, isChecked)
            layoutDohProvider.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateDohStatus()
        }

        sourceAdapter = SourceAdapter { source, downloaded ->
            if (downloaded) deleteSource(source) else downloadSource(source)
        }
        view.findViewById<RecyclerView>(R.id.recyclerSources).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sourceAdapter
        }

        updateProviderButtons()
        refreshSources()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateProviderButtons() {
        val ctx = context ?: return
        val active = DohResolver.provider
        providerButtons.forEach { (id, button) ->
            val on = id == active
            button.setBackgroundResource(
                if (on) R.drawable.bg_segment_filtered else R.drawable.bg_segment_off
            )
            button.setTextColor(ctx.getColor(if (on) R.color.accent else R.color.text_hint))
        }
    }

    private fun updateDohStatus() {
        if (!isAdded) return
        val ctx = context ?: return
        val totals = Stats.totals(ctx)
        txtDohStatus.text = if (DohResolver.isEnabled) {
            getString(R.string.doh_status_on, DohResolver.providerLabel())
        } else {
            getString(R.string.doh_status_off)
        }
        txtDohQueries.text = totals.doh.toString()
    }

    private fun updateStatus() {
        if (!isAdded) return
        val ctx = context ?: return
        val count = BlocklistManager.getActiveCount()
        txtStatus.text = when {
            BlocklistManager.isLoading -> getString(R.string.trackers_status_loading)
            BlocklistManager.isEnabled ->
                resources.getQuantityString(R.plurals.trackers_status_on, count, count)
            else -> getString(R.string.trackers_status_off)
        }
        txtDomainsLoaded.text = count.toString()
        txtTrackersBlocked.text = Stats.totals(ctx).trackers.toString()
        updateDohStatus()
    }

    private fun refreshSources() {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val sources = BlocklistManager.getSources(ctx)
            val counts = mutableMapOf<String, Int>()
            val items = sources.map { source ->
                val downloaded = BlocklistManager.isSourceDownloaded(ctx, source.id)
                if (downloaded) counts[source.id] = BlocklistManager.getDownloadedSourceCount(ctx, source.id)
                source to downloaded
            }
            if (isAdded) sourceAdapter.submitList(items, counts)
        }
    }

    private fun downloadSource(source: BlocklistSource) {
        val ctx = context ?: return
        Toast.makeText(
            ctx, getString(R.string.toast_downloading, source.name), Toast.LENGTH_SHORT
        ).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = BlocklistManager.downloadSource(ctx, source)
            if (!isAdded) return@launch
            result.onSuccess { count ->
                Toast.makeText(
                    context,
                    resources.getQuantityString(R.plurals.toast_names_added, count, count),
                    Toast.LENGTH_SHORT
                ).show()
                refreshSources()
                updateStatus()
            }
            result.onFailure { error ->
                Toast.makeText(
                    context,
                    getString(R.string.toast_download_failed, error.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteSource(source: BlocklistSource) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            BlocklistManager.deleteSource(ctx, source.id)
            if (!isAdded) return@launch
            refreshSources()
            updateStatus()
        }
    }
}
