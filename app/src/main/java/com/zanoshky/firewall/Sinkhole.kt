package com.zanoshky.firewall

/**
 * Addresses handed out in place of a blocked domain.
 *
 * A blocked lookup could simply be refused, and for an app that is blocked
 * outright that is what happens. For a blocked *domain* it is better to answer
 * with an address that leads nowhere: the app connects to it, the tunnel is the
 * only thing that route reaches, and the connection is refused on the spot. That
 * gives the firewall a second place to stop the traffic when an app ignores a
 * refusal and retries, and it makes the block visible in the log as a real
 * connection attempt rather than only as a lookup.
 *
 * The range is 198.18.0.0/16, reserved by RFC 2544 for benchmarking and never
 * routed on the internet, so claiming it cannot take a real destination away
 * from the user the way a 10.x or 192.168.x range could.
 */
object Sinkhole {

    const val NETWORK = "198.18.0.0"
    const val PREFIX_LENGTH = 16

    private const val MAX_ENTRIES = 16_384

    private val byDomain = HashMap<String, Int>()
    private val byAddress = HashMap<Int, String>()
    private var next = 2

    fun isSinkhole(address: ByteArray): Boolean =
        address.size == 4 && (address[0].toInt() and 0xFF) == 198 && (address[1].toInt() and 0xFF) == 18

    /** A stable address for [domain] for as long as the service lives. */
    @Synchronized
    fun addressFor(domain: String): ByteArray {
        val existing = byDomain[domain]
        if (existing != null) return encode(existing)

        if (byDomain.size >= MAX_ENTRIES) {
            // Start over rather than grow without bound. The only cost is that an
            // older block loses its name in the log; it is still blocked.
            byDomain.clear()
            byAddress.clear()
            next = 2
        }

        val host = next
        // Stop short of 198.18.255.x, which is where the tunnel's own addresses live.
        next = if (next >= 0xFEFE) 2 else next + 1
        byDomain[domain] = host
        byAddress[host] = domain
        return encode(host)
    }

    /** The domain an address was handed out for, for logging a refused connection. */
    @Synchronized
    fun domainFor(address: ByteArray): String? {
        if (!isSinkhole(address)) return null
        val host = ((address[2].toInt() and 0xFF) shl 8) or (address[3].toInt() and 0xFF)
        return byAddress[host]
    }

    @Synchronized
    fun clear() {
        byDomain.clear()
        byAddress.clear()
        next = 2
    }

    private fun encode(host: Int) = byteArrayOf(
        198.toByte(), 18.toByte(),
        ((host shr 8) and 0xFF).toByte(),
        (host and 0xFF).toByte()
    )
}
