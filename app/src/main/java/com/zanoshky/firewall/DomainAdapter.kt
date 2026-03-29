package com.zanoshky.firewall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DomainAdapter(
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<DomainAdapter.ViewHolder>() {

    private var domains: List<String> = emptyList()

    fun submitList(list: List<String>) {
        domains = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = domains.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_domain, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(domains[position])

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtDomain: TextView = view.findViewById(R.id.txtDomain)
        private val btnRemove: TextView = view.findViewById(R.id.btnRemove)

        fun bind(domain: String) {
            txtDomain.text = domain
            btnRemove.setOnClickListener { onRemove(domain) }
        }
    }
}
