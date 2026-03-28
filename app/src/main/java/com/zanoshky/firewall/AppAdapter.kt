package com.zanoshky.firewall

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
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
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = apps.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(old: Int, new: Int) =
                apps[old].packageName == list[new].packageName
            override fun areContentsTheSame(old: Int, new: Int) =
                apps[old].allowWifi == list[new].allowWifi &&
                apps[old].allowMobile == list[new].allowMobile
        })
        apps = list
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
        private val txtName: TextView = view.findViewById(R.id.txtName)
        private val txtPackage: TextView = view.findViewById(R.id.txtPackage)
        private val txtSystemBadge: TextView = view.findViewById(R.id.txtSystemBadge)
        private val btnWifi: FrameLayout = view.findViewById(R.id.btnWifi)
        private val txtWifi: TextView = view.findViewById(R.id.txtWifi)
        private val btnMobile: FrameLayout = view.findViewById(R.id.btnMobile)
        private val txtMobile: TextView = view.findViewById(R.id.txtMobile)

        private val ctx = view.context
        private val colorOn = ContextCompat.getColor(ctx, R.color.toggle_on)
        private val colorOff = ContextCompat.getColor(ctx, R.color.text_secondary)
        private val pulseAnim = AnimationUtils.loadAnimation(ctx, R.anim.scale_pulse)

        fun bind(app: AppInfo) {
            imgIcon.setImageDrawable(app.icon)
            txtName.text = app.name
            txtPackage.text = app.packageName
            txtSystemBadge.visibility = if (app.isSystem) View.VISIBLE else View.GONE

            updateToggleState(btnWifi, txtWifi, app.allowWifi, false)
            updateToggleState(btnMobile, txtMobile, app.allowMobile, false)

            btnWifi.setOnClickListener {
                app.allowWifi = !app.allowWifi
                updateToggleState(btnWifi, txtWifi, app.allowWifi, true)
                it.startAnimation(pulseAnim)
                onToggleWifi(app)
            }
            btnMobile.setOnClickListener {
                app.allowMobile = !app.allowMobile
                updateToggleState(btnMobile, txtMobile, app.allowMobile, true)
                it.startAnimation(pulseAnim)
                onToggleMobile(app)
            }
        }

        private fun updateToggleState(btn: FrameLayout, txt: TextView, allowed: Boolean, animate: Boolean) {
            val bgRes = if (allowed) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off
            btn.setBackgroundResource(bgRes)

            if (animate) {
                val from = if (allowed) colorOff else colorOn
                val to = if (allowed) colorOn else colorOff
                ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
                    duration = 250
                    addUpdateListener { txt.setTextColor(it.animatedValue as Int) }
                    start()
                }
            } else {
                txt.setTextColor(if (allowed) colorOn else colorOff)
            }

            btn.contentDescription = if (allowed) "Allowed – tap to block" else "Blocked – tap to allow"
        }
    }
}
