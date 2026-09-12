package com.zanoshky.firewall

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * What the firewall has done, counted once and read everywhere.
 *
 * Counters live in two halves: an in-memory half the tunnel bumps on every
 * lookup, and a stored half that survives a restart. The UI always shows the sum
 * of the two, and only the service ever folds one into the other, so no reader
 * can race the read-modify-write on the stored keys.
 */
object Stats {

    private const val PREFS = "firewall_prefs"

    private const val K_QUERIES = "stat_queries"
    private const val K_DOH = "stat_doh"
    private const val K_PLAIN = "stat_plain"
    private const val K_TRACKERS = "stat_trackers"
    private const val K_DOMAINS = "stat_domains"
    private const val K_APP_BLOCKED = "stat_app_blocked"
    private const val K_REFUSED = "stat_refused"
    private const val K_PROTECTED_MS = "stat_protected_ms"
    private const val K_SESSIONS = "stat_sessions"

    /** Lookups seen by the firewall. */
    val queries = AtomicLong(0)

    /** Lookups answered over an encrypted connection to the DoH provider. */
    val dohQueries = AtomicLong(0)

    /** Lookups forwarded to the network's own resolver in plain text. */
    val plainQueries = AtomicLong(0)

    /** Lookups stopped by a downloaded tracker list. */
    val trackersBlocked = AtomicLong(0)

    /** Lookups stopped by the user's own block list. */
    val domainsBlocked = AtomicLong(0)

    /** Lookups refused because the app itself is blocked. */
    val appBlocked = AtomicLong(0)

    /** Connections to a blocked address that were refused outright. */
    val connectionsRefused = AtomicLong(0)

    @Volatile var sessionStart: Long = 0

    fun blockedThisSession(): Long =
        trackersBlocked.get() + domainsBlocked.get() + appBlocked.get()

    data class Totals(
        val queries: Long,
        val doh: Long,
        val plain: Long,
        val trackers: Long,
        val domains: Long,
        val appBlocked: Long,
        val refused: Long,
        val protectedMs: Long,
        val sessions: Long
    ) {
        val blocked: Long get() = trackers + domains + appBlocked
        val allowed: Long get() = (queries - blocked).coerceAtLeast(0)
        val encryptedShare: Int
            get() {
                val resolved = doh + plain
                return if (resolved <= 0) 0 else ((doh * 100) / resolved).toInt()
            }
        val blockedShare: Int
            get() = if (queries <= 0) 0 else ((blocked * 100) / queries).toInt()
    }

    fun totals(context: Context): Totals {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val live = if (sessionStart > 0) System.currentTimeMillis() - sessionStart else 0
        return Totals(
            queries = p.getLong(K_QUERIES, 0) + queries.get(),
            doh = p.getLong(K_DOH, 0) + dohQueries.get(),
            plain = p.getLong(K_PLAIN, 0) + plainQueries.get(),
            trackers = p.getLong(K_TRACKERS, 0) + trackersBlocked.get(),
            domains = p.getLong(K_DOMAINS, 0) + domainsBlocked.get(),
            appBlocked = p.getLong(K_APP_BLOCKED, 0) + appBlocked.get(),
            refused = p.getLong(K_REFUSED, 0) + connectionsRefused.get(),
            protectedMs = p.getLong(K_PROTECTED_MS, 0) + live,
            sessions = p.getLong(K_SESSIONS, 0)
        )
    }

    /** Called by the service only: fold the in-memory half into the stored half. */
    fun flush(context: Context, closingSession: Boolean = false) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val q = queries.getAndSet(0)
        val d = dohQueries.getAndSet(0)
        val pl = plainQueries.getAndSet(0)
        val t = trackersBlocked.getAndSet(0)
        val dom = domainsBlocked.getAndSet(0)
        val ab = appBlocked.getAndSet(0)
        val r = connectionsRefused.getAndSet(0)

        val now = System.currentTimeMillis()
        val elapsed = if (sessionStart > 0) now - sessionStart else 0
        sessionStart = if (closingSession) 0 else now

        val edit = p.edit()
            .putLong(K_QUERIES, p.getLong(K_QUERIES, 0) + q)
            .putLong(K_DOH, p.getLong(K_DOH, 0) + d)
            .putLong(K_PLAIN, p.getLong(K_PLAIN, 0) + pl)
            .putLong(K_TRACKERS, p.getLong(K_TRACKERS, 0) + t)
            .putLong(K_DOMAINS, p.getLong(K_DOMAINS, 0) + dom)
            .putLong(K_APP_BLOCKED, p.getLong(K_APP_BLOCKED, 0) + ab)
            .putLong(K_REFUSED, p.getLong(K_REFUSED, 0) + r)
            .putLong(K_PROTECTED_MS, p.getLong(K_PROTECTED_MS, 0) + elapsed)
        edit.apply()
    }

    fun startSession(context: Context) {
        sessionStart = System.currentTimeMillis()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong(K_SESSIONS, p.getLong(K_SESSIONS, 0) + 1).apply()
    }

    fun reset(context: Context) {
        queries.set(0); dohQueries.set(0); plainQueries.set(0)
        trackersBlocked.set(0); domainsBlocked.set(0); appBlocked.set(0)
        connectionsRefused.set(0)
        val start = sessionStart
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_QUERIES).remove(K_DOH).remove(K_PLAIN)
            .remove(K_TRACKERS).remove(K_DOMAINS).remove(K_APP_BLOCKED)
            .remove(K_REFUSED).remove(K_PROTECTED_MS).remove(K_SESSIONS)
            .apply()
        sessionStart = if (start > 0) System.currentTimeMillis() else 0
    }
}
