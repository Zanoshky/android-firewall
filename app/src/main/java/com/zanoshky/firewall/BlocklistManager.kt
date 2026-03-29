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

object BlocklistManager {

    private const val PREFS_NAME = "blocklist_prefs"
    private const val KEY_ENABLED = "blocklist_enabled"
    private const val KEY_CUSTOM_DOMAINS = "custom_blocked_domains"
    private const val KEY_WHITELISTED = "whitelisted_domains"
    private const val DOWNLOADED_DIR = "blocklists"

    // Thread-safe domain set — swapped atomically, read by VPN thread
    @Volatile
    private var activeDomains: Set<String> = emptySet()

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isLoading: Boolean = false
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        isEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
        // Don't load on main thread — will be loaded on first VPN start or via suspend
    }

    /** Call from coroutine only */
    suspend fun setEnabledAsync(context: Context, enabled: Boolean) {
        isEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            loadAllDomainsAsync(context)
        } else {
            activeDomains = emptySet()
        }
    }

    fun isDomainBlocked(domain: String): Boolean {
        if (!isEnabled) return false
        val domains = activeDomains // local snapshot
        if (domains.isEmpty()) return false
        var d = domain.lowercase()
        while (d.contains('.')) {
            if (domains.contains(d)) return true
            d = d.substringAfter('.')
        }
        return false
    }

    /** Async loading — safe to call from any coroutine */
    suspend fun loadAllDomainsAsync(context: Context) = withContext(Dispatchers.IO) {
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

            getCustomDomains(context).forEach { domains.add(it.lowercase()) }
            getWhitelistedDomains(context).forEach { domains.remove(it.lowercase()) }

            // Atomic swap — VPN thread sees either old or new set, never empty
            activeDomains = domains
        } finally {
            isLoading = false
        }
    }

    suspend fun reloadAsync(context: Context) {
        if (isEnabled) loadAllDomainsAsync(context)
    }

    /** Sync reload for VPN service (already on IO thread) */
    fun reloadSync(context: Context) {
        if (!isEnabled) return
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

            getCustomDomains(context).forEach { domains.add(it.lowercase()) }
            getWhitelistedDomains(context).forEach { domains.remove(it.lowercase()) }

            activeDomains = domains
        } finally {
            isLoading = false
        }
    }

    fun getActiveCount(): Int = activeDomains.size

    // --- Custom domains ---

    fun getCustomDomains(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_DOMAINS, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun addCustomDomain(context: Context, domain: String) {
        val current = getCustomDomains(context).toMutableList()
        val d = domain.lowercase().trim()
        if (d.isNotEmpty() && d !in current) {
            current.add(d)
            prefs(context).edit().putString(KEY_CUSTOM_DOMAINS, current.joinToString("\n")).apply()
            // Copy-on-write update
            activeDomains = HashSet(activeDomains).apply { add(d) }
        }
    }

    fun removeCustomDomain(context: Context, domain: String) {
        val current = getCustomDomains(context).toMutableList()
        val d = domain.lowercase().trim()
        current.remove(d)
        prefs(context).edit().putString(KEY_CUSTOM_DOMAINS, current.joinToString("\n")).apply()
        activeDomains = HashSet(activeDomains).apply { remove(d) }
    }

    // --- Whitelist ---

    fun getWhitelistedDomains(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_WHITELISTED, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    suspend fun addWhitelistedDomain(context: Context, domain: String) {
        val current = getWhitelistedDomains(context).toMutableList()
        val d = domain.lowercase().trim()
        if (d.isNotEmpty() && d !in current) {
            current.add(d)
            prefs(context).edit().putString(KEY_WHITELISTED, current.joinToString("\n")).apply()
            activeDomains = HashSet(activeDomains).apply { remove(d) }
        }
    }

    suspend fun removeWhitelistedDomain(context: Context, domain: String) {
        val current = getWhitelistedDomains(context).toMutableList()
        val d = domain.lowercase().trim()
        current.remove(d)
        prefs(context).edit().putString(KEY_WHITELISTED, current.joinToString("\n")).apply()
        reloadAsync(context)
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

    fun isSourceDownloaded(context: Context, sourceId: String): Boolean {
        val file = File(context.filesDir, "$DOWNLOADED_DIR/$sourceId.txt")
        return file.exists()
    }

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
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.requestMethod = "GET"

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
                                "hosts" -> parseHostsLine(line)
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

    private fun parseHostsLine(line: String): String? = parseDomainLine(line)

    private fun parseAdblockLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith('!') || trimmed.startsWith('[') || trimmed.isEmpty()) return null
        if (trimmed.startsWith("||") && trimmed.endsWith("^")) {
            val domain = trimmed.removePrefix("||").removeSuffix("^").lowercase()
            return if (domain.contains('.') && !domain.contains('/') && !domain.contains('*')) domain else null
        }
        return null
    }
}
