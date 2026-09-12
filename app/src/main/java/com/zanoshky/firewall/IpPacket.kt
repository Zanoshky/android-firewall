package com.zanoshky.firewall

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * The small amount of IPv4 the tunnel needs.
 *
 * Only two kinds of packet ever reach us, because the tunnel only claims a route
 * to its own resolver and to the sinkhole range: DNS queries, which we answer,
 * and connections to a domain that was blocked, which we refuse. So this file
 * parses enough of an IPv4 header to tell those apart, and builds exactly two
 * replies: a UDP datagram back to the sender, and a TCP reset.
 */
object IpPacket {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun version(packet: ByteBuffer): Int = (packet.get(0).toInt() shr 4) and 0xF

    fun headerLength(packet: ByteBuffer): Int = (packet.get(0).toInt() and 0xF) * 4

    fun protocol(packet: ByteBuffer): Int = packet.get(9).toInt() and 0xFF

    fun totalLength(packet: ByteBuffer): Int = packet.getShort(2).toInt() and 0xFFFF

    fun sourceAddress(packet: ByteBuffer): ByteArray =
        ByteArray(4).also { for (i in 0 until 4) it[i] = packet.get(12 + i) }

    fun destinationAddress(packet: ByteBuffer): ByteArray =
        ByteArray(4).also { for (i in 0 until 4) it[i] = packet.get(16 + i) }

    fun sourceIp(packet: ByteBuffer): InetAddress = InetAddress.getByAddress(sourceAddress(packet))

    fun destinationIp(packet: ByteBuffer): InetAddress =
        InetAddress.getByAddress(destinationAddress(packet))

    fun sourcePort(packet: ByteBuffer): Int =
        packet.getShort(headerLength(packet)).toInt() and 0xFFFF

    fun destinationPort(packet: ByteBuffer): Int =
        packet.getShort(headerLength(packet) + 2).toInt() and 0xFFFF

    fun ipToString(addr: ByteArray): String =
        "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}." +
            "${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"

    /** The UDP payload of [packet], or null when the packet is too short to hold one. */
    fun udpPayload(packet: ByteBuffer, length: Int): ByteArray? {
        val start = headerLength(packet) + 8
        if (start >= length) return null
        val payload = ByteArray(length - start)
        val saved = packet.position()
        packet.position(start)
        packet.get(payload)
        packet.position(saved)
        return payload
    }

    /**
     * Wrap [payload] as a UDP datagram addressed back to whoever sent [original],
     * with the source and destination swapped so it looks like an answer from the
     * server the app was talking to.
     *
     * The UDP checksum is left at zero, which IPv4 defines as "not computed".
     */
    fun buildUdpResponse(original: ByteBuffer, payload: ByteArray): ByteArray {
        val srcIp = sourceAddress(original)
        val dstIp = destinationAddress(original)
        val srcPort = sourcePort(original)
        val dstPort = destinationPort(original)

        val udpLength = 8 + payload.size
        val total = 20 + udpLength
        val out = ByteArray(total)
        val buf = ByteBuffer.wrap(out)

        buf.put(0x45.toByte())          // IPv4, 20 byte header
        buf.put(0)                       // DSCP
        buf.putShort(total.toShort())
        buf.putShort(0)                  // identification
        buf.putShort(0x4000.toShort())   // don't fragment
        buf.put(64)                      // TTL
        buf.put(PROTO_UDP.toByte())
        buf.putShort(0)                  // header checksum, filled in below
        buf.put(dstIp)                   // we answer as the destination
        buf.put(srcIp)

        buf.putShort(dstPort.toShort())
        buf.putShort(srcPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0)                  // UDP checksum: not computed
        buf.put(payload)

        buf.putShort(10, headerChecksum(out))
        return out
    }

    /**
     * A TCP reset for [original], so an app that tried to reach a blocked domain
     * fails at once instead of waiting out a connect timeout.
     *
     * RFC 793: a segment that carries an ACK is answered with RST and that same
     * acknowledgement number as our sequence; anything else (a plain SYN) is
     * answered with RST and ACK covering everything the sender sent.
     */
    fun buildTcpReset(original: ByteBuffer, length: Int): ByteArray? {
        val ipHeaderLen = headerLength(original)
        if (length < ipHeaderLen + 20) return null

        val srcIp = sourceAddress(original)
        val dstIp = destinationAddress(original)
        val srcPort = sourcePort(original)
        val dstPort = destinationPort(original)

        val seq = original.getInt(ipHeaderLen + 4).toLong() and 0xFFFFFFFFL
        val ack = original.getInt(ipHeaderLen + 8).toLong() and 0xFFFFFFFFL
        val dataOffset = ((original.get(ipHeaderLen + 12).toInt() shr 4) and 0xF) * 4
        val flags = original.get(ipHeaderLen + 13).toInt() and 0xFF
        if (flags and 0x04 != 0) return null // never answer a reset with a reset

        val payloadLen = (length - ipHeaderLen - dataOffset).coerceAtLeast(0)
        val synOrFin = if (flags and 0x01 != 0 || flags and 0x02 != 0) 1 else 0

        val ourSeq: Long
        val ourAck: Long
        val ourFlags: Int
        if (flags and 0x10 != 0) { // ACK present
            ourSeq = ack
            ourAck = 0
            ourFlags = 0x04 // RST
        } else {
            ourSeq = 0
            ourAck = (seq + payloadLen + synOrFin) and 0xFFFFFFFFL
            ourFlags = 0x14 // RST | ACK
        }

        val total = 40
        val out = ByteArray(total)
        val buf = ByteBuffer.wrap(out)

        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(total.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64)
        buf.put(PROTO_TCP.toByte())
        buf.putShort(0)
        buf.put(dstIp)
        buf.put(srcIp)

        buf.putShort(dstPort.toShort())
        buf.putShort(srcPort.toShort())
        buf.putInt(ourSeq.toInt())
        buf.putInt(ourAck.toInt())
        buf.put((5 shl 4).toByte())      // data offset 5 words, no options
        buf.put(ourFlags.toByte())
        buf.putShort(0)                  // window
        buf.putShort(0)                  // checksum, filled in below
        buf.putShort(0)                  // urgent pointer

        buf.putShort(10, headerChecksum(out))
        buf.putShort(36, transportChecksum(out, 20, 20, PROTO_TCP))
        return out
    }

    /** Ones-complement checksum over the 20 byte IPv4 header of [packet]. */
    private fun headerChecksum(packet: ByteArray): Short {
        var sum = 0L
        for (i in 0 until 20 step 2) {
            if (i == 10) continue // the checksum field itself reads as zero
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toShort()
    }

    /**
     * TCP checksum over the pseudo header plus the segment. Unlike UDP's, this one
     * is mandatory, and a reset with a wrong checksum is dropped in silence.
     */
    private fun transportChecksum(
        packet: ByteArray, offset: Int, length: Int, protocol: Int
    ): Short {
        var sum = 0L
        // Pseudo header: source and destination address, protocol, segment length.
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += protocol.toLong()
        sum += length.toLong()

        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8

        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toShort()
    }
}
