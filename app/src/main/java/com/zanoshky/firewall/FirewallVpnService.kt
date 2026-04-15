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
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val isRebuilding = AtomicBoolean(false)

    companion object {
        const val ACTION_START = "com.zanoshky.firewall.START"
        const val ACTION_STOP = "com.zanoshky.firewall.STOP"
        private const val CHANNEL_ID = "firewall_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "FirewallVPN"

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
        acquireWakeLock()
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
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            readJob?.cancel()
            readJob = null
            readJob = scope.launch {
                try { runVpn() } finally { isRebuilding.set(false) }
            }
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
            override fun onAvailable(network: Network) { rebuildTunnel() }
            override fun onLost(network: Network) { /* rebuild when available */ }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                rebuildTunnel()
            }
        }
        networkCallback = cb
        cm.registerNetworkCallback(request, cb)
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

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Firewall::VpnWakeLock"
            ).apply { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

        for (pkg in allowedPackages) {
            try { builder.addDisallowedApplication(pkg) }
            catch (_: PackageManager.NameNotFoundException) {}
        }

        val fd = builder.establish() ?: return
        vpnInterface = fd

        if (sessionStartTime == 0L) {
            sessionStartTime = System.currentTimeMillis()
        }

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
                if (length <= 0) continue

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
                    val destIp = PacketFilter.destinationIp(buffer).hostAddress ?: "?"
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

                        val label = "$destIp:$destPort"
                        logBatch.add(ConnectionLog(
                            packageName = "system",
                            appName = label,
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
                    scope.launch { batch.forEach { logDao.insert(it) } }
                }

                buffer.clear()
            }
        } catch (_: Exception) {
            // fd closed - loop exits
        } finally {
            if (logBatch.isNotEmpty()) {
                val batch = ArrayList(logBatch)
                scope.launch { batch.forEach { logDao.insert(it) } }
            }
        }
    }

    private fun stopVpn() {
        unregisterNetworkCallback()

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
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        releaseWakeLock()
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
