package com.zanoshky.firewall

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {

    private lateinit var logAdapter: LogAdapter
    private lateinit var logDao: ConnectionLogDao
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var chips: List<TextView>
    private var filterQuery = ""
    private var statusFilter = 0 // 0 all, 1 blocked, 2 allowed, 3 trackers
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var searchDebounce: Runnable? = null
    private var loadJob: Job? = null // prevent overlapping loads

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logDao = RuleDatabase.get(requireContext()).connectionLogDao()
        logAdapter = LogAdapter()

        recycler = view.findViewById(R.id.recyclerLogs)
        txtEmpty = view.findViewById(R.id.txtEmptyLogs)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = logAdapter

        chips = listOf(
            view.findViewById(R.id.chipAll),
            view.findViewById(R.id.chipBlocked),
            view.findViewById(R.id.chipAllowed),
            view.findViewById(R.id.chipTrackers)
        )
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                if (statusFilter != index) {
                    statusFilter = index
                    updateChips()
                    loadLogs()
                }
            }
        }
        updateChips()

        view.findViewById<TextView>(R.id.btnClearLogs).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                logDao.deleteAll()
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        logAdapter.submitList(emptyList())
                        showEmpty(true)
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.btnExportLogs).setOnClickListener { exportCsv() }

        view.findViewById<EditText>(R.id.editFilterLogs).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s?.toString()?.trim() ?: ""
                searchDebounce?.let { handler.removeCallbacks(it) }
                searchDebounce = Runnable { loadLogs() }
                handler.postDelayed(searchDebounce!!, 250)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadLogs()
        startAutoRefresh()
    }

    override fun onDestroyView() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        searchDebounce?.let { handler.removeCallbacks(it) }
        loadJob?.cancel()
        super.onDestroyView()
    }

    private fun updateChips() {
        chips.forEachIndexed { index, chip ->
            chip.setBackgroundResource(
                if (index == statusFilter) R.drawable.bg_chip_active else R.drawable.bg_chip
            )
            chip.setTextColor(
                requireContext().getColor(
                    if (index == statusFilter) R.color.accent else R.color.text_secondary
                )
            )
        }
    }

    private fun startAutoRefresh() {
        refreshRunnable = object : Runnable {
            override fun run() {
                if (isAdded) loadLogs()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(refreshRunnable!!, 3000)
    }

    private fun loadLogs() {
        // Cancel previous load if still running
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val status = statusFilter
            val query = filterQuery
            val logs = withContext(Dispatchers.IO) { logDao.getFiltered(status, query, 500) }
            if (!isAdded) return@launch
            logAdapter.submitList(logs)
            showEmpty(logs.isEmpty())
        }
    }

    private fun exportCsv() {
        val appCtx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                logDao.getFiltered(statusFilter, filterQuery, 10000)
            }
            if (!isAdded) return@launch
            if (logs.isEmpty()) {
                Toast.makeText(appCtx, "No log entries to export", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val file = withContext(Dispatchers.IO) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(appCtx.cacheDir, "exports").apply { mkdirs() }
                val out = File(dir, "firewall-logs-$stamp.csv")
                val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                out.bufferedWriter().use { w ->
                    w.appendLine("timestamp,app,domain,dest_ip,dest_port,protocol,status,bytes")
                    for (log in logs) {
                        val status = when {
                            log.blockedByTracker -> "tracker"
                            log.allowed -> "allowed"
                            else -> "blocked"
                        }
                        w.appendLine(
                            listOf(
                                iso.format(Date(log.timestamp)),
                                csv(log.appName),
                                csv(log.domain),
                                log.destIp,
                                log.destPort.toString(),
                                log.protocol,
                                status,
                                log.bytes.toString()
                            ).joinToString(",")
                        )
                    }
                }
                out
            }

            if (!isAdded) return@launch
            val uri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The share sheet backgrounds the activity; that is not the user leaving.
            AppLock.suppressNextRelock()
            startActivity(Intent.createChooser(send, "Export ${logs.size} log entries"))
        }
    }

    private fun csv(field: String): String =
        if (field.contains(',') || field.contains('"'))
            "\"${field.replace("\"", "\"\"")}\"" else field

    private fun showEmpty(empty: Boolean) {
        if (!isAdded) return
        txtEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }
}
