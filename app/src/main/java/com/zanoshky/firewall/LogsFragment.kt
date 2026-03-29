package com.zanoshky.firewall

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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogsFragment : Fragment() {

    private lateinit var logAdapter: LogAdapter
    private lateinit var logDao: ConnectionLogDao
    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private var filterQuery = ""
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
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

        view.findViewById<EditText>(R.id.editFilterLogs).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterQuery = s?.toString()?.lowercase() ?: ""
                loadLogs()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadLogs()
        startAutoRefresh()
    }

    override fun onDestroyView() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        loadJob?.cancel()
        super.onDestroyView()
    }

    private fun startAutoRefresh() {
        refreshRunnable = object : Runnable {
            override fun run() {
                if (isAdded) loadLogs()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(refreshRunnable!!)
    }

    private fun loadLogs() {
        // Cancel previous load if still running
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) { logDao.getRecent(500) }
            if (!isAdded) return@launch
            val query = filterQuery // snapshot
            val filtered = if (query.isEmpty()) logs
                else logs.filter { it.appName.lowercase().contains(query) ||
                    it.destIp.contains(query) ||
                    it.domain.lowercase().contains(query) }
            logAdapter.submitList(filtered)
            showEmpty(filtered.isEmpty())
        }
    }

    private fun showEmpty(empty: Boolean) {
        if (!isAdded) return
        txtEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }
}
