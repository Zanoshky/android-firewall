package com.zanoshky.firewall

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppTrafficInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val bytesIn: Long,
    val bytesOut: Long
)

class AppTrafficAdapter : RecyclerView.Adapter<AppTrafficAdapter.ViewHolder>() {

    private var items: List<AppTrafficInfo> = emptyList()

    fun submitList(list: List<AppTrafficInfo>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_traffic, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgIcon: ImageView = view.findViewById(R.id.imgAppIcon)
        private val txtName: TextView = view.findViewById(R.id.txtAppName)
        private val txtDown: TextView = view.findViewById(R.id.txtAppDown)
        private val txtUp: TextView = view.findViewById(R.id.txtAppUp)
        private val txtTotal: TextView = view.findViewById(R.id.txtAppTotal)

        fun bind(item: AppTrafficInfo) {
            item.icon?.let { imgIcon.setImageDrawable(it) }
            txtName.text = item.appName
            txtDown.text = "D ${formatBytes(item.bytesIn)}"
            txtUp.text = "U ${formatBytes(item.bytesOut)}"
            txtTotal.text = formatBytes(item.bytesIn + item.bytesOut)
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
