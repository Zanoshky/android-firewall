package com.zanoshky.firewall

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's own domain rules: a block list and an allow list of ordinary
 * websites, kept apart from the downloaded tracker lists.
 *
 * A rule covers the domain and everything under it, so blocking `google.com`
 * also blocks `www.google.com` and `mail.google.com`. When both lists have
 * something to say, the more specific rule wins: block `google.com`, allow
 * `mail.google.com`, and mail keeps working while the rest of the domain does
 * not. The allow list is also what lifts a domain out of the tracker lists.
 *
 * Both sets are held in memory and swapped whole, so the tunnel sees a change on
 * the very next lookup without being rebuilt.
 */
object DomainRules {

    private const val PREFS_NAME = "domain_rules"
    private const val KEY_BLOCKED = "blocked_domains"
    private const val KEY_ALLOWED = "allowed_domains"
    private const val KEY_MIGRATED = "migrated_from_blocklist_prefs"

    const val UNKNOWN = 0
    const val BLOCK = 1
    const val ALLOW = 2

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        migrateIfNeeded(context)
        blocked = read(context, KEY_BLOCKED)
        allowed = read(context, KEY_ALLOWED)
        loaded = true
    }

    private fun ensure(context: Context) {
        if (!loaded) init(context)
    }

    /**
     * Before this release the two lists lived in the tracker screen's preferences
     * as "custom blocked" and "whitelisted". Carry them over once so nobody's
     * rules disappear when the Domains tab takes over.
     */
    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val old = context.getSharedPreferences("blocklist_prefs", Context.MODE_PRIVATE)
        val oldBlocked = old.getString("custom_blocked_domains", "") ?: ""
        val oldAllowed = old.getString("whitelisted_domains", "") ?: ""
        p.edit()
            .putString(KEY_BLOCKED, oldBlocked)
            .putString(KEY_ALLOWED, oldAllowed)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    private fun read(context: Context, key: String): Set<String> {
        val raw = prefs(context).getString(key, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split('\n').mapNotNull { normalise(it) }.toSet()
    }

    private fun write(context: Context, key: String, values: Collection<String>) {
        prefs(context).edit().putString(key, values.joinToString("\n")).apply()
    }

    /**
     * Reduce whatever the user typed to a bare host name. Accepts a pasted URL,
     * a leading `*.` wildcard and a trailing dot. Returns null when there is no
     * usable host left.
     */
    fun normalise(input: String): String? {
        var d = input.trim().lowercase()
        if (d.isEmpty()) return null
        d = d.substringAfter("://")
        d = d.substringBefore('/')
        d = d.substringBefore('?')
        d = d.substringBefore('#')
        d = d.substringAfterLast('@')
        d = d.substringBefore(':')
        d = d.removePrefix("*.").removePrefix(".").removeSuffix(".")
        if (d.isEmpty() || !d.contains('.')) return null
        if (d.any { it.isWhitespace() }) return null
        if (d.any { it !in "abcdefghijklmnopqrstuvwxyz0123456789.-_" }) return null
        return d
    }

    /**
     * [BLOCK], [ALLOW] or [UNKNOWN] for [domain]. Walks from the full name up
     * through its parents and stops at the first level either list mentions, so
     * the most specific rule is the one that decides. Within one level the allow
     * list wins, which is what makes it usable as an exception list.
     */
    fun decide(domain: String): Int {
        val b = blocked
        val a = allowed
        if (b.isEmpty() && a.isEmpty()) return UNKNOWN
        var d = domain
        while (true) {
            if (a.contains(d)) return ALLOW
            if (b.contains(d)) return BLOCK
            val dot = d.indexOf('.')
            if (dot < 0) return UNKNOWN
            d = d.substring(dot + 1)
            if (!d.contains('.')) return UNKNOWN
        }
    }

    fun blockedDomains(context: Context): List<String> {
        ensure(context)
        return blocked.sorted()
    }

    fun allowedDomains(context: Context): List<String> {
        ensure(context)
        return allowed.sorted()
    }

    fun blockedCount(): Int = blocked.size
    fun allowedCount(): Int = allowed.size

    /** Returns the stored form of [input], or null when it was not a usable domain. */
    fun addBlocked(context: Context, input: String): String? {
        ensure(context)
        val d = normalise(input) ?: return null
        blocked = blocked + d
        allowed = allowed - d
        write(context, KEY_BLOCKED, blocked)
        write(context, KEY_ALLOWED, allowed)
        return d
    }

    fun addAllowed(context: Context, input: String): String? {
        ensure(context)
        val d = normalise(input) ?: return null
        allowed = allowed + d
        blocked = blocked - d
        write(context, KEY_ALLOWED, allowed)
        write(context, KEY_BLOCKED, blocked)
        return d
    }

    fun removeBlocked(context: Context, domain: String) {
        ensure(context)
        blocked = blocked - domain
        write(context, KEY_BLOCKED, blocked)
    }

    fun removeAllowed(context: Context, domain: String) {
        ensure(context)
        allowed = allowed - domain
        write(context, KEY_ALLOWED, allowed)
    }

    /** Used by restore, which writes the preference file directly. */
    fun replaceAll(context: Context, blockedList: List<String>, allowedList: List<String>) {
        val b = blockedList.mapNotNull { normalise(it) }.toSet()
        val a = allowedList.mapNotNull { normalise(it) }.toSet() - b
        blocked = b
        allowed = a
        write(context, KEY_BLOCKED, b)
        write(context, KEY_ALLOWED, a)
        prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
        loaded = true
    }
}
