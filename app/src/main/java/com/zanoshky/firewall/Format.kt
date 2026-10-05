package com.zanoshky.firewall

import android.content.Context
import java.util.Locale

/** Number formatting shared by the insights sheet, the weekly summary and the widget. */
object Format {

    /**
     * A count shortened the way the Activity tab does it. The number follows the
     * reader's own language, so a Russian phone gets 1,2 rather than 1.2; the K
     * and M suffixes are strings.
     */
    fun count(context: Context, n: Long): String {
        val locale = Locale.getDefault()
        return when {
            n >= 1_000_000 -> context.getString(
                R.string.count_millions, String.format(locale, "%.1f", n / 1_000_000.0)
            )
            n >= 10_000 -> context.getString(
                R.string.count_thousands, String.format(locale, "%.0f", n / 1_000.0)
            )
            n >= 1_000 -> context.getString(
                R.string.count_thousands, String.format(locale, "%.1f", n / 1_000.0)
            )
            else -> n.toString()
        }
    }

    /** Whole days covered from [since] to now, at least one. */
    fun daysSince(since: Long, now: Long = System.currentTimeMillis()): Int {
        if (since <= 0) return 1
        return (((now - since) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, 7)
    }

    /** A plural resource needs an Int; very large counts only ever pick "other". */
    fun quantity(n: Long): Int = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
