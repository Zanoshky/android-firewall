package com.zanoshky.firewall

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onToggleWifi: (AppInfo) -> Unit,
    private val onToggleMobile: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var apps: List<AppInfo> = emptyList()

    fun submitList(list: List<AppInfo>) {
        val oldList = apps
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(old: Int, new: Int) =
                oldList[old].packageName == list[new].packageName
            override fun areContentsTheSame(old: Int, new: Int) =
                oldList[old].allowWifi == list[new].allowWifi &&
                oldList[old].allowMobile == list[new].allowMobile &&
                oldList[old].blockedRequests == list[new].blockedRequests
        })
        apps = list
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = apps.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(apps[position])

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
        private val txtName: TextView = view.findViewById(R.id.txtName)
        private val txtPackage: TextView = view.findViewById(R.id.txtPackage)
        private val txtSystemBadge: TextView = view.findViewById(R.id.txtSystemBadge)
        private val txtAppStats: TextView = view.findViewById(R.id.txtAppStats)
        private val btnWifi: FrameLayout = view.findViewById(R.id.btnWifi)
        private val txtWifi: TextView = view.findViewById(R.id.txtWifi)
        private val btnMobile: FrameLayout = view.findViewById(R.id.btnMobile)
        private val txtMobile: TextView = view.findViewById(R.id.txtMobile)

        private val ctx = view.context
        private val colorOn = ContextCompat.getColor(ctx, R.color.toggle_on)
        private val colorOff = ContextCompat.getColor(ctx, R.color.text_hint)

        fun bind(app: AppInfo) {
            imgIcon.setImageDrawable(app.icon)
            txtName.text = app.name
            txtPackage.text = app.packageName
            txtSystemBadge.visibility = if (app.isSystem) View.VISIBLE else View.GONE

            val totalReqs = app.blockedRequests + app.allowedRequests
            if (totalReqs > 0) {
                txtAppStats.visibility = View.VISIBLE
                txtAppStats.text = "${formatCount(app.blockedRequests)} blocked - ${formatCount(app.allowedRequests)} allowed"
            } else {
                txtAppStats.visibility = View.GONE
            }

            updateToggle(btnWifi, txtWifi, app.allowWifi)
            updateToggle(btnMobile, txtMobile, app.allowMobile)

            btnWifi.setOnClickListener {
                app.allowWifi = !app.allowWifi
                updateToggle(btnWifi, txtWifi, app.allowWifi)
                onToggleWifi(app)
            }
            btnMobile.setOnClickListener {
                app.allowMobile = !app.allowMobile
                updateToggle(btnMobile, txtMobile, app.allowMobile)
                onToggleMobile(app)
            }
        }

        private fun formatCount(n: Long): String = when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
            else -> n.toString()
        }

        private fun updateToggle(btn: FrameLayout, txt: TextView, allowed: Boolean) {
            btn.setBackgroundResource(if (allowed) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
            txt.setTextColor(if (allowed) colorOn else colorOff)
            btn.contentDescription = if (allowed) "Allowed – tap to block" else "Blocked – tap to allow"
        }
    }
}
