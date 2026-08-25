package com.zanoshky.firewall

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-JSON export and import of everything the user configured.
 *
 * What is in scope, and why:
 *  - Per-app rules (the `rules` table). The whole point of the feature.
 *  - Custom blocked domains and whitelisted domains ("blocklist_prefs").
 *  - Tracker blocking on/off, DoH on/off and provider ("doh_prefs").
 *  - Which online blocklist sources were downloaded, by id. The downloaded
 *    files themselves are hundreds of thousands of lines, so the backup stores
 *    ids and restore re-downloads them.
 *
 * What is deliberately excluded:
 *  - The App Lock PIN. A backup file is meant to be copied around; it must never
 *    carry credentials, and restoring someone else's file must not change your PIN.
 *  - Traffic counters, connection logs and per-app stats. They are telemetry, not
 *    configuration, and the running VPN service does read-modify-write on the same
 *    counter keys on its flush timer - writing them from here would race that flush.
 *  - The master firewall on/off switch. That is device state, and turning the VPN
 *    on requires system consent that only an Activity can request.
 *
 * Restore is replace, not merge: rules absent from the file end up with no row,
 * which means fully blocked. That is the app's safe default.
 */
object BackupManager {

    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/json"

    private const val BLOCKLIST_PREFS = "blocklist_prefs"
    private const val KEY_BLOCKLIST_ENABLED = "blocklist_enabled"
    private const val KEY_CUSTOM_DOMAINS = "custom_blocked_domains"
    private const val KEY_WHITELISTED = "whitelisted_domains"

    private const val DOH_PREFS = "doh_prefs"
    private const val KEY_DOH_ENABLED = "doh_enabled"
    private const val KEY_DOH_PROVIDER = "doh_provider"

    data class BackupSummary(
        val rules: Int,
        val customDomains: Int,
        val whitelistedDomains: Int,
        val sources: Int
    )

