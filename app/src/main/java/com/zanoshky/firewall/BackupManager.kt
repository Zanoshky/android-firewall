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
 * In scope, and why:
 *  - How each app is handled (the `rules` table). The whole point of the feature.
 *  - The user's own domain rules, both lists.
 *  - Tracker blocking on or off, encrypted lookups on or off and the provider.
 *  - Which tracker lists were downloaded, by id. The files themselves run to
 *    hundreds of thousands of lines, so restore fetches them again.
 *
 * Deliberately out of scope:
 *  - The App Lock passcode. A backup file is meant to be copied around; it must
 *    never carry a credential, and restoring someone else's file must not change
 *    the passcode on this phone.
 *  - Counters and the activity log. They are a record of what happened, not
 *    configuration, and the running service does read-modify-write on the same
 *    counter keys on its own timer.
 *  - The master switch. That is device state, and turning the tunnel on needs
 *    system consent that only an Activity can ask for.
 *
 * Restore replaces rather than merges: an app the file does not mention ends up
 * with no row, which means blocked, and that is the safe direction to be wrong in.
 *
 * Schema 2 replaced the pair of "allowed on Wi-Fi" and "allowed on mobile" flags
 * with one mode. A schema 1 file still restores: an app allowed on either network
 * becomes Filtered.
 */
object BackupManager {

    const val SCHEMA_VERSION = 2
    const val MIME_TYPE = "application/json"

    private const val BLOCKLIST_PREFS = "blocklist_prefs"
    private const val KEY_BLOCKLIST_ENABLED = "blocklist_enabled"

    private const val DOH_PREFS = "doh_prefs"
    private const val KEY_DOH_ENABLED = "doh_enabled"
    private const val KEY_DOH_PROVIDER = "doh_provider"

    data class BackupSummary(val rules: Int, val domains: Int, val sources: Int)

    data class RestoreSummary(
        val rules: Int,
        val domains: Int,
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
                val blocked = DomainRules.blockedDomains(context)
                val allowed = DomainRules.allowedDomains(context)
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
                    put("blockedDomains", JSONArray(blocked))
                    put("allowedDomains", JSONArray(allowed))
                    put("blocklistSources", JSONArray(sourceIds))
                    put("rules", JSONArray().apply {
                        rules.forEach { rule ->
                            put(JSONObject().apply {
                                put("packageName", rule.packageName)
                                put("mode", rule.mode)
                            })
                        }
                    })
                }

                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: return@withContext Result.failure(Exception("Could not open the selected file"))
                stream.use { it.write(root.toString(2).toByteArray()) }

