package com.zanoshky.firewall

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class BlocklistSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val format: String
)

/**
 * The tracker and ad lists: one bundled with the app and any the user downloads.
 *
 * This object owns only the lists that come from somewhere else. The user's own
 * block and allow rules live in [DomainRules] and are consulted first, so an
 * allow rule can lift a domain out of a list of a hundred thousand names without
 * that list having to be rewritten.
 */
object BlocklistManager {

    private const val PREFS_NAME = "blocklist_prefs"
    private const val KEY_ENABLED = "blocklist_enabled"
    private const val DOWNLOADED_DIR = "blocklists"

    /** Swapped whole, so the tunnel thread sees either the old set or the new one. */
    @Volatile private var activeDomains: Set<String> = emptySet()

    @Volatile var isEnabled: Boolean = false
        private set

    @Volatile var isLoading: Boolean = false
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        isEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    suspend fun setEnabledAsync(context: Context, enabled: Boolean) {
        isEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) loadDomains(context) else activeDomains = emptySet()
    }

    /** True when [domain] or any parent of it is on one of the loaded lists. */
    fun isDomainBlocked(domain: String): Boolean {
        if (!isEnabled) return false
        val domains = activeDomains
        if (domains.isEmpty()) return false
        var d = domain
        while (true) {
            if (domains.contains(d)) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
            if (!d.contains('.')) return false
        }
    }

    fun getActiveCount(): Int = activeDomains.size

    suspend fun reloadAsync(context: Context) {
        if (isEnabled) loadDomains(context)
    }

    /** For the service, which is already on a background thread. */
    fun reloadSync(context: Context) {
        if (!isEnabled) return
        readAll(context)
    }

    private suspend fun loadDomains(context: Context) = withContext(Dispatchers.IO) {
        readAll(context)
    }

    private fun readAll(context: Context) {
        isLoading = true
        try {
            val domains = HashSet<String>()
            try {
                context.assets.open("blocklist_default.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line -> parseDomainLine(line)?.let { domains.add(it) } }
                }
            } catch (_: Exception) {}

            val dir = File(context.filesDir, DOWNLOADED_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    try {
                        file.bufferedReader().useLines { lines ->
                            lines.forEach { line -> parseDomainLine(line)?.let { domains.add(it) } }
                        }
                    } catch (_: Exception) {}
                }
            }
            activeDomains = domains
        } finally {
            isLoading = false
        }
    }

    // --- Remote sources ---

    fun getSources(context: Context): List<BlocklistSource> {
        return try {
            val json = context.assets.open("blocklist_sources.json").bufferedReader().readText()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BlocklistSource(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    url = obj.getString("url"),
                    format = obj.getString("format")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isSourceDownloaded(context: Context, sourceId: String): Boolean =
        File(context.filesDir, "$DOWNLOADED_DIR/$sourceId.txt").exists()

    suspend fun getDownloadedSourceCount(context: Context, sourceId: String): Int =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "$DOWNLOADED_DIR/$sourceId.txt")
            if (!file.exists()) return@withContext 0
            try { file.readLines().count { parseDomainLine(it) != null } } catch (_: Exception) { 0 }
        }

    suspend fun downloadSource(context: Context, source: BlocklistSource): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(source.url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Firewall Android")

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
                }

                val dir = File(context.filesDir, DOWNLOADED_DIR)
                if (!dir.exists()) dir.mkdirs()

                val outFile = File(dir, "${source.id}.txt")
                var count = 0

                outFile.bufferedWriter().use { writer ->
                    BufferedReader(InputStreamReader(conn.inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            val domain = when (source.format) {
                                "adblock" -> parseAdblockLine(line)
                                else -> parseDomainLine(line)
                            }
                            if (domain != null) {
                                writer.write(domain)
                                writer.newLine()
                                count++
                            }
                        }
                    }
                }

                conn.disconnect()
                reloadAsync(context)
                Result.success(count)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteSource(context: Context, sourceId: String) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "$DOWNLOADED_DIR/$sourceId.txt")
        if (file.exists()) file.delete()
        reloadAsync(context)
    }

    // --- Parsing helpers ---

    private fun parseDomainLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) return null
        val parts = trimmed.split("\\s+".toRegex())
        return if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
            val d = parts[1].lowercase()
            if (d != "localhost" && d.contains('.')) d else null
        } else if (parts.size == 1 && parts[0].contains('.') && !parts[0].contains('/')) {
            parts[0].lowercase().removePrefix("*.").takeIf { it.isNotEmpty() }
        } else null
    }

    private fun parseAdblockLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith('!') || trimmed.startsWith('[') || trimmed.isEmpty()) return null
        if (!trimmed.startsWith("||")) return null
        val raw = trimmed.removePrefix("||")
        val caret = raw.indexOf('^')
        val dollar = raw.indexOf('$')
        val end = when {
            caret >= 0 && dollar >= 0 -> minOf(caret, dollar)
            caret >= 0 -> caret
            dollar >= 0 -> dollar
            else -> raw.length
        }
        val domain = raw.substring(0, end).lowercase()
        return if (domain.contains('.') && !domain.contains('/') && !domain.contains('*')) domain else null
    }
}