    data class RestoreSummary(
        val rules: Int,
        val customDomains: Int,
        val whitelistedDomains: Int,
        val sourcesDownloaded: Int,
        val sourcesFailed: Int
    )

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "firewall-backup-$stamp.json"
    }

    // --- Export ---

    suspend fun export(context: Context, uri: Uri): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            try {
                val rules = RuleDatabase.get(context).ruleDao().getAll()
                val custom = BlocklistManager.getCustomDomains(context)
                val whitelist = BlocklistManager.getWhitelistedDomains(context)
                val sourceIds = BlocklistManager.getSources(context)
                    .map { it.id }
                    .filter { BlocklistManager.isSourceDownloaded(context, it) }

                val blocklistPrefs = context.getSharedPreferences(BLOCKLIST_PREFS, Context.MODE_PRIVATE)
                val dohPrefs = context.getSharedPreferences(DOH_PREFS, Context.MODE_PRIVATE)

                val root = JSONObject().apply {
                    put("schema", SCHEMA_VERSION)
                    put("app", context.packageName)
                    put("appVersion", appVersion(context))
                    put("exportedAt", System.currentTimeMillis())
                    put("trackerBlockingEnabled", blocklistPrefs.getBoolean(KEY_BLOCKLIST_ENABLED, false))
                    put("dohEnabled", dohPrefs.getBoolean(KEY_DOH_ENABLED, false))
                    put("dohProvider", dohPrefs.getString(KEY_DOH_PROVIDER, "cloudflare"))
                    put("customBlockedDomains", JSONArray(custom))
                    put("whitelistedDomains", JSONArray(whitelist))
                    put("blocklistSources", JSONArray(sourceIds))
                    put("rules", JSONArray().apply {
                        rules.forEach { rule ->
                            put(JSONObject().apply {
                                put("packageName", rule.packageName)
                                put("allowWifi", rule.allowWifi)
                                put("allowMobile", rule.allowMobile)
                            })
                        }
                    })
                }

                val bytes = root.toString(2).toByteArray()
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext Result.failure(Exception("Could not open the selected file"))
                stream.use { it.write(bytes) }

                Result.success(
                    BackupSummary(rules.size, custom.size, whitelist.size, sourceIds.size)
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- Restore ---

    /**
     * Parsed and validated file contents. Building this cannot touch any stored
     * state, so a malformed file fails before anything is overwritten.
     */
    private data class ParsedBackup(
        val rules: List<AppRule>,
        val customDomains: List<String>,
        val whitelistedDomains: List<String>,
        val sourceIds: List<String>,
        val trackerBlockingEnabled: Boolean,
        val dohEnabled: Boolean,
        val dohProvider: String
    )

    /**
     * Read [uri], validate it, then commit. [onProgress] is invoked on the caller's
     * dispatcher context with short human-readable status lines; blocklist
     * re-downloads dominate the runtime and are reported one by one.
     */
    suspend fun restore(
        context: Context,
        uri: Uri,
        onProgress: suspend (String) -> Unit = {}
    ): Result<RestoreSummary> = withContext(Dispatchers.IO) {
        try {
            onProgress("Reading backup file...")

            val text = context.contentResolver.openInputStream(uri)?.use {
                it.reader().readText()
            } ?: return@withContext Result.failure(Exception("Could not open the selected file"))

            val parsed = parse(text).getOrElse { return@withContext Result.failure(it) }

            onProgress("Applying settings...")

            // Preferences first, so the in-memory reload below sees final values.
            context.getSharedPreferences(BLOCKLIST_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BLOCKLIST_ENABLED, parsed.trackerBlockingEnabled)
                .putString(KEY_CUSTOM_DOMAINS, parsed.customDomains.joinToString("\n"))
                .putString(KEY_WHITELISTED, parsed.whitelistedDomains.joinToString("\n"))
                .apply()

            context.getSharedPreferences(DOH_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DOH_ENABLED, parsed.dohEnabled)
                .putString(KEY_DOH_PROVIDER, parsed.dohProvider)
                .apply()

            // Replace the rule set atomically so a crash mid-restore cannot leave
            // an empty rules table behind.
            val db = RuleDatabase.get(context)
            db.withTransaction {
                db.ruleDao().deleteAll()
                if (parsed.rules.isNotEmpty()) db.ruleDao().upsertAll(parsed.rules)
            }

            // BlocklistManager and DohResolver cache their prefs in @Volatile fields
            // at process start and are shared with the VPN service in this process,
            // so they have to be re-read explicitly.
            BlocklistManager.init(context)
            DohResolver.init(context)
            BlocklistManager.reloadAsync(context)

            // Apply the restored per-app rules to the live tunnel.
            TunnelControl.requestRebuild(context)

            val sourceResult = restoreSources(context, parsed.sourceIds, onProgress)

            Result.success(
                RestoreSummary(
                    rules = parsed.rules.size,
                    customDomains = parsed.customDomains.size,
                    whitelistedDomains = parsed.whitelistedDomains.size,
                    sourcesDownloaded = sourceResult.first,
                    sourcesFailed = sourceResult.second
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Returns downloaded count to failed count. */
    private suspend fun restoreSources(
        context: Context,
        wantedIds: List<String>,
        onProgress: suspend (String) -> Unit
    ): Pair<Int, Int> {
        val known = BlocklistManager.getSources(context).associateBy { it.id }
        val wanted = wantedIds.filter { known.containsKey(it) }

        // Replace semantics: drop sources the backup did not have.
        known.keys
            .filter { it !in wanted && BlocklistManager.isSourceDownloaded(context, it) }
            .forEach { BlocklistManager.deleteSource(context, it) }

        val missing = wanted.filterNot { BlocklistManager.isSourceDownloaded(context, it) }
        var downloaded = 0
        var failed = 0

        missing.forEachIndexed { index, id ->
            val source = known[id] ?: return@forEachIndexed
            onProgress("Downloading ${source.name} (${index + 1} of ${missing.size})...")
            if (BlocklistManager.downloadSource(context, source).isSuccess) downloaded++ else failed++
        }
        return downloaded to failed
    }

    private fun parse(text: String): Result<ParsedBackup> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Result.failure(Exception("Not a valid Firewall backup file"))
        }

        val schema = root.optInt("schema", -1)
        if (schema < 1) {
            return Result.failure(Exception("Not a valid Firewall backup file"))
        }
        if (schema > SCHEMA_VERSION) {
            return Result.failure(
                Exception("This backup was made by a newer version of Firewall")
            )
        }

        val rules = mutableListOf<AppRule>()
        val seen = HashSet<String>()
        val rulesArray = root.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rulesArray.length()) {
            val obj = rulesArray.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName").trim()
            // Skip junk rather than aborting: an uninstalled or malformed entry is
            // harmless, and the app already purges rules for missing packages.
            if (pkg.isEmpty() || !seen.add(pkg)) continue
            rules.add(
                AppRule(
                    packageName = pkg,
                    allowWifi = obj.optBoolean("allowWifi", false),
                    allowMobile = obj.optBoolean("allowMobile", false)
                )
            )
        }

        val provider = root.optString("dohProvider", "cloudflare")
            .takeIf { DohResolver.providers.containsKey(it) } ?: "cloudflare"

        return Result.success(
            ParsedBackup(
                rules = rules,
                customDomains = domainList(root.optJSONArray("customBlockedDomains")),
                whitelistedDomains = domainList(root.optJSONArray("whitelistedDomains")),
                sourceIds = stringList(root.optJSONArray("blocklistSources")),
                trackerBlockingEnabled = root.optBoolean("trackerBlockingEnabled", false),
                dohEnabled = root.optBoolean("dohEnabled", false),
                dohProvider = provider
            )
        )
    }

    /**
     * Domains are stored newline-joined, so anything containing whitespace would
     * corrupt neighbouring entries on the next read. Filter, do not escape.
     */
    private fun domainList(array: JSONArray?): List<String> =
        stringList(array)
            .map { it.lowercase() }
            .filter { it.contains('.') && it.none { c -> c.isWhitespace() } }
            .distinct()

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
