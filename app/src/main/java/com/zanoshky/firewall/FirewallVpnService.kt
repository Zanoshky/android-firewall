package com.zanoshky.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * The tunnel.
 *
 * It claims one small, reserved range of addresses rather than the whole
 * internet: its own resolver, the sinkhole the blocked domains point at, and the
 * handful of public resolvers that apps hardcode. Everything else an app does
 * leaves the phone the way it always did, over the real network, at full speed.
 *
 * That is the whole reason the firewall is cheap to run. Ordinary traffic is
 * never copied into this process, so there is no relaying, no per-packet work
 * while a video plays, and nothing to keep the CPU awake. The only packets that
 * arrive are lookups, which are small and rare, and connections to a domain that
 * was already blocked, which are refused immediately.
 *
 * What that buys, and what it costs:
 *  - Every app inside the tunnel has its lookups filtered, including the browser
 *    you allowed. That is what makes a domain rule mean something.
 *  - An app in [AppMode.BYPASS] is outside the tunnel and is not touched at all.
 *  - An app in [AppMode.BLOCKED] is refused every name it asks for, so it cannot
 *    open anything new. An app that has an address written into it can still try
 *    that one address; blocking the names is what stops the rest.
 */
class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var flushJob: Job? = null

    private val owners = OwnerLookup()
    private val logBuffer = LogBuffer()
    private val tunnelLock = kotlinx.coroutines.sync.Mutex()

    @Volatile private var upstreamDns: List<String> = emptyList()
    @Volatile private var output: FileOutputStream? = null
    private val writeLock = Any()

    companion object {
        const val ACTION_START = "com.zanoshky.firewall.START"
        const val ACTION_STOP = "com.zanoshky.firewall.STOP"
        private const val CHANNEL_ID = "firewall_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FirewallVPN"

        /** Inside the reserved 198.18.0.0/16 range, well clear of the sinkhole hosts. */
        private const val TUN_ADDRESS = "198.18.255.1"
        private const val TUN_DNS = "198.18.255.2"

        /** DNS over TLS. Android probes our resolver on this port and we cannot serve it. */
        private const val PORT_DOT = 853

        /**
         * Resolvers apps reach for directly instead of asking the system. Claiming
         * them means a lookup sent straight to 8.8.8.8 is filtered like any other
         * instead of walking past the firewall.
         */
        private val HIJACKED_RESOLVERS = setOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "94.140.14.14", "94.140.15.15"
        )

        /** True while the tunnel is established and reading packets. */
        @Volatile var isRunning: Boolean = false

        /** Set when the system's own Private DNS would take lookups away from us. */
        @Volatile var privateDnsActive: Boolean = false

        /** Apps currently inside the tunnel, for the notification and the hero card. */
        @Volatile var appsFiltered: Int = 0
        @Volatile var appsBlocked: Int = 0
        @Volatile var appsBypassed: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BlocklistManager.init(this)
        DohResolver.init(this)
        DomainRules.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                rebuildTunnel()
                START_STICKY
            }
        }
    }

    /**
     * Only the set of apps that live outside the tunnel is fixed when the tunnel
     * is built, so this runs when that set changes and at startup. Everything
     * else, per-app modes and domain rules alike, is read live on each lookup.
     */
    private fun rebuildTunnel() {
        readJob?.cancel()
        readJob = scope.launch { tunnelLock.withLock { runTunnel() } }
    }

    private suspend fun runTunnel() {
        closeInterface()

        RuleStore.reload(this)
        DomainRules.init(this)
        BlocklistManager.reloadSync(this)

        val logDao = RuleDatabase.get(this).connectionLogDao()
        logDao.deleteOlderThan(System.currentTimeMillis() - 3 * 86400_000L)
        logDao.trimTo(20_000)

        refreshUpstreamDns()

        val fd = establish() ?: run {
            Log.e(TAG, "could not establish the tunnel")
            return
        }
        vpnInterface = fd
        isRunning = true
        if (Stats.sessionStart == 0L) Stats.startSession(this)

        registerNetworkCallback()
        startFlushLoop()

        val input = FileInputStream(fd.fileDescriptor)
        val out = FileOutputStream(fd.fileDescriptor)
        output = out

        val raw = ByteArray(4096)
        val buffer = ByteBuffer.wrap(raw)

        try {
            while (currentCoroutineContext().isActive) {
                val length = input.read(raw)
                if (length < 0) break
                if (length < 20) continue
                buffer.limit(length)
                buffer.position(0)
                try {
                    handlePacket(buffer, length)
                } catch (e: Exception) {
                    Log.w(TAG, "packet dropped: ${e.message}")
                }
                buffer.clear()
            }
        } catch (_: Exception) {
            // The descriptor was closed under us; the loop is done.
        } finally {
            logBuffer.flush(this, scope)
        }
    }

    private fun establish(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Firewall")
            .setMtu(1500)
            .setBlocking(true)
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(TUN_DNS)
            .addRoute(Sinkhole.NETWORK, Sinkhole.PREFIX_LENGTH)

        for (resolver in HIJACKED_RESOLVERS) {
            try { builder.addRoute(resolver, 32) } catch (_: Exception) {}
        }

        // The firewall itself must stay outside, or resolving a name for the DoH
        // provider would arrive back here as a lookup to answer.
        try { builder.addDisallowedApplication(packageName) }
        catch (_: PackageManager.NameNotFoundException) {}

        excludeBypassedApps(builder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        for (attempt in 1..3) {
            val fd = try { builder.establish() } catch (e: Exception) {
                Log.e(TAG, "establish threw", e); null
            }
            if (fd != null) return fd
            Log.e(TAG, "establish() returned null (attempt $attempt)")
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    /**
     * Take the apps set to Bypass out of the tunnel.
     *
     * Android grants network access per UID, not per package, and several
     * packages can share one. Excluding a package whose UID it shares with an app
     * that should be filtered would quietly take that app out too, so a UID only
     * leaves the tunnel when every package in it is set to Bypass.
     */
    private fun excludeBypassedApps(builder: Builder) {
        val apps = try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) { emptyList() }

        var filtered = 0
        var blocked = 0
        var bypassed = 0
        val byUid = apps.groupBy { it.uid }

        for (app in apps) {
            when (RuleStore.modeOf(app.packageName)) {
                AppMode.BYPASS -> bypassed++
                AppMode.FILTERED -> filtered++
                else -> blocked++
            }
        }

        for (app in apps) {
            if (RuleStore.modeOf(app.packageName) != AppMode.BYPASS) continue
            val mates = byUid[app.uid] ?: continue
            if (mates.all { RuleStore.modeOf(it.packageName) == AppMode.BYPASS }) {
                try { builder.addDisallowedApplication(app.packageName) }
                catch (_: PackageManager.NameNotFoundException) {}
            }
        }

        appsFiltered = filtered
        appsBlocked = blocked
        appsBypassed = bypassed
    }

    // --- Packet handling ---

    private fun handlePacket(packet: ByteBuffer, length: Int) {
        if (IpPacket.version(packet) != 4) return
        val protocol = IpPacket.protocol(packet)
        val destination = IpPacket.destinationAddress(packet)

        if (protocol == IpPacket.PROTO_UDP && length >= IpPacket.headerLength(packet) + 8 &&
            IpPacket.destinationPort(packet) == 53
        ) {
            handleLookup(packet, length, destination)
            return
        }

        // Android's own resolver opens a connection to every DNS server a network
        // offers to see whether it speaks DNS over TLS, and ours does not. Refusing
        // is the right answer and makes it fall back to plain lookups at once, but
        // it is housekeeping, not something the user blocked, so it is not counted
        // and not written to the log.
        if (isTunnelProbe(packet, protocol, destination)) {
            if (protocol == IpPacket.PROTO_TCP) {
                IpPacket.buildTcpReset(packet, length)?.let { writeToTunnel(it) }
            }
            return
        }

        // Anything else that reaches us is addressed to something we claimed on
        // purpose: a sinkholed domain, or a resolver being used for something
        // other than a lookup. Refuse it rather than letting it hang.
        Stats.connectionsRefused.incrementAndGet()
        val owner = owners.resolve(this, protocol, packet, length)
        record(
            ConnectionLog(
                packageName = owner.packageName,
                appName = owner.label,
                domain = Sinkhole.domainFor(destination) ?: "",
                destIp = IpPacket.ipToString(destination),
                destPort = if (protocol == IpPacket.PROTO_TCP || protocol == IpPacket.PROTO_UDP)
                    IpPacket.destinationPort(packet) else 0,
                protocol = protocolName(protocol),
                blockReason = BlockReason.DROPPED,
                bytes = length.toLong(),
                timestamp = System.currentTimeMillis()
            )
        )

        if (protocol == IpPacket.PROTO_TCP) {
            IpPacket.buildTcpReset(packet, length)?.let { writeToTunnel(it) }
        }
    }

    /** True for traffic aimed at the tunnel's own addresses that is not a lookup. */
    private fun isTunnelProbe(packet: ByteBuffer, protocol: Int, destination: ByteArray): Boolean {
        if (Sinkhole.domainFor(destination) != null) return false
        val address = IpPacket.ipToString(destination)
        if (address != TUN_DNS && address != TUN_ADDRESS) return false
        if (protocol != IpPacket.PROTO_TCP && protocol != IpPacket.PROTO_UDP) return true
        val port = IpPacket.destinationPort(packet)
        return port == PORT_DOT || port != 53
    }

    private fun handleLookup(packet: ByteBuffer, length: Int, destination: ByteArray) {
        val query = IpPacket.udpPayload(packet, length) ?: return
        val question = DnsMessage.parseQuestion(query) ?: return
        val domain = question.name

        Stats.queries.incrementAndGet()
        val owner = owners.resolve(this, IpPacket.PROTO_UDP, packet, length)
        val destinationIp = IpPacket.ipToString(destination)

        // A copy, because the shared read buffer is reused as soon as we return.
        val request = ByteArray(length)
        packet.position(0)
        packet.get(request)
        packet.position(0)
        val requestPacket = ByteBuffer.wrap(request)

        fun log(reason: Int) {
            record(
                ConnectionLog(
                    packageName = owner.packageName,
                    appName = owner.label,
                    domain = domain,
                    destIp = destinationIp,
                    destPort = 53,
                    protocol = if (reason == BlockReason.ALLOWED && DohResolver.isEnabled) "DoH" else "DNS",
                    blockReason = reason,
                    bytes = length.toLong(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        if (owner.mode == AppMode.BLOCKED) {
            Stats.appBlocked.incrementAndGet()
            writeToTunnel(IpPacket.buildUdpResponse(requestPacket, DnsMessage.buildNxDomain(query)))
            log(BlockReason.APP_BLOCKED)
            return
        }

        val verdict = DomainRules.decide(domain)
        if (verdict == DomainRules.BLOCK) {
            Stats.domainsBlocked.incrementAndGet()
            refuse(requestPacket, query, domain)
            log(BlockReason.BLOCKLIST)
            return
        }
        if (verdict != DomainRules.ALLOW && BlocklistManager.isDomainBlocked(domain)) {
            Stats.trackersBlocked.incrementAndGet()
            refuse(requestPacket, query, domain)
            log(BlockReason.TRACKER)
            return
        }

        scope.launch {
            val answer = DohResolver.resolve(query, upstreamDns) { socket -> protect(socket) }
            if (answer != null) {
                writeToTunnel(IpPacket.buildUdpResponse(requestPacket, answer))
                log(BlockReason.ALLOWED)
            }
        }
    }

    /** Point the name at the sinkhole so the connection that follows dies at once. */
    private fun refuse(requestPacket: ByteBuffer, query: ByteArray, domain: String) {
        val response = DnsMessage.buildSinkholeResponse(query, Sinkhole.addressFor(domain))
        writeToTunnel(IpPacket.buildUdpResponse(requestPacket, response))
    }

    /**
     * Buffer a log row, and write the batch out once it is worth a disk write.
     * The timed flush in [startFlushLoop] catches whatever is left over.
     */
    private fun record(log: ConnectionLog) {
        if (logBuffer.add(log)) logBuffer.flush(this, scope)
    }


    private fun writeToTunnel(packet: ByteArray) {
        val out = output ?: return
        try {
            synchronized(writeLock) { out.write(packet) }
        } catch (_: Exception) {
            // The tunnel went away between the read and this write.
        }
    }

    private fun protocolName(protocol: Int) = when (protocol) {
        IpPacket.PROTO_TCP -> "TCP"
        IpPacket.PROTO_UDP -> "UDP"
        else -> "IP/$protocol"
    }

    // --- Network state ---

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshUpstreamDns()
            override fun onLost(network: Network) = refreshUpstreamDns()
            override fun onLinkPropertiesChanged(
                network: Network, linkProperties: android.net.LinkProperties
            ) = refreshUpstreamDns()
        }
        networkCallback = callback
        try { cm.registerNetworkCallback(request, callback) } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    /**
     * The resolvers the real network handed out, used when DNS over HTTPS is off
     * or unreachable. The tunnel's own addresses are filtered out: forwarding a
     * lookup to ourselves would spin.
     */
    private fun refreshUpstreamDns() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var privateDns = false
        val servers = mutableListOf<String>()
        try {
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val link = cm.getLinkProperties(network) ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && link.isPrivateDnsActive) {
                    privateDns = true
                }
                for (server in link.dnsServers) {
                    val address = server.hostAddress ?: continue
                    if (server !is Inet4Address) continue
                    if (address.startsWith("198.18.")) continue
                    if (address !in servers) servers.add(address)
                }
            }
        } catch (_: Exception) {}
        upstreamDns = servers
        privateDnsActive = privateDns
        try { setUnderlyingNetworks(null) } catch (_: Exception) {}
    }

    // --- Lifecycle ---

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (isActive) {
                delay(30_000)
                logBuffer.flush(this@FirewallVpnService, scope)
                Stats.flush(this@FirewallVpnService)
            }
        }
    }

    private fun closeInterface() {
        isRunning = false
        output = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    private fun stopVpn() {
        flushJob?.cancel()
        flushJob = null
        unregisterNetworkCallback()
        readJob?.cancel()
        readJob = null
        closeInterface()
        Sinkhole.clear()
        logBuffer.flush(this, scope)
        Stats.flush(this, closingSession = true)
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Another VPN took the slot. Clear the preference so the switch shows the
        // truth next time the app is opened rather than claiming protection.
        getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).apply()
        stopVpn()
        super.onRevoke()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Firewall Active", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while the firewall is running" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val subtitle = buildString {
            if (DohResolver.isEnabled) append("Encrypted lookups") else append("Filtering lookups")
            if (BlocklistManager.isEnabled) append(", trackers blocked")
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall active")
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}

/**
 * Which app a packet belongs to.
 *
 * Android 10 and newer can answer this directly for a connection's four-tuple.
 * Older releases still expose the socket tables under /proc, which is where the
 * same answer came from before the API existed. Results are cached per source
 * port for a short while, because a lookup costs a system call and a burst of
 * queries from one app all share a port.
 */
private class OwnerLookup {

    data class Owner(val packageName: String, val label: String, val mode: Int)

    private val unknown = Owner("system", "System", AppMode.FILTERED)

    private val portCache = HashMap<Int, Pair<Int, Long>>()   // port to uid and when
    private val uidCache = HashMap<Int, Owner>()
    private var uidCacheVersion = -1

    companion object {
        private const val PORT_TTL_MS = 30_000L
    }

    @Synchronized
    fun resolve(service: VpnService, protocol: Int, packet: ByteBuffer, length: Int): Owner {
        if (protocol != IpPacket.PROTO_TCP && protocol != IpPacket.PROTO_UDP) return unknown
        if (length < IpPacket.headerLength(packet) + 8) return unknown

        val sourcePort = IpPacket.sourcePort(packet)
        val now = System.currentTimeMillis()
        val cached = portCache[sourcePort]
        val uid = if (cached != null && now - cached.second < PORT_TTL_MS) {
            cached.first
        } else {
            val found = lookupUid(service, protocol, packet)
            if (portCache.size > 512) portCache.clear()
            portCache[sourcePort] = found to now
            found
        }
        return ownerOf(service, uid)
    }

    private fun lookupUid(service: VpnService, protocol: Int, packet: ByteBuffer): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val uid = cm.getConnectionOwnerUid(
                    protocol,
                    InetSocketAddress(IpPacket.sourceIp(packet), IpPacket.sourcePort(packet)),
                    InetSocketAddress(IpPacket.destinationIp(packet), IpPacket.destinationPort(packet))
                )
                if (uid > 0) return uid
            } catch (_: Exception) {}
        }
        return ProcNetLookup.uidForLocalPort(protocol, IpPacket.sourcePort(packet))
    }

    private fun ownerOf(service: VpnService, uid: Int): Owner {
        if (uid <= 0) return unknown

        val version = RuleStore.version.get()
        if (version != uidCacheVersion) {
            uidCache.clear()
            uidCacheVersion = version
        }
        uidCache[uid]?.let { return it }

        val packages = try {
            service.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val owner = if (packages.isEmpty()) {
            // A system component with no package of its own. There is nothing for
            // the user to have set a rule on, so it is filtered, never blocked.
            unknown
        } else {
            val primary = packages.first()
            val label = try {
                service.packageManager.getApplicationLabel(
                    service.packageManager.getApplicationInfo(primary, 0)
                ).toString()
            } catch (_: Exception) { primary }
            Owner(primary, label, RuleStore.modeOfGroup(packages))
        }
        uidCache[uid] = owner
        return owner
    }
}

