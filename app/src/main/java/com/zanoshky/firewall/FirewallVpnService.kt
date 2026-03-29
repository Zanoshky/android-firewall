package com.zanoshky.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    companion object {
        const val ACTION_START = "com.zanoshky.firewall.START"
        const val ACTION_STOP = "com.zanoshky.firewall.STOP"
        private const val CHANNEL_ID = "firewall_channel"
        private const val NOTIFICATION_ID = 1

        val totalBlockedSession = AtomicLong(0)
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
                // Close existing VPN fd (unblocks read loop), cancel job, don't persist stats
                // since we're restarting immediately — stats carry over in the AtomicLongs
                try { vpnInterface?.close() } catch (_: Exception) {}
                vpnInterface = null
                readJob?.cancel()
                readJob = null
                readJob = scope.launch { runVpn() }
                START_STICKY
            }
        }
    }

    /**
     * Main VPN loop. Runs entirely on IO dispatcher.
     * Closing vpnInterface from stopVpn() will cause input.read() to throw,
     * breaking the loop cleanly.
     */
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

        sessionStartTime = System.currentTimeMillis()
        // Don't reset counters — they accumulate across VPN restarts within same service lifecycle
        // They only get persisted + reset in stopVpn() when the service actually stops

        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val outputLock = Object()
        val buffer = ByteBuffer.allocate(32767)
        val logBatch = ArrayList<ConnectionLog>(32)

        try {
            while (true) {
                val length = input.read(buffer.array())
                if (length <= 0) continue

                totalBlockedSession.incrementAndGet()
                sessionBytesIn.addAndGet(length.toLong())

                buffer.limit(length)
                buffer.position(0)

                if (PacketFilter.ipVersion(buffer) == 4 && length >= 20) {
                    val proto = PacketFilter.protocol(buffer)
                    val protoName = when (proto) {
                        6 -> "TCP"; 17 -> "UDP"; else -> "IP/$proto"
                    }

                    try {
                        val destIp = PacketFilter.destinationIp(buffer).hostAddress ?: "?"
                        val destPort = if ((proto == 6 || proto == 17) && length >= 24)
                            PacketFilter.destinationPort(buffer) else 0

                        var domain = ""
                        var isTrackerBlocked = false
                        var handledByDoh = false

                        if (proto == 17 && destPort == 53) {
                            val queryDomain = PacketFilter.extractDnsQueryDomain(buffer, length)
                            if (queryDomain != null) {
                                domain = queryDomain

                                val ipHeaderLen = (buffer.get(0).toInt() and 0xF) * 4
                                val dnsStart = ipHeaderLen + 8
                                if (dnsStart < length) {
                                    val dnsQuery = ByteArray(length - dnsStart)
                                    buffer.position(dnsStart)
                                    buffer.get(dnsQuery)
                                    buffer.position(0)

                                    if (BlocklistManager.isDomainBlocked(queryDomain)) {
                                        isTrackerBlocked = true
                                        trackersBlockedSession.incrementAndGet()

                                        val nxResponse = DohResolver.buildBlockedResponse(dnsQuery)
                                        val responsePacket = DohResolver.wrapDnsResponse(buffer, length, nxResponse)
                                        try {
                                            synchronized(outputLock) { output.write(responsePacket) }
                                            sessionBytesOut.addAndGet(responsePacket.size.toLong())
                                        } catch (_: Exception) {}
                                        handledByDoh = true

                                    } else if (DohResolver.isEnabled) {
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
                                        handledByDoh = true
                                    }
                                }
                            }
                        }

                        logBatch.add(ConnectionLog(
                            packageName = "blocked",
                            appName = when {
                                isTrackerBlocked -> "Tracker: $domain"
                                handledByDoh && !isTrackerBlocked -> "DoH: $domain"
                                else -> "Blocked packet"
                            },
                            destIp = destIp,
                            destPort = destPort,
                            protocol = if (handledByDoh && !isTrackerBlocked) "DoH" else protoName,
                            allowed = handledByDoh && !isTrackerBlocked,
                            bytes = length.toLong(),
                            timestamp = System.currentTimeMillis(),
                            domain = domain,
                            blockedByTracker = isTrackerBlocked
                        ))
                    } catch (_: Exception) {}
                }

                if (logBatch.size >= 20) {
                    val batch = ArrayList(logBatch)
                    logBatch.clear()
                    scope.launch { batch.forEach { logDao.insert(it) } }
                }

                buffer.clear()
            }
        } catch (_: Exception) {
            // fd closed by stopVpn → read throws → loop exits cleanly
        } finally {
            if (logBatch.isNotEmpty()) {
                val batch = ArrayList(logBatch)
                scope.launch { batch.forEach { logDao.insert(it) } }
            }
        }
    }

    /**
     * Stop VPN by closing the file descriptor.
     * This causes the blocking input.read() in runVpn to throw,
     * breaking the loop without needing coroutine cancellation to work on blocking IO.
     * Safe to call from any thread.
     */
    private fun stopVpn() {
        // Close fd first — this unblocks the read loop
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        // Cancel the coroutine (it should already be exiting from the fd close)
        readJob?.cancel()
        readJob = null

        // Snapshot and reset counters atomically
        val blocked = totalBlockedSession.getAndSet(0)
        val bytesIn = sessionBytesIn.getAndSet(0)
        val bytesOut = sessionBytesOut.getAndSet(0)
        val trackers = trackersBlockedSession.getAndSet(0)
        val doh = dohQueriesSession.getAndSet(0)
        val duration = if (sessionStartTime > 0) System.currentTimeMillis() - sessionStartTime else 0
        sessionStartTime = 0

        // Persist stats using snapshot values
        if (blocked > 0 || bytesIn > 0) {
            scope.launch {
                try {
                    val prefs = getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong("total_blocked", prefs.getLong("total_blocked", 0) + blocked)
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
            if (DohResolver.isEnabled) append(" · DoH active")
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