                Result.success(
                    BackupSummary(rules.size, blocked.size + allowed.size, sourceIds.size)
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --- Restore ---

    /**
     * Parsed and validated file contents. Building this touches no stored state,
     * so a malformed file fails before anything has been overwritten.
     */
    private data class ParsedBackup(
        val rules: List<AppRule>,
        val blockedDomains: List<String>,
        val allowedDomains: List<String>,
        val sourceIds: List<String>,
        val trackerBlockingEnabled: Boolean,
        val dohEnabled: Boolean,
        val dohProvider: String
    )

    suspend fun restore(
        context: Context,
        uri: Uri,
        onProgress: suspend (String) -> Unit = {}
    ): Result<RestoreSummary> = withContext(Dispatchers.IO) {
        try {
            onProgress("Reading the file")

            val text = context.contentResolver.openInputStream(uri)?.use {
                it.reader().readText()
            } ?: return@withContext Result.failure(Exception("Could not open the selected file"))

            val parsed = parse(text).getOrElse { return@withContext Result.failure(it) }

            onProgress("Applying settings")

            context.getSharedPreferences(BLOCKLIST_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_BLOCKLIST_ENABLED, parsed.trackerBlockingEnabled)
                .apply()

            context.getSharedPreferences(DOH_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DOH_ENABLED, parsed.dohEnabled)
                .putString(KEY_DOH_PROVIDER, parsed.dohProvider)
                .apply()

            DomainRules.replaceAll(context, parsed.blockedDomains, parsed.allowedDomains)

            // Replace the rules in one transaction, so a crash part way through
            // cannot leave an empty table behind.
            val db = RuleDatabase.get(context)
            db.withTransaction {
                db.ruleDao().deleteAll()
                if (parsed.rules.isNotEmpty()) db.ruleDao().upsertAll(parsed.rules)
            }

            // These cache their preferences in memory at process start and are
            // shared with the running service, so they have to be told to re-read.
            BlocklistManager.init(context)
            DohResolver.init(context)
            BlocklistManager.reloadAsync(context)
            RuleStore.adoptAfterRestore(context)

            val sources = restoreSources(context, parsed.sourceIds, onProgress)

            Result.success(
                RestoreSummary(
                    rules = parsed.rules.size,
                    domains = parsed.blockedDomains.size + parsed.allowedDomains.size,
                    sourcesDownloaded = sources.first,
                    sourcesFailed = sources.second
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

        // Replace semantics: drop lists the backup did not have.
        known.keys
            .filter { it !in wanted && BlocklistManager.isSourceDownloaded(context, it) }
            .forEach { BlocklistManager.deleteSource(context, it) }

        val missing = wanted.filterNot { BlocklistManager.isSourceDownloaded(context, it) }
        var downloaded = 0
        var failed = 0

        missing.forEachIndexed { index, id ->
            val source = known[id] ?: return@forEachIndexed
            onProgress("Downloading ${source.name}, ${index + 1} of ${missing.size}")
            if (BlocklistManager.downloadSource(context, source).isSuccess) downloaded++ else failed++
        }
        return downloaded to failed
    }

    private fun parse(text: String): Result<ParsedBackup> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Result.failure(Exception("Not a Firewall backup file"))
        }

        val schema = root.optInt("schema", -1)
        if (schema < 1) return Result.failure(Exception("Not a Firewall backup file"))
        if (schema > SCHEMA_VERSION) {
            return Result.failure(Exception("This backup was made by a newer version of Firewall"))
        }

        val rules = mutableListOf<AppRule>()
        val seen = HashSet<String>()
        val rulesArray = root.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rulesArray.length()) {
            val obj = rulesArray.optJSONObject(i) ?: continue
            val pkg = obj.optString("packageName").trim()
            // Skip junk rather than abort: one malformed or uninstalled entry is
            // harmless, and rules for missing packages are purged anyway.
            if (pkg.isEmpty() || !seen.add(pkg)) continue

            val mode = when {
                obj.has("mode") -> obj.optInt("mode", AppMode.BLOCKED)
                // Schema 1: allowed on either network meant the app was let out.
                obj.optBoolean("allowWifi", false) || obj.optBoolean("allowMobile", false) ->
                    AppMode.FILTERED
                else -> AppMode.BLOCKED
            }
            if (mode !in AppMode.BLOCKED..AppMode.BYPASS) continue
            rules.add(AppRule(pkg, mode))
        }

        val provider = root.optString("dohProvider", "cloudflare")
            .takeIf { DohResolver.providers.containsKey(it) } ?: "cloudflare"

        // Schema 1 called these custom and whitelisted.
        val blocked = domainList(
            root.optJSONArray("blockedDomains") ?: root.optJSONArray("customBlockedDomains")
        )
        val allowed = domainList(
            root.optJSONArray("allowedDomains") ?: root.optJSONArray("whitelistedDomains")
        )

        return Result.success(
            ParsedBackup(
                rules = rules,
                blockedDomains = blocked,
                allowedDomains = allowed,
                sourceIds = stringList(root.optJSONArray("blocklistSources")),
                trackerBlockingEnabled = root.optBoolean("trackerBlockingEnabled", false),
                dohEnabled = root.optBoolean("dohEnabled", false),
                dohProvider = provider
            )
        )
    }

    private fun domainList(array: JSONArray?): List<String> =
        stringList(array).mapNotNull { DomainRules.normalise(it) }.distinct()

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
