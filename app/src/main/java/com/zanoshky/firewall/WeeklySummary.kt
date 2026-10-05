package com.zanoshky.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Once a week, a notification that says what the firewall did: how much it
 * stopped, how many tracker companies the apps went for, and which app went
 * for the most. Everything is worked out on the phone from the kept log;
 * nothing leaves it.
 *
 * A daily job drives it rather than a weekly one. The job also trims the log,
 * which otherwise only happened when the tunnel was rebuilt, and a daily
 * check lets the summary land a week after the last one however the system
 * batched the job in between.
 */
object WeeklySummary {

    private const val PREFS = "firewall_prefs"
    private const val KEY_ENABLED = "weekly_summary_enabled"
    private const val KEY_LAST_SENT = "weekly_summary_last_sent"

    private const val CHANNEL_ID = "weekly_summary"
    private const val NOTIFICATION_ID = 2
    const val JOB_ID = 4201

    private const val WEEK_MS = 7 * 86_400_000L
    private const val DAY_MS = 86_400_000L

    /** A little slack so a job that runs a few minutes early still counts as a week. */
    private const val SLACK_MS = 2 * 3_600_000L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Idempotent: safe from every app start and every boot. */
    fun ensureScheduled(context: Context) {
        val p = prefs(context)
        // The first summary comes a week after the feature first ran, not at
        // once over whatever happened to be in the log.
        if (p.getLong(KEY_LAST_SENT, 0) == 0L) {
            p.edit().putLong(KEY_LAST_SENT, System.currentTimeMillis()).apply()
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SummaryJobService::class.java))
            .setPeriodic(DAY_MS)
            .setPersisted(true)
            .build()
        try { scheduler.schedule(job) } catch (_: Exception) {}
    }

    /** The daily run: trim the log, then post the summary if a week has passed. */
    suspend fun runDaily(context: Context) {
        try { RuleDatabase.trim(context) } catch (_: Exception) {}

        val p = prefs(context)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_SENT, 0)
        if (now - last < WEEK_MS - SLACK_MS) return
        p.edit().putLong(KEY_LAST_SENT, now).apply()
        if (!isEnabled(context)) return

        val content = compute(context, now - WEEK_MS) ?: return
        post(context, content)
    }

    data class Content(val title: String, val body: String)

    /** Null when there is nothing worth saying, such as a week with the firewall off. */
    suspend fun compute(context: Context, since: Long): Content? {
        val dao = RuleDatabase.get(context).connectionLogDao()
        val totals = dao.getTotalsSince(since)
        if (totals.total == 0) return null

        val rows = dao.getDomainActivity(since)
        val index = TrackerCompanies.get(context)
        val all = index.report(rows)

        // The app that went for the most companies, ties broken by how often.
        val top = rows.groupBy { it.packageName }
            .map { (_, appRows) -> appRows.first().appName to index.report(appRows) }
            .filter { it.second.companies.isNotEmpty() }
            .maxWithOrNull(
                compareBy<Pair<String, TrackerReport>> { it.second.companies.size }
                    .thenBy { it.second.trackerHits }
            )

        val res = context.resources
        val title = if (totals.blocked > 0) {
            res.getQuantityString(
                R.plurals.weekly_title, totals.blocked, Format.count(context, totals.blocked.toLong())
            )
        } else {
            res.getQuantityString(
                R.plurals.weekly_title_none, totals.total, Format.count(context, totals.total.toLong())
            )
        }

        val lines = mutableListOf<String>()
        val companies = all.companies.size
        if (companies > 0) {
            lines += res.getQuantityString(
                R.plurals.weekly_companies, companies,
                companies, all.companies.take(3).joinToString(", ") { it.name }
            )
        }
        if (top != null) {
            val count = top.second.companies.size
            lines += res.getQuantityString(R.plurals.weekly_top_app, count, count, top.first)
        }
        if (totals.trackers > 0) {
            lines += res.getQuantityString(
                R.plurals.weekly_trackers_stopped, totals.trackers,
                Format.count(context, totals.trackers.toLong())
            )
        }
        if (lines.isEmpty()) lines += context.getString(R.string.weekly_no_trackers)
        return Content(title, lines.joinToString(" "))
    }

    fun post(context: Context, content: Content) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannel(context)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACTIVITY)
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_mono)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) {}
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.weekly_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.weekly_channel_description) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Runs [WeeklySummary.runDaily] off the main thread for the job scheduler. */
class SummaryJobService : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                WeeklySummary.runDaily(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        work?.cancel()
        super.onDestroy()
    }
}
