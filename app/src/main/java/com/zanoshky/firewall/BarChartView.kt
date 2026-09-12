package com.zanoshky.firewall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The last twenty four hours, one bar an hour.
 *
 * Full height of a bar is the busiest hour in the window, so the shape is a
 * comparison between hours rather than an absolute scale. The blocked share sits
 * at the bottom of each bar in the block colour, which makes "how much of what
 * my phone asks for is being stopped" readable at a glance.
 *
 * Hand drawn rather than pulled from a chart library: it is two rectangles per
 * bar, and the app has no other use for a charting dependency.
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val allowedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val blockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.danger)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }

    private val bar = RectF()
    private var buckets: List<HourBucket> = emptyList()

    /** [buckets] may have gaps; missing hours are drawn as empty. */
    fun setBuckets(buckets: List<HourBucket>) {
        this.buckets = buckets
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hours = 24
        val nowHour = System.currentTimeMillis() / 3_600_000L
        val byHour = buckets.associateBy { it.hour }
        val peak = buckets.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1

        val gap = 3f
        val width = (width - gap * (hours - 1)) / hours
        val height = height.toFloat()
        val radius = minOf(width / 2f, 3f)

        for (i in 0 until hours) {
            val bucket = byHour[nowHour - (hours - 1 - i)]
            val left = i * (width + gap)

            if (bucket == null || bucket.total == 0) {
                bar.set(left, height - 2f, left + width, height)
                canvas.drawRoundRect(bar, radius, radius, emptyPaint)
                continue
            }

            val full = (bucket.total.toFloat() / peak) * height
            val blocked = (bucket.blocked.toFloat() / bucket.total) * full

            bar.set(left, height - full, left + width, height)
            canvas.drawRoundRect(bar, radius, radius, allowedPaint)

            if (blocked > 0f) {
                bar.set(left, height - blocked, left + width, height)
                canvas.drawRoundRect(bar, radius, radius, blockedPaint)
            }
        }
    }
}
