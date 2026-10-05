package com.zanoshky.firewall

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The home screen widget: protected or not, what was stopped today, and a switch.
 *
 * The switch follows the same rules as the quick settings tile. Turning
 * protection on is never a threat, so it happens in place. Turning it off with
 * App Lock set would let anyone holding the phone get round the passcode, so
 * that tap opens the app instead. The same goes for turning on before the
 * system's VPN consent was ever given, because only an activity can ask.
 * Which of the two a tap does is decided when the widget is drawn, since a
 * broadcast receiver is not allowed to open an activity from the background.
 */
class FirewallWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.zanoshky.firewall.WIDGET_TOGGLE"
        private const val PREFS = "firewall_prefs"

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** Redraw every placed widget. Cheap when there are none. */
        fun requestUpdate(context: Context) {
            val appCtx = context.applicationContext
            if (ids(appCtx).isEmpty()) return
            scope.launch { update(appCtx) }
        }

        private fun ids(context: Context): IntArray = try {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, FirewallWidget::class.java))
        } catch (_: Exception) { IntArray(0) }

        private suspend fun update(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val today = try {
                RuleDatabase.get(context).connectionLogDao().getTotalsSince(startOfToday())
            } catch (_: Exception) { PeriodTotals(0, 0, 0, 0) }
            val views = render(context, today)
            try { AppWidgetManager.getInstance(context).updateAppWidget(ids, views) } catch (_: Exception) {}
        }

        private fun render(context: Context, today: PeriodTotals): RemoteViews {
            val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
            val views = RemoteViews(context.packageName, R.layout.widget_firewall)
            val res = context.resources

            views.setTextViewText(
                R.id.widgetStatus,
                context.getString(if (enabled) R.string.status_protected else R.string.status_inactive)
            )
            if (enabled) {
                views.setImageViewResource(R.id.widgetShield, R.drawable.ic_shield)
                views.setInt(R.id.widgetShield, "setColorFilter", 0)
            } else {
                views.setImageViewResource(R.id.widgetShield, R.drawable.ic_shield_mono)
                views.setInt(R.id.widgetShield, "setColorFilter", context.getColor(R.color.text_hint))
            }

            views.setTextViewText(
                R.id.widgetStopped,
                res.getQuantityString(
                    R.plurals.widget_stopped_today, today.blocked,
                    Format.count(context, today.blocked.toLong())
                )
            )
            if (today.trackers > 0) {
                views.setTextViewText(
                    R.id.widgetTrackers,
                    res.getQuantityString(
                        R.plurals.widget_trackers_today, today.trackers,
                        Format.count(context, today.trackers.toLong())
                    )
                )
                views.setViewVisibility(R.id.widgetTrackers, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetTrackers, View.GONE)
            }

            views.setTextViewText(
                R.id.widgetToggle,
                context.getString(if (enabled) R.string.action_turn_off else R.string.action_turn_on)
            )
            views.setInt(
                R.id.widgetToggle, "setBackgroundResource",
                if (enabled) R.drawable.bg_chip else R.drawable.bg_chip_active
            )
            views.setTextColor(
                R.id.widgetToggle,
                context.getColor(if (enabled) R.color.text_secondary else R.color.accent)
            )

            val needsApp = if (enabled) AppLock.isEnabled(context) else needsConsent(context)
            views.setOnClickPendingIntent(
                R.id.widgetToggle,
                if (needsApp) openApp(context, 1) else toggle(context)
            )
            views.setOnClickPendingIntent(android.R.id.background, openApp(context, 0))
            return views
        }

        private fun needsConsent(context: Context): Boolean =
            try { VpnService.prepare(context) != null } catch (_: Exception) { true }

        private fun openApp(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun toggle(context: Context): PendingIntent {
            val intent = Intent(context, FirewallWidget::class.java).setAction(ACTION_TOGGLE)
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val appCtx = context.applicationContext
        scope.launch {
            try { update(appCtx) } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)

        // The widget was drawn before something changed, such as App Lock being
        // set since. Do nothing unsafe; the redraw below sends the next tap to
        // the app.
        val blocked = if (enabled) AppLock.isEnabled(context) else needsConsent(context)
        if (!blocked) {
            if (enabled) {
                prefs.edit().putBoolean("enabled", false).apply()
                context.startService(
                    Intent(context, FirewallVpnService::class.java)
                        .setAction(FirewallVpnService.ACTION_STOP)
                )
            } else {
                prefs.edit().putBoolean("enabled", true).apply()
                val start = Intent(context, FirewallVpnService::class.java)
                    .setAction(FirewallVpnService.ACTION_START)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(start)
                    } else {
                        context.startService(start)
                    }
                } catch (_: Exception) {
                    // A background start was refused. Say so by staying off.
                    prefs.edit().putBoolean("enabled", false).apply()
                }
            }
        }
        requestUpdate(context)
    }
}
