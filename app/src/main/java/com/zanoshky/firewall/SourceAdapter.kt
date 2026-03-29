package com.zanoshky.firewall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SourceAdapter(
    private val onAction: (BlocklistSource, Boolean) -> Unit // (source, isDownloaded)
) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

    private var items: List<Pair<BlocklistSource, Boolean>> = emptyList() // source + isDownloaded
    private var domainCounts: Map<String, Int> = emptyMap()

    fun submitList(list: List<Pair<BlocklistSource, Boolean>>, counts: Map<String, Int> = emptyMap()) {
        items = list
        domainCounts = counts
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blocklist_source, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtName: TextView = view.findViewById(R.id.txtSourceName)
        private val txtDesc: TextView = view.findViewById(R.id.txtSourceDesc)
        private val txtStatus: TextView = view.findViewById(R.id.txtSourceStatus)
        private val btnAction: TextView = view.findViewById(R.id.btnSourceAction)

        fun bind(item: Pair<BlocklistSource, Boolean>) {
            val (source, downloaded) = item
            txtName.text = source.name
            txtDesc.text = source.description

            if (downloaded) {
                val count = domainCounts[source.id] ?: 0
                txtStatus.text = "✓ Downloaded · $count domains"
                txtStatus.setTextColor(itemView.context.getColor(R.color.status_active))
                btnAction.text = "Remove"
                btnAction.setTextColor(itemView.context.getColor(R.color.accent_pink))
            } else {
                txtStatus.text = "Not downloaded"
                txtStatus.setTextColor(itemView.context.getColor(R.color.text_hint))
                btnAction.text = "Download"
                btnAction.setTextColor(itemView.context.getColor(R.color.accent_blue))
            }

            btnAction.setOnClickListener { onAction(source, downloaded) }
        }
    }
}