/**
 * The socket tables under /proc, for Android 9 and older where the framework has
 * no call for this. Android 10 closed these files off to apps, which is exactly
 * when the API that replaced them arrived, so the two never have to overlap.
 */
private object ProcNetLookup {

    fun uidForLocalPort(protocol: Int, port: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return -1
        val files = if (protocol == IpPacket.PROTO_TCP) {
            listOf("/proc/net/tcp", "/proc/net/tcp6")
        } else {
            listOf("/proc/net/udp", "/proc/net/udp6")
        }
        val wanted = "%04X".format(port)
        for (path in files) {
            try {
                java.io.File(path).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val fields = line.trim().split("\\s+".toRegex())
                        if (fields.size < 8) continue
                        val local = fields[1]
                        val colon = local.lastIndexOf(':')
                        if (colon < 0) continue
                        if (!local.regionMatches(colon + 1, wanted, 0, 4, ignoreCase = true)) continue
                        return fields[7].toIntOrNull() ?: -1
                    }
                }
            } catch (_: Exception) {}
        }
        return -1
    }
}

/**
 * Log rows on their way to the database.
 *
 * Writing one row per lookup would wake the disk on every name an app resolves,
 * which is the opposite of what this design is for. Rows collect here and go in
 * as one insert, on a batch or on the minute, whichever comes first.
 */
