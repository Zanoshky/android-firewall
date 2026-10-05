package com.zanoshky.firewall

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

/**
 * Android's Private DNS setting, and what the firewall does about it.
 *
 * The setting has three positions, and only one of them is a problem:
 *  - Off: lookups go to the tunnel's resolver in plain text. Fine.
 *  - Automatic: Android offers DNS over TLS to whatever resolver the network
 *    names. Inside the tunnel that is ours, which refuses port 853, so Android
 *    falls back to plain lookups at once and the firewall sees every one. Fine.
 *  - A provider hostname (strict): Android sends every lookup over TLS to that
 *    provider and never asks the tunnel at all. Filtering stops, and if the
 *    provider sits on an address the tunnel claims, lookups can fail outright.
 *
 * So the only thing to fix is strict mode, and the fix is to move it to
 * Automatic while the firewall runs and put it back when the firewall stops.
 * Writing that setting needs WRITE_SECURE_SETTINGS, which no app can ask for at
 * runtime; it is granted once over adb. Without it the app explains the problem
 * and opens the right settings screen.
 *
 * The user picked strict mode because they wanted encrypted lookups, so taking
 * it over must not quietly downgrade them to plain text: if DNS over HTTPS is
 * off, it is switched on with the same provider where there is one, and put
 * back afterwards along with the setting.
 */
object PrivateDns {

    private const val MODE = "private_dns_mode"
    private const val SPECIFIER = "private_dns_specifier"
    private const val MODE_AUTOMATIC = "opportunistic"
    private const val MODE_STRICT = "hostname"

    private const val PREFS_NAME = "private_dns_prefs"
    private const val KEY_TAKEN = "taken_over"
    private const val KEY_ORIGINAL_MODE = "original_mode"
    private const val KEY_ORIGINAL_SPECIFIER = "original_specifier"
    private const val KEY_DOH_ENABLED_BY_US = "doh_enabled_by_us"
    private const val KEY_DOH_PREVIOUS_PROVIDER = "doh_previous_provider"

    /** What the user pastes into a terminal to let the firewall handle this itself. */
    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    /** The DNS over TLS hostnames of the providers the firewall also speaks DoH to. */
    private val DOT_TO_DOH = mapOf(
        "dns.google" to "google",
        "dns.google.com" to "google",
        "one.one.one.one" to "cloudflare",
        "1dot1dot1dot1.cloudflare-dns.com" to "cloudflare",
        "cloudflare-dns.com" to "cloudflare",
        "dns.quad9.net" to "quad9",
        "dns9.quad9.net" to "quad9"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun canManage(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True while the firewall has the setting moved to Automatic on the user's behalf. */
    fun isTakenOver(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_TAKEN, false) } catch (_: Exception) { false }

    /** The provider the user had set, while the firewall stands in for it. */
    fun takenOverHost(context: Context): String? {
        if (!isTakenOver(context)) return null
        return try { prefs(context).getString(KEY_ORIGINAL_SPECIFIER, "") ?: "" }
        catch (_: Exception) { "" }
    }

    /**
     * The provider hostname when Private DNS is in strict mode, an empty string
     * when it is strict but the name cannot be read, and null when it is off or
     * Automatic, neither of which takes lookups away from the tunnel.
     */
    fun strictHost(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val mode = readSetting(context, MODE)
        if (mode != null && mode != MODE_STRICT) return null

        // The setting can be unreadable to apps; the networks report it either way.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val name = cm.getLinkProperties(network)?.privateDnsServerName
                if (!name.isNullOrEmpty()) return name
            }
        } catch (_: Exception) {}

        if (mode == MODE_STRICT) return readSetting(context, SPECIFIER) ?: ""
        return null
    }

    /**
     * Move strict Private DNS to Automatic for as long as the firewall runs.
     * Returns true when the setting is no longer in the firewall's way.
     */
    fun takeOver(context: Context): Boolean {
        if (!canManage(context)) return false
        val host = strictHost(context) ?: return true
        val resolver = context.contentResolver
        val p = prefs(context)

        // A second call, after a rebuild or a crash, must not overwrite what the
        // user had with what we set.
        if (!p.getBoolean(KEY_TAKEN, false)) {
            p.edit()
                .putString(KEY_ORIGINAL_MODE, MODE_STRICT)
                .putString(KEY_ORIGINAL_SPECIFIER, readSetting(context, SPECIFIER) ?: host)
                .commit()
        }

        val written = try {
            Settings.Global.putString(resolver, MODE, MODE_AUTOMATIC)
        } catch (_: Exception) { false }
        if (!written) return false

        val edit = p.edit().putBoolean(KEY_TAKEN, true)
        if (!DohResolver.isEnabled && !p.getBoolean(KEY_DOH_ENABLED_BY_US, false)) {
            edit.putBoolean(KEY_DOH_ENABLED_BY_US, true)
                .putString(KEY_DOH_PREVIOUS_PROVIDER, DohResolver.provider)
            DOT_TO_DOH[host.lowercase().trimEnd('.')]?.let { DohResolver.setProvider(context, it) }
            DohResolver.setEnabled(context, true)
        }
        edit.commit()
        return true
    }

    /**
     * Put back what [takeOver] changed. If the user moved the setting themselves
     * while the firewall ran, their choice stands and only our bookkeeping goes.
     */
    fun restore(context: Context) {
        val p = try { prefs(context) } catch (_: Exception) { return }
        if (!p.getBoolean(KEY_TAKEN, false)) return

        if (canManage(context)) {
            val current = readSetting(context, MODE)
            if (current == null || current == MODE_AUTOMATIC) {
                val resolver = context.contentResolver
                try {
                    p.getString(KEY_ORIGINAL_SPECIFIER, null)?.let {
                        if (it.isNotEmpty() && readSetting(context, SPECIFIER) != it) {
                            Settings.Global.putString(resolver, SPECIFIER, it)
                        }
                    }
                    Settings.Global.putString(
                        resolver, MODE, p.getString(KEY_ORIGINAL_MODE, MODE_STRICT) ?: MODE_STRICT
                    )
                } catch (_: Exception) {}
            }
        }

        if (p.getBoolean(KEY_DOH_ENABLED_BY_US, false)) {
            DohResolver.setEnabled(context, false)
            p.getString(KEY_DOH_PREVIOUS_PROVIDER, null)?.let { DohResolver.setProvider(context, it) }
        }

        p.edit().clear().commit()
    }

    private fun readSetting(context: Context, key: String): String? = try {
        Settings.Global.getString(context.contentResolver, key)
    } catch (_: Exception) {
        // Newer releases may refuse hidden settings to apps; callers fall back.
        null
    }
}
