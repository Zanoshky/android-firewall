package com.zanoshky.firewall

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The user's own domain rules.
 *
 * This is the tab people were asking for: blocking google.com here really does
 * stop google.com, in every app that is set to Filtered, because the firewall
 * now sees those apps' lookups instead of waving them past.
 *
 * Both lists share one screen and one input. Two lists side by side with a
 * scroll view around them was the shape this replaced, and a list inside a
 * scroll view only ever lays out its first row.
 */
class DomainsFragment : Fragment() {

    private lateinit var adapter: DomainAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var chipBlock: TextView
    private lateinit var chipAllow: TextView
    private lateinit var txtHint: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var editDomain: EditText

    /** True while the block list is the one on screen. */
    private var showingBlockList = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_domains, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        chipBlock = view.findViewById(R.id.chipBlockList)
        chipAllow = view.findViewById(R.id.chipAllowList)
        txtHint = view.findViewById(R.id.txtListHint)
        txtEmpty = view.findViewById(R.id.txtEmptyDomains)
        editDomain = view.findViewById(R.id.editDomain)

        adapter = DomainAdapter { domain -> remove(domain) }
        recycler = view.findViewById(R.id.recyclerDomains)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        chipBlock.setOnClickListener { switchTo(true) }
        chipAllow.setOnClickListener { switchTo(false) }

        view.findViewById<TextView>(R.id.btnAddDomain).setOnClickListener { add() }
        editDomain.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }

        switchTo(true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun switchTo(blockList: Boolean) {
        showingBlockList = blockList
        chipBlock.setBackgroundResource(
            if (blockList) R.drawable.bg_segment_blocked else R.drawable.bg_segment_off
        )
        chipAllow.setBackgroundResource(
            if (blockList) R.drawable.bg_segment_off else R.drawable.bg_segment_filtered
        )
        context?.let { ctx ->
            chipBlock.setTextColor(ctx.getColor(if (blockList) R.color.danger else R.color.text_hint))
            chipAllow.setTextColor(ctx.getColor(if (blockList) R.color.text_hint else R.color.accent))
        }
        editDomain.hint = if (blockList) "Domain to block" else "Domain to allow"
        refresh()
    }

    private fun add() {
        val ctx = context ?: return
        val input = editDomain.text.toString()
        if (input.isBlank()) return

        val stored = if (showingBlockList) DomainRules.addBlocked(ctx, input)
        else DomainRules.addAllowed(ctx, input)

        if (stored == null) {
            Toast.makeText(ctx, "That is not a domain name", Toast.LENGTH_SHORT).show()
            return
        }
        editDomain.text.clear()
        refresh()
        Toast.makeText(
            ctx,
            if (showingBlockList) "$stored blocked" else "$stored allowed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun remove(domain: String) {
        val ctx = context ?: return
        if (showingBlockList) DomainRules.removeBlocked(ctx, domain)
        else DomainRules.removeAllowed(ctx, domain)
        refresh()
    }

    private fun refresh() {
        if (!isAdded) return
        val ctx = context ?: return
        val domains = if (showingBlockList) DomainRules.blockedDomains(ctx)
        else DomainRules.allowedDomains(ctx)

        adapter.submitList(domains)

        txtHint.text = if (showingBlockList) {
            "${domains.size} blocked. Nothing on this list can be reached by a filtered app."
        } else {
            "${domains.size} allowed. These are let through even when a tracker list names them."
        }

        val empty = domains.isEmpty()
        txtEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        txtEmpty.text = if (showingBlockList) {
            "No blocked domains yet.\nAdd one above to stop it everywhere."
        } else {
            "No allowed domains yet.\nAdd one here when a tracker list blocks something you need."
        }
    }
}
