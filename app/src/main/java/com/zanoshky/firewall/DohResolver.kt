package com.zanoshky.firewall

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * DNS-over-HTTPS resolver. Intercepts DNS queries from the VPN tunnel,
 * resolves them via a DoH provider (Cloudflare/Google/Quad9), and
 * builds a raw DNS response packet to write back into the tunnel.
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

    fun getProviderUrl(): String = providers[provider] ?: providers["cloudflare"]!!

    /**
     * Resolve a raw DNS query via DoH (RFC 8484 wire format POST).
     * Returns the raw DNS response bytes, or null on failure.
     */
    fun resolve(dnsQuery: ByteArray): ByteArray? {
        return try {
            val url = URL(getProviderUrl())
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            conn.outputStream.use { it.write(dnsQuery) }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val response = conn.inputStream.use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    baos.write(buf, 0, n)
                }
                baos.toByteArray()
            }
            conn.disconnect()
            response
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a DNS NXDOMAIN response for a blocked domain.
     * Copies the transaction ID and question from the original query.
     */
    fun buildBlockedResponse(dnsQuery: ByteArray): ByteArray {
        val resp = ByteArrayOutputStream()
        // Transaction ID (first 2 bytes from query)
        resp.write(dnsQuery, 0, 2)
        // Flags: QR=1, AA=1, RCODE=3 (NXDOMAIN) = 0x8183
        resp.write(0x81)
        resp.write(0x83)
        // QDCOUNT = 1
        resp.write(0x00)
        resp.write(0x01)
        // ANCOUNT = 0
        resp.write(0x00)
        resp.write(0x00)
        // NSCOUNT = 0
        resp.write(0x00)
        resp.write(0x00)
        // ARCOUNT = 0
        resp.write(0x00)
        resp.write(0x00)
        // Copy question section from original query (starts at byte 12)
        if (dnsQuery.size > 12) {
            var pos = 12
            // Skip domain name labels
            while (pos < dnsQuery.size) {
                val len = dnsQuery[pos].toInt() and 0xFF
                if (len == 0) { pos++; break }
                pos += len + 1
            }
            // Include QTYPE (2) + QCLASS (2)
            val questionEnd = minOf(pos + 4, dnsQuery.size)
            resp.write(dnsQuery, 12, questionEnd - 12)
        }
        return resp.toByteArray()
    }

    /**
     * Wrap a raw DNS response into a full IP+UDP packet to write back to the VPN tunnel.
     * Swaps src/dst IP and ports from the original packet.
     */
    fun wrapDnsResponse(originalPacket: ByteBuffer, originalLength: Int, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = (originalPacket.get(0).toInt() and 0xF) * 4

        // Extract original addresses and ports
        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        originalPacket.position(12); originalPacket.get(srcIp)
        originalPacket.position(16); originalPacket.get(dstIp)
        originalPacket.position(0)

        val srcPort = originalPacket.getShort(ipHeaderLen).toInt() and 0xFFFF
        val dstPort = originalPacket.getShort(ipHeaderLen + 2).toInt() and 0xFFFF

        val udpLen = 8 + dnsResponse.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // IP header
        buf.put((0x45).toByte()) // IPv4, header len 20
        buf.put(0) // TOS
        buf.putShort(totalLen.toShort()) // total length
        buf.putShort(0) // identification
        buf.putShort(0x4000.toShort()) // flags: don't fragment
        buf.put(64) // TTL
        buf.put(17) // protocol: UDP
        buf.putShort(0) // checksum (0 = let OS handle)
        buf.put(dstIp) // src = original dst (we're the "server")
        buf.put(srcIp) // dst = original src

        // IP checksum
        var sum = 0L
        for (i in 0 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv().toShort()
        buf.putShort(10, checksum)

        // UDP header
        buf.position(ipHeaderLen)
        buf.putShort(dstPort.toShort()) // src port = original dst port
        buf.putShort(srcPort.toShort()) // dst port = original src port
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // UDP checksum (optional for IPv4)

        // DNS payload
        buf.put(dnsResponse)

        return packet
    }
}
