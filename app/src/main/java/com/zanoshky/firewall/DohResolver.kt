package com.zanoshky.firewall

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * Where a lookup goes once the firewall has decided to let it through.
 *
 * With DNS over HTTPS on, the query is posted to the chosen provider as RFC 8484
 * wire format, so nobody between the phone and that provider can read which site
 * is being opened. With it off, the query is forwarded to the resolver the
 * network handed out, in plain text, exactly as it would have gone without the
 * firewall.
 *
 * If the encrypted path fails, the query falls back to the plain one rather than
 * leaving the app with no answer at all. A phone that cannot resolve anything
 * looks broken, and a user with a broken phone turns the firewall off.
 */
object DohResolver {

    private const val PREFS_NAME = "doh_prefs"
    private const val KEY_ENABLED = "doh_enabled"
    private const val KEY_PROVIDER = "doh_provider"

    @Volatile var isEnabled: Boolean = false
        private set

    @Volatile var provider: String = "cloudflare"
        private set

    val providers = mapOf(
        "cloudflare" to "https://cloudflare-dns.com/dns-query",
        "google" to "https://dns.google/dns-query",
        "quad9" to "https://dns.quad9.net/dns-query"
    )

    val providerNames = mapOf(
        "cloudflare" to "Cloudflare",
        "google" to "Google",
        "quad9" to "Quad9"
    )

    /** Used when the network offers no resolver of its own. */
    private val FALLBACK_UPSTREAM = listOf("1.1.1.1", "8.8.8.8")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        isEnabled = p.getBoolean(KEY_ENABLED, false)
        provider = p.getString(KEY_PROVIDER, "cloudflare") ?: "cloudflare"
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setProvider(context: Context, id: String) {
        provider = id
        prefs(context).edit().putString(KEY_PROVIDER, id).apply()
    }

    fun getProviderUrl(): String = providers[provider] ?: providers.getValue("cloudflare")

    fun providerLabel(): String = providerNames[provider] ?: "Cloudflare"

    /**
     * Answer [query], counting how it was answered. [upstream] is the resolver
     * list the underlying network handed out, and [protect] keeps our own socket
     * out of the tunnel so a lookup cannot loop back into itself.
     */
    fun resolve(
        query: ByteArray,
        upstream: List<String>,
        protect: (DatagramSocket) -> Unit
    ): ByteArray? {
        if (isEnabled) {
            val encrypted = overHttps(query)
            if (encrypted != null) {
                Stats.dohQueries.incrementAndGet()
                return encrypted
            }
        }
        val plain = overUdp(query, upstream, protect)
        if (plain != null) Stats.plainQueries.incrementAndGet()
        return plain
    }

    private fun overHttps(query: ByteArray): ByteArray? {
        return try {
            val conn = URL(getProviderUrl()).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.outputStream.use { it.write(query) }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val response = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(2048)
                var n = input.read(buf)
                while (n != -1) {
                    out.write(buf, 0, n)
                    n = input.read(buf)
                }
                out.toByteArray()
            }
            conn.disconnect()
            if (response.size >= 12) response else null
        } catch (_: Exception) {
            null
        }
    }

    private fun overUdp(
        query: ByteArray,
        upstream: List<String>,
        protect: (DatagramSocket) -> Unit
    ): ByteArray? {
        val servers = (upstream + FALLBACK_UPSTREAM).distinct().take(3)
        for (server in servers) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 3000
                    val address = InetAddress.getByName(server)
                    socket.send(DatagramPacket(query, query.size, InetSocketAddress(address, 53)))
                    val buffer = ByteArray(4096)
                    val reply = DatagramPacket(buffer, buffer.size)
                    socket.receive(reply)
                    if (reply.length >= 12) {
                        return buffer.copyOf(reply.length)
                    }
                }
            } catch (_: Exception) {
                // Try the next resolver.
            }
        }
        return null
    }
}
