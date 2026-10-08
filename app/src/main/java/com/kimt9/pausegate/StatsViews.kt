package com.kimt9.pausegate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.ceil

private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

/** 날짜별 막대. 아래쪽이 참음(밝게), 위쪽이 열기(어둡게). items는 (라벨, 참음, 열기). */
class StackedBarsView(
    context: Context,
    private val theme: Theme,
    private val items: List<Triple<String, Int, Int>>,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * d
        textAlign = Paint.Align.CENTER
        color = withAlpha(theme.text, 0xAA)
    }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (96 * d).toInt())

    override fun onDraw(canvas: Canvas) {
        val chartH = height - 18 * d
        val n = items.size.coerceAtLeast(1)
        val max = (items.maxOfOrNull { it.second + it.third } ?: 0).coerceAtLeast(1)
        val slot = width.toFloat() / n
        val bw = slot * 0.5f
        items.forEachIndexed { i, (name, resisted, opened) ->
            val x = i * slot + (slot - bw) / 2
            val hr = chartH * resisted / max
            val ho = chartH * opened / max
            if (resisted + opened == 0) {
                paint.color = withAlpha(theme.text, 0x30)
                rect.set(x, chartH - 2 * d, x + bw, chartH)
                canvas.drawRoundRect(rect, d, d, paint)
            }
            if (resisted > 0) {
                paint.color = theme.accent
                rect.set(x, chartH - hr, x + bw, chartH)
                canvas.drawRoundRect(rect, 3 * d, 3 * d, paint)
            }
            if (opened > 0) {
                paint.color = withAlpha(theme.text, 0x66)
                rect.set(x, chartH - hr - ho, x + bw, chartH - hr)
                canvas.drawRoundRect(rect, 3 * d, 3 * d, paint)
            }
            canvas.drawText(name, x + bw / 2, height - 3 * d, label)
        }
    }
}

/** 하루 24시간의 시간대별 개입 횟수 */
class HourBarsView(
    context: Context,
    private val theme: Theme,
    private val counts: IntArray,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * d
        color = withAlpha(theme.text, 0xAA)
    }
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (64 * d).toInt())

    override fun onDraw(canvas: Canvas) {
        val chartH = height - 18 * d
        val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val slot = width.toFloat() / 24
        val bw = slot * 0.62f
        for (i in 0 until 24) {
            val x = i * slot + (slot - bw) / 2
            val h = if (counts[i] == 0) 2 * d else chartH * counts[i] / max
            paint.color = if (counts[i] == 0) withAlpha(theme.text, 0x30) else theme.accent
            rect.set(x, chartH - h, x + bw, chartH)
            canvas.drawRoundRect(rect, d, d, paint)
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("0시", 0f, height - 3 * d, label)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText("12시", width / 2f, height - 3 * d, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText("24시", width.toFloat(), height - 3 * d, label)
    }
}

/**
 * 한 달 달력. 날짜마다 참은 비율이 높을수록 진하게 칠한다. 개입이 없던 날은 옅은 칸이다.
 * @param month 0부터 시작하는 달
 */
class CalendarView(
    context: Context,
    private val theme: Theme,
    private val year: Int,
    private val month: Int,
    private val stats: Map<String, DayStat>,
    private val selected: String,
    private val today: String,
    private val onPick: (String) -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val head = 24 * d
    private val cellH = 42 * d
    private val offset: Int
    private val days: Int
    private val rows: Int
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * d; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init {
        val cal = Calendar.getInstance().apply { clear(); set(year, month, 1) }
        offset = cal.get(Calendar.DAY_OF_WEEK) - 1
        days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        rows = ceil((offset + days) / 7.0).toInt()
    }

    private fun dayKey(day: Int) = String.format("%04d%02d%02d", year, month + 1, day)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (head + rows * cellH).toInt())

    override fun onDraw(canvas: Canvas) {
        val cw = width / 7f
        num.textSize = 11f * d
        num.color = withAlpha(theme.text, 0x99)
        "일월화수목금토".forEachIndexed { i, c -> canvas.drawText(c.toString(), cw * i + cw / 2, head - 8 * d, num) }
        num.textSize = 13f * d
        for (day in 1..days) {
            val idx = offset + day - 1
            val col = idx % 7
            val row = idx / 7
            rect.set(cw * col + 2 * d, head + cellH * row + 2 * d, cw * (col + 1) - 2 * d, head + cellH * (row + 1) - 2 * d)
            val key = dayKey(day)
            val s = stats[key]
            val future = key > today
            var textColor = withAlpha(theme.text, if (future) 0x55 else 0xCC)
            if (s != null && s.shown > 0) {
                val level = when {
                    s.ratio < 0.4f -> 0x50
                    s.ratio < 0.7f -> 0x99
                    else -> 0xFF
                }
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(theme.accent, level)
                canvas.drawRoundRect(rect, 6 * d, 6 * d, paint)
                if (level == 0xFF) textColor = theme.bg
            } else if (!future) {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(theme.text, 0x14)
                canvas.drawRoundRect(rect, 6 * d, 6 * d, paint)
            }
            if (key == selected || key == today) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (if (key == selected) 2f else 1f) * d
                paint.color = withAlpha(theme.text, if (key == selected) 0xFF else 0x80)
                canvas.drawRoundRect(rect, 6 * d, 6 * d, paint)
            }
            num.color = textColor
            canvas.drawText(day.toString(), rect.centerX(), rect.centerY() + 4.5f * d, num)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val col = (e.x / (width / 7f)).toInt()
                val row = ((e.y - head) / cellH).toInt()
                val day = row * 7 + col - offset + 1
                if (e.y >= head && col in 0..6 && day in 1..days) {
                    performClick()
                    onPick(dayKey(day))
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()
}