private class LogBuffer {

    private val pending = ArrayList<ConnectionLog>(64)
    private var lastBlockedFlush = 0L

    /**
     * Returns true when the batch should go to the database now.
     *
     * Something that was stopped is the whole reason someone opens this screen,
     * so it goes in almost at once rather than waiting out the batch. Ordinary
     * allowed lookups are the bulk of the rows and can wait, which is what keeps
     * this from waking the disk on every name an app resolves.
     */
    @Synchronized
    fun add(log: ConnectionLog): Boolean {
        pending.add(log)
        if (pending.size >= BATCH_SIZE) return true
        if (log.blockReason == BlockReason.ALLOWED) return false
        val now = System.currentTimeMillis()
        if (now - lastBlockedFlush < BLOCKED_FLUSH_GAP_MS) return false
        lastBlockedFlush = now
        return true
    }

    @Synchronized
    private fun drain(): List<ConnectionLog> {
        if (pending.isEmpty()) return emptyList()
        val copy = ArrayList(pending)
        pending.clear()
        return copy
    }

    fun flush(context: Context, scope: CoroutineScope) {
        val batch = drain()
        if (batch.isEmpty()) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                RuleDatabase.get(appContext).connectionLogDao().insertAll(batch)
            } catch (_: Exception) {}
        }
    }

    private companion object {
        const val BATCH_SIZE = 25

        /** Shortest gap between two writes triggered by something being stopped. */
        const val BLOCKED_FLUSH_GAP_MS = 2_000L
    }
}
