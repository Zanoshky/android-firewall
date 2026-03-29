package com.zanoshky.firewall

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.nio.ByteBuffer

object PacketFilter {

    fun ipVersion(packet: ByteBuffer): Int = (packet.get(0).toInt() shr 4) and 0xF

    fun protocol(packet: ByteBuffer): Int = packet.get(9).toInt() and 0xFF

    fun destinationIp(packet: ByteBuffer): InetAddress {
        val addr = ByteArray(4)
        packet.position(16)
        packet.get(addr)
        packet.position(0)
        return InetAddress.getByAddress(addr)
    }

    fun destinationPort(packet: ByteBuffer): Int {
        val headerLen = (packet.get(0).toInt() and 0xF) * 4
        return packet.getShort(headerLen + 2).toInt() and 0xFFFF
    }

    fun isOnWifi(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Extract queried domain name from a DNS query packet (UDP port 53).
     * Returns null if not a valid DNS query or parsing fails.
     *
     * DNS packet structure (after UDP header):
     * - 2 bytes: Transaction ID
     * - 2 bytes: Flags
     * - 2 bytes: Questions count
     * - 2 bytes: Answer RRs
     * - 2 bytes: Authority RRs
     * - 2 bytes: Additional RRs
     * - Question section: name + type(2) + class(2)
     *
     * Name is encoded as length-prefixed labels: 3www6google3com0
     */
    fun extractDnsQueryDomain(packet: ByteBuffer, packetLength: Int): String? {
        try {
            val ipHeaderLen = (packet.get(0).toInt() and 0xF) * 4
            val udpStart = ipHeaderLen
            val dnsStart = udpStart + 8 // UDP header is 8 bytes

            if (packetLength < dnsStart + 12) return null // too short for DNS header

            // Check it's a standard query (QR=0, Opcode=0)
            val flags = packet.getShort(dnsStart + 2).toInt() and 0xFFFF
            if ((flags and 0x8000) != 0) return null // QR bit set = response, not query

            val questions = packet.getShort(dnsStart + 4).toInt() and 0xFFFF
            if (questions < 1) return null

            // Parse the domain name from question section
            var pos = dnsStart + 12
            val sb = StringBuilder()

            while (pos < packetLength) {
                val labelLen = packet.get(pos).toInt() and 0xFF
                if (labelLen == 0) break // end of name
                if (labelLen > 63) return null // compression pointer or invalid

                pos++
                if (pos + labelLen > packetLength) return null

                if (sb.isNotEmpty()) sb.append('.')
                for (i in 0 until labelLen) {
                    sb.append(packet.get(pos + i).toInt().toChar())
                }
                pos += labelLen
            }

            val domain = sb.toString().lowercase()
            return if (domain.contains('.') && domain.length > 3) domain else null
        } catch (_: Exception) {
            return null
        }
    }
}
