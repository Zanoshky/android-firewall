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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val ownerCache = HashMap<String, Pair<String, String>?>()
    private val isRebuilding = AtomicBoolean(false)
    @Volatile private var lastNetworkKey: String? = null
    private var rebuildDebounce: Job? = null

    companion object {
        const val ACTION_START = "com.zanoshky.firewall.START"
        const val ACTION_STOP = "com.zanoshky.firewall.STOP"
        private const val CHANNEL_ID = "firewall_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FirewallVPN"

        /** True while the VPN tunnel is established and processing packets. */
        @Volatile var isRunning: Boolean = false

        val totalBlockedSession = AtomicLong(0)
        val totalAllowedSession = AtomicLong(0)
        val trackersBlockedSession = AtomicLong(0)
        val dohQueriesSession = AtomicLong(0)
        val sessionBytesIn = AtomicLong(0)
        val sessionBytesOut = AtomicLong(0)
        @Volatile var sessionStartTime: Long = 0
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BlocklistManager.init(this)
        DohResolver.init(this)
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

    private fun rebuildTunnel() {
        if (!isRebuilding.compareAndSet(false, true)) return
        try {
            isRunning = false
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            readJob?.cancel()
            readJob = null
            // Release the guard before launching: the coroutine is the steady-state
            // packet loop, not a "rebuild in progress". The guard only serializes the
            // teardown-and-re-establish window above.
            isRebuilding.set(false)
            readJob = scope.launch { runVpn() }
        } catch (e: Exception) {
            isRebuilding.set(false)
            Log.e(TAG, "rebuildTunnel failed", e)
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkEvent(network, cm.getNetworkCapabilities(network))
            }
            override fun onLost(network: Network) { /* rebuild when available */ }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onNetworkEvent(network, caps)
            }
        }
        networkCallback = cb
        cm.registerNetworkCallback(request, cb)
    }

    /**
     * onCapabilitiesChanged fires constantly (signal strength, validation, metering),
     * but per-app rules only depend on which network/transport we're on. Only rebuild
     * the tunnel when that actually changes, debounced so rapid handovers coalesce.
     */
    private fun onNetworkEvent(network: Network, caps: NetworkCapabilities?) {
        val key = networkKey(network, caps)
        if (key == lastNetworkKey) return
        lastNetworkKey = key
        rebuildDebounce?.cancel()
        rebuildDebounce = scope.launch {
            delay(1000)
            rebuildTunnel()
        }
    }

    private fun networkKey(network: Network?, caps: NetworkCapabilities?): String {
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
        return "$network/$transport"
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
     * Resolve which app owns a connection (Android 10+). Results are cached per
     * connection 4-tuple so the syscall only happens once per new connection.
     */
    private fun resolveOwnerApp(
        proto: Int, srcAddr: InetAddress, srcPort: Int, destAddr: InetAddress, destPort: Int
    ): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (proto != 6 && proto != 17) return null
        val key = "$proto|$srcPort|${destAddr.hostAddress}|$destPort"
        if (ownerCache.containsKey(key)) return ownerCache[key]
        val result = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val uid = cm.getConnectionOwnerUid(
                proto, InetSocketAddress(srcAddr, srcPort), InetSocketAddress(destAddr, destPort)
            )
            if (uid > 0) {
                packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
                    val label = try {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    } catch (_: Exception) { pkg }
                    pkg to label
                }
            } else null
        } catch (_: Exception) { null }
        if (ownerCache.size > 2000) ownerCache.clear()
        ownerCache[key] = result
        return result
    }

    private suspend fun runVpn() {
        val db = RuleDatabase.get(this)
        val dao = db.ruleDao()
        val trafficDao = db.trafficDao()
        val logDao = db.connectionLogDao()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onWifi = PacketFilter.isOnWifi(cm)
        val allowedPackages = if (onWifi) dao.getAllowedWifi() else dao.getAllowedMobile()

        val pm = packageManager
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in allApps) {
            if (app.uid > 1000 && trafficDao.getStats(app.packageName) == null) {
                trafficDao.upsert(TrafficStat(app.packageName))
            }
        }

        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        logDao.deleteOlderThan(weekAgo)
        BlocklistManager.reloadSync(this)

        val builder = Builder()
            .setSession("Firewall")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setBlocking(true)

        if (DohResolver.isEnabled) {
            builder.addDnsServer("10.0.0.2")
        }

        try { builder.addDisallowedApplication(packageName) }
        catch (_: PackageManager.NameNotFoundException) {}

        // Android enforces network access per-UID, not per-package. Excluding an
        // allowed package whose UID is shared with a blocked package would let the
        // blocked one ride along with full access. Only exclude a package when
        // every package in its UID group is allowed.
        val allowedSet = allowedPackages.toSet()
        val byUid = allApps.filter { it.uid > 1000 }.groupBy { it.uid }
        for (app in allApps) {
            if (app.packageName !in allowedSet) continue
            val uidMates = byUid[app.uid] ?: continue
            if (uidMates.all { it.packageName in allowedSet }) {
                try { builder.addDisallowedApplication(app.packageName) }
                catch (_: PackageManager.NameNotFoundException) {}
            }
        }

        // Fail closed: if establish() fails (e.g. another VPN grabbed the slot),
        // retry instead of silently leaving traffic unprotected.
        var established: ParcelFileDescriptor? = null
        for (attempt in 1..3) {
            established = builder.establish()
            if (established != null) break
            Log.e(TAG, "VPN establish() failed (attempt $attempt)")
            delay(2000)
        }
        val fd = established ?: return
        vpnInterface = fd
        isRunning = true

        if (sessionStartTime == 0L) {
            sessionStartTime = System.currentTimeMillis()
        }

        // Seed the key so the callback's immediate onAvailable for the current
        // network doesn't trigger a pointless rebuild right after establish().
        val activeNetwork = cm.activeNetwork
        lastNetworkKey = networkKey(activeNetwork, activeNetwork?.let { cm.getNetworkCapabilities(it) })
        registerNetworkCallback()

        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val outputLock = Object()
        val buffer = ByteBuffer.allocate(32767)
        val logBatch = ArrayList<ConnectionLog>(32)
        var lastFlush = System.currentTimeMillis()

        try {
            while (true) {
                val length = input.read(buffer.array())
                if (length < 0) break // fd closed - would busy-spin on continue
                if (length == 0) continue

                sessionBytesIn.addAndGet(length.toLong())
                buffer.limit(length)
                buffer.position(0)

                if (PacketFilter.ipVersion(buffer) != 4 || length < 20) {
                    // Non-IPv4 or too short - count as blocked, skip
                    totalBlockedSession.incrementAndGet()
                    buffer.clear()
                    continue
                }

                val proto = PacketFilter.protocol(buffer)
                val protoName = when (proto) {
                    6 -> "TCP"; 17 -> "UDP"; else -> "IP/$proto"
                }

                try {
                    val destAddr = PacketFilter.destinationIp(buffer)
                    val destIp = destAddr.hostAddress ?: "?"
                    val destPort = if ((proto == 6 || proto == 17) && length >= 24)
                        PacketFilter.destinationPort(buffer) else 0

                    // --- DNS on UDP port 53 ---
                    if (proto == 17 && destPort == 53) {
                        val queryDomain = PacketFilter.extractDnsQueryDomain(buffer, length)
                        if (queryDomain != null) {
                            val ipHeaderLen = (buffer.get(0).toInt() and 0xF) * 4
                            val dnsStart = ipHeaderLen + 8
                            if (dnsStart < length) {
                                val dnsQuery = ByteArray(length - dnsStart)
                                buffer.position(dnsStart)
                                buffer.get(dnsQuery)
                                buffer.position(0)

                                if (BlocklistManager.isDomainBlocked(queryDomain)) {
                                    // Tracker blocked - send NXDOMAIN back
                                    trackersBlockedSession.incrementAndGet()
                                    totalBlockedSession.incrementAndGet()

                                    val nxResponse = DohResolver.buildBlockedResponse(dnsQuery)
                                    val responsePacket = DohResolver.wrapDnsResponse(buffer, length, nxResponse)
                                    try {
                                        synchronized(outputLock) { output.write(responsePacket) }
                                        sessionBytesOut.addAndGet(responsePacket.size.toLong())
                                    } catch (_: Exception) {}

                                    logBatch.add(ConnectionLog(
                                        packageName = "system",
                                        appName = "Tracker: $queryDomain",
                                        destIp = destIp,
                                        destPort = destPort,
                                        protocol = "DNS",
                                        allowed = false,
                                        bytes = length.toLong(),
                                        timestamp = System.currentTimeMillis(),
                                        domain = queryDomain,
                                        blockedByTracker = true
                                    ))

                                } else if (DohResolver.isEnabled) {
                                    // DoH resolve - allowed
                                    totalAllowedSession.incrementAndGet()

                                    val packetCopy = ByteArray(length)
                                    buffer.position(0)
                                    buffer.get(packetCopy)
                                    buffer.position(0)

                                    scope.launch {
                                        try {
                                            val dnsResponse = DohResolver.resolve(dnsQuery)
                                            if (dnsResponse != null) {
                                                val wrapped = DohResolver.wrapDnsResponse(
                                                    ByteBuffer.wrap(packetCopy), length, dnsResponse
                                                )
                                                synchronized(outputLock) { output.write(wrapped) }
                                                sessionBytesOut.addAndGet(wrapped.size.toLong())
                                                dohQueriesSession.incrementAndGet()
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    logBatch.add(ConnectionLog(
                                        packageName = "system",
                                        appName = "DoH: $queryDomain",
                                        destIp = destIp,
                                        destPort = destPort,
                                        protocol = "DoH",
                                        allowed = true,
                                        bytes = length.toLong(),
                                        timestamp = System.currentTimeMillis(),
                                        domain = queryDomain,
                                        blockedByTracker = false
                                    ))

                                } else {
                                    // DNS query, no DoH, not blocked - dropped by VPN (blocked)
                                    totalBlockedSession.incrementAndGet()

                                    logBatch.add(ConnectionLog(
                                        packageName = "system",
                                        appName = "DNS: $queryDomain",
                                        destIp = destIp,
                                        destPort = destPort,
                                        protocol = "DNS",
                                        allowed = false,
                                        bytes = length.toLong(),
                                        timestamp = System.currentTimeMillis(),
                                        domain = queryDomain,
                                        blockedByTracker = false
                                    ))
                                }
                            } else {
                                totalBlockedSession.incrementAndGet()
                            }
                        } else {
                            // Malformed DNS
                            totalBlockedSession.incrementAndGet()
                        }
                    } else {
                        // --- Non-DNS traffic (TCP, UDP to other ports) ---
                        // These are dropped by the VPN (no route out)
                        totalBlockedSession.incrementAndGet()

                        val srcPort = if ((proto == 6 || proto == 17) && length >= 24)
                            PacketFilter.sourcePort(buffer) else 0
                        val owner = if (srcPort > 0)
                            resolveOwnerApp(proto, PacketFilter.sourceIp(buffer), srcPort, destAddr, destPort)
                        else null

                        logBatch.add(ConnectionLog(
                            packageName = owner?.first ?: "system",
                            appName = owner?.second ?: "$destIp:$destPort",
                            destIp = destIp,
                            destPort = destPort,
                            protocol = protoName,
                            allowed = false,
                            bytes = length.toLong(),
                            timestamp = System.currentTimeMillis(),
                            domain = "",
                            blockedByTracker = false
                        ))
                    }
                } catch (_: Exception) {
                    totalBlockedSession.incrementAndGet()
                }

                // Flush log batch: every 10 entries OR every 2 seconds
                val now = System.currentTimeMillis()
                if (logBatch.size >= 10 || (logBatch.isNotEmpty() && now - lastFlush >= 2000)) {
                    val batch = ArrayList(logBatch)
                    logBatch.clear()
                    lastFlush = now
                    scope.launch { logDao.insertAll(batch) }
                }

                buffer.clear()
            }
        } catch (_: Exception) {
            // fd closed - loop exits
        } finally {
            if (logBatch.isNotEmpty()) {
                val batch = ArrayList(logBatch)
                scope.launch { logDao.insertAll(batch) }
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        unregisterNetworkCallback()
        rebuildDebounce?.cancel()
        rebuildDebounce = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        readJob?.cancel()
        readJob = null

        val blocked = totalBlockedSession.getAndSet(0)
        val allowed = totalAllowedSession.getAndSet(0)
        val bytesIn = sessionBytesIn.getAndSet(0)
        val bytesOut = sessionBytesOut.getAndSet(0)
        val trackers = trackersBlockedSession.getAndSet(0)
        val doh = dohQueriesSession.getAndSet(0)
        val duration = if (sessionStartTime > 0) System.currentTimeMillis() - sessionStartTime else 0
        sessionStartTime = 0

        if (blocked > 0 || allowed > 0 || bytesIn > 0) {
            scope.launch {
                try {
                    val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong("total_blocked", prefs.getLong("total_blocked", 0) + blocked)
                        .putLong("total_allowed", prefs.getLong("total_allowed", 0) + allowed)
                        .putLong("total_bytes_in", prefs.getLong("total_bytes_in", 0) + bytesIn)
                        .putLong("total_bytes_out", prefs.getLong("total_bytes_out", 0) + bytesOut)
                        .putLong("trackers_blocked", prefs.getLong("trackers_blocked", 0) + trackers)
                        .putLong("doh_queries", prefs.getLong("doh_queries", 0) + doh)
                        .putLong("last_session_duration", duration)
                        .apply()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // Another VPN took over. Clear the pref so the switch shows "off" when the
        // user next opens the app, instead of lying about protection being active.
        getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).apply()
        stopVpn()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Firewall Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows when the firewall is running" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val subtitle = buildString {
            append("Monitoring traffic")
            if (DohResolver.isEnabled) append(" - DoH active")
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall Active")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
