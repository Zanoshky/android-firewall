package com.zanoshky.firewall

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The per-app modes, held in memory and read by the tunnel.
 *
 * The old design read the rules table once per tunnel build, so every change had
 * to tear the tunnel down and rebuild it, and a change that raced the rebuild was
 * silently lost. Here the map is the source of truth while the process lives: a
 * change lands in memory first and applies to the very next lookup, and the
 * database write follows on a process-lifetime scope so leaving the screen cannot
 * cancel it.
 *
 * Only one kind of change still needs the tunnel rebuilt: moving an app into or
 * out of [AppMode.BYPASS], because which apps are inside the tunnel at all is
 * fixed when the tunnel is established.
 */
object RuleStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var modes: Map<String, Int> = emptyMap()
    @Volatile private var loaded = false

    /** Bumped on every change so the service can drop its UID cache. */
    val version = AtomicInteger(0)

    /** Default for an app with no rule of its own. */
    const val DEFAULT_MODE = AppMode.BLOCKED

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
        }
        scope.launch { reload(context) }
    }

    suspend fun reload(context: Context) {
        val rules = RuleDatabase.get(context.applicationContext).ruleDao().getAll()
        modes = rules.associate { it.packageName to it.mode }
        loaded = true
        version.incrementAndGet()
    }

    /** Blocking load, for the service thread which is already off the main thread. */
    fun reloadBlocking(context: Context) {
        kotlinx.coroutines.runBlocking { reload(context) }
    }

    fun modeOf(packageName: String): Int = modes[packageName] ?: DEFAULT_MODE

    fun snapshot(): Map<String, Int> = modes

    fun packagesInMode(mode: Int): Set<String> =
        modes.filterValues { it == mode }.keys

    /**
     * Several packages can share one Android UID, and network identity is per UID,
     * so they cannot be told apart once a packet is on the wire. Resolve the group
     * to its most permissive member rather than its strictest: treating a shared
     * UID as blocked because one obscure package in it is blocked would cut off
     * apps the user allowed on purpose.
     */
    fun modeOfGroup(packageNames: Collection<String>): Int {
        if (packageNames.isEmpty()) return DEFAULT_MODE
        var best = AppMode.BLOCKED
        for (pkg in packageNames) {
            val mode = modeOf(pkg)
            if (mode == AppMode.BYPASS) return AppMode.BYPASS
            if (mode == AppMode.FILTERED) best = AppMode.FILTERED
        }
        return best
    }

    fun setMode(context: Context, packageName: String, mode: Int) {
        setModes(context, listOf(packageName), mode)
    }

    fun setModes(context: Context, packageNames: List<String>, mode: Int) {
        if (packageNames.isEmpty()) return
        val appCtx = context.applicationContext

        val updated = HashMap(modes)
        var bypassChanged = false
        for (pkg in packageNames) {
            val old = updated[pkg] ?: DEFAULT_MODE
            if (old == mode) continue
            if (old == AppMode.BYPASS || mode == AppMode.BYPASS) bypassChanged = true
            updated[pkg] = mode
        }
        modes = updated
        version.incrementAndGet()

        scope.launch {
            val dao = RuleDatabase.get(appCtx).ruleDao()
            dao.upsertAll(packageNames.map { AppRule(it, mode) })
            // Only the set of apps that live outside the tunnel is baked into the
            // VpnService builder. Everything else is read live, so it is already in
            // force by the time this write lands.
            if (bypassChanged) TunnelControl.requestRebuild(appCtx)
        }
    }

    /** Drop rules for packages that are no longer installed. */
    fun purgeMissing(context: Context, installed: Set<String>) {
        val stale = modes.keys.filter { it !in installed }
        if (stale.isEmpty()) return
        val appCtx = context.applicationContext
        modes = modes.filterKeys { it in installed }
        version.incrementAndGet()
        scope.launch {
            val dao = RuleDatabase.get(appCtx).ruleDao()
            stale.forEach { dao.delete(it) }
        }
    }

    /** Used by restore, which has already replaced the table. */
    fun adoptAfterRestore(context: Context) {
        scope.launch {
            reload(context)
            TunnelControl.requestRebuild(context.applicationContext)
        }
    }
}
