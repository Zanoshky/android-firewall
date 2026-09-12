package com.zanoshky.firewall

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val onClick: (ConnectionLog) -> Unit = {}
) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var logs: List<ConnectionLog> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

    fun submitList(list: List<ConnectionLog>) {
        val oldList = logs
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(old: Int, new: Int) = oldList[old].id == list[new].id
            override fun areContentsTheSame(old: Int, new: Int) = oldList[old] == list[new]
        })
        logs = list
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = logs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(logs[position])

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val viewDot: View = view.findViewById(R.id.viewDot)
        private val txtApp: TextView = view.findViewById(R.id.txtLogApp)
        private val txtDest: TextView = view.findViewById(R.id.txtLogDest)
        private val txtTime: TextView = view.findViewById(R.id.txtLogTime)
        private val txtStatus: TextView = view.findViewById(R.id.txtLogStatus)
        private val ctx = view.context

        fun bind(log: ConnectionLog) {
            txtApp.text = log.appName
            txtDest.text = if (log.domain.isNotEmpty()) {
                "${log.domain} · ${log.protocol}"
            } else {
                "${log.destIp}:${log.destPort} · ${log.protocol}"
            }

            val age = System.currentTimeMillis() - log.timestamp
            txtTime.text = if (age < 86_400_000) timeFormat.format(Date(log.timestamp))
            else dateFormat.format(Date(log.timestamp))

            val colour = when (log.blockReason) {
                BlockReason.TRACKER -> R.color.accent_orange
                BlockReason.BLOCKLIST -> R.color.danger
                BlockReason.APP_BLOCKED -> R.color.danger
                BlockReason.DROPPED -> R.color.danger
                else -> R.color.accent
            }
            txtStatus.text = BlockReason.label(log.blockReason)
            txtStatus.setTextColor(ContextCompat.getColor(ctx, colour))
            (viewDot.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(ctx, colour)
            )

            itemView.setOnClickListener { onClick(log) }
        }
    }
}
