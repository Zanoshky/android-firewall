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

class FirewallVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START = "com.zanoshky.firewall.START"
        const val ACTION_STOP = "com.zanoshky.firewall.STOP"
        private const val CHANNEL_ID = "firewall_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                scope.launch { startVpn() }
                START_STICKY
            }
        }
    }

    private suspend fun startVpn() {
        stopVpn() // clean up any existing session

        val dao = RuleDatabase.get(this).ruleDao()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onWifi = PacketFilter.isOnWifi(cm)

        // Get packages that are ALLOWED for the current network type
        val allowedPackages = if (onWifi) dao.getAllowedWifi() else dao.getAllowedMobile()

        val builder = Builder()
            .setSession("Firewall")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0) // capture all IPv4
            .addRoute("::", 0)       // capture all IPv6
            .setBlocking(false)

        // BLOCK ALL by default. Only disallow our own app so we don't loop,
        // then add allowed apps to the disallowed list (they bypass the VPN = get internet).
        // Apps NOT in the disallowed list go through the VPN tunnel which is a black hole.

        // Disallow ourselves to prevent infinite loop
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {}

        // Disallow (= bypass VPN = allow internet) for each permitted app
        for (pkg in allowedPackages) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        vpnInterface = builder.establish()
    }

    private fun stopVpn() {
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onDestroy() {
        scope.cancel()
        stopVpn()
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall Active")
            .setContentText("Blocking unauthorized network access")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
