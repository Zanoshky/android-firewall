package com.zanoshky.firewall

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Parses raw IP packets from the VPN tunnel and determines
 * whether to allow or drop them based on per-app rules.
 *
 * The VPN builder uses per-app filtering (addAllowedApplication / addDisallowedApplication)
 * so most filtering happens at the VPN config level. This class provides
 * utilities for packet inspection if needed.
 */
object PacketFilter {

    /** Extract IP version from raw packet */
    fun ipVersion(packet: ByteBuffer): Int = (packet.get(0).toInt() shr 4) and 0xF

    /** Extract protocol number (6=TCP, 17=UDP) from IPv4 packet */
    fun protocol(packet: ByteBuffer): Int = packet.get(9).toInt() and 0xFF

    /** Extract destination IP from IPv4 packet */
    fun destinationIp(packet: ByteBuffer): InetAddress {
        val addr = ByteArray(4)
        packet.position(16)
        packet.get(addr)
        packet.position(0)
        return InetAddress.getByAddress(addr)
    }

    /** Extract destination port from TCP/UDP IPv4 packet */
    fun destinationPort(packet: ByteBuffer): Int {
        val headerLen = (packet.get(0).toInt() and 0xF) * 4
        return packet.getShort(headerLen + 2).toInt() and 0xFFFF
    }

    /** Check if device is currently on Wi-Fi */
    fun isOnWifi(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
