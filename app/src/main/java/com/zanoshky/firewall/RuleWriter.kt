package com.zanoshky.firewall

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saves a per-app rule and then rebuilds the tunnel.
 *
 * Both halves run on a process-lifetime scope on purpose. The write used to run on
 * the fragment's view scope, so switching tab, rotating or backgrounding the app
 * right after a toggle cancelled it: the row was never written, while the rebuild
 * (which uses the application context and survives) went ahead and rebuilt the
 * tunnel from the old rules. The toggle looked applied but nothing changed.
 *
 * The rebuild is also sequenced after the write instead of being posted on a
 * handler in parallel with it, so the tunnel can never read the rules table
 * before the new row has landed.
 */
object RuleWriter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pendingRebuild: Job? = null

    /** Debounce window for a burst of toggles, in milliseconds. */
    private const val REBUILD_DELAY_MS = 400L

    fun save(context: Context, dao: RuleDao, rule: AppRule) {
        val appCtx = context.applicationContext
        scope.launch {
            dao.upsert(rule)
            scheduleRebuild(appCtx)
        }
    }

    fun saveAll(context: Context, dao: RuleDao, rules: List<AppRule>) {
        val appCtx = context.applicationContext
        scope.launch {
            dao.upsertAll(rules)
            scheduleRebuild(appCtx)
        }
    }

    private suspend fun scheduleRebuild(appCtx: Context) {
        lock.withLock {
            pendingRebuild?.cancel()
            pendingRebuild = scope.launch {
                delay(REBUILD_DELAY_MS)
                TunnelControl.requestRebuild(appCtx)
            }
        }
    }
}
