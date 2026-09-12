package com.zanoshky.firewall

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onModeChanged: (AppInfo, Int) -> Unit
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
                oldList[old].mode == list[new].mode &&
                    oldList[old].lookups == list[new].lookups &&
                    oldList[old].blocked == list[new].blocked
        })
        apps = list
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Repaint every row. A bulk change mutates the same AppInfo objects the
     * adapter is already holding, so a diff against them finds nothing to do.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun refreshAll() {
        notifyDataSetChanged()
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
        private val btnBypass: TextView = view.findViewById(R.id.btnBypass)
        private val btnFiltered: TextView = view.findViewById(R.id.btnFiltered)
        private val btnBlocked: TextView = view.findViewById(R.id.btnBlocked)

        private val ctx = view.context

        fun bind(app: AppInfo) {
            imgIcon.setImageDrawable(app.icon)
            txtName.text = app.name
            txtPackage.text = app.packageName
            txtSystemBadge.visibility = if (app.isSystem) View.VISIBLE else View.GONE

            if (app.lookups > 0) {
                txtAppStats.visibility = View.VISIBLE
                txtAppStats.text = "${app.lookups} lookups, ${app.blocked} blocked"
            } else {
                txtAppStats.visibility = View.GONE
            }

            paint(app.mode)

            btnBypass.setOnClickListener { choose(app, AppMode.BYPASS) }
            btnFiltered.setOnClickListener { choose(app, AppMode.FILTERED) }
            btnBlocked.setOnClickListener { choose(app, AppMode.BLOCKED) }
        }

        private fun choose(app: AppInfo, mode: Int) {
            if (app.mode == mode) return
            app.mode = mode
            paint(mode)
            onModeChanged(app, mode)
        }

        private fun paint(mode: Int) {
            style(btnBypass, mode == AppMode.BYPASS, R.drawable.bg_segment_bypass, R.color.mode_bypass)
            style(btnFiltered, mode == AppMode.FILTERED, R.drawable.bg_segment_filtered, R.color.accent)
            style(btnBlocked, mode == AppMode.BLOCKED, R.drawable.bg_segment_blocked, R.color.danger)

            itemView.contentDescription = when (mode) {
                AppMode.BYPASS -> "Bypass, not filtered"
                AppMode.FILTERED -> "Filtered through the firewall"
                else -> "Blocked"
            }
        }

        private fun style(button: TextView, active: Boolean, background: Int, colour: Int) {
            button.setBackgroundResource(if (active) background else R.drawable.bg_segment_off)
            button.setTextColor(
                ContextCompat.getColor(ctx, if (active) colour else R.color.text_hint)
            )
        }
    }
}
