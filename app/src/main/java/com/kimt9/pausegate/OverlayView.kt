package com.kimt9.pausegate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 숨쉬기 원. 4초 들이쉬고 4초 내쉬는 크기 변화를 그린다. */
class BreathView(
    context: Context,
    private val tint: Int,
    private val onPhase: (inhale: Boolean) -> Unit,
) : View(context) {
    private var t = 0f
    private var inhale = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; alpha = 70 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.STROKE; strokeWidth = 4f * resources.displayMetrics.density
    }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: android.animation.Animator) {
                inhale = !inhale
                onPhase(inhale)
            }
        })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val max = minOf(width, height) / 2f - ring.strokeWidth
        val r = max * (0.45f + 0.55f * t)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
    }
}

/**
 * 개입 화면 전체. 접근성 오버레이 창에 그대로 붙인다.
 * @param limitReached 오늘 사용 한도를 넘긴 앱이면 true. 더 오래 기다리게 한다.
 */
class OverlayView(
    context: Context,
    private val theme: Theme,
    appLabel: String,
    message: String,
    delaySec: Int,
    breathing: Boolean,
    limitReached: Boolean,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var remaining = if (limitReached) maxOf(delaySec * 3, 20) else delaySec
    private val openBtn: TextView
    private val tick = object : Runnable {
        override fun run() {
            remaining--
            refreshOpenButton()
            if (remaining > 0) postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(theme.bg)
        isFocusableInTouchMode = true
        isClickable = true // 뒤쪽 앱으로 터치가 새지 않게 막는다.

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        fun text(s: String, sp: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(context).apply {
            this.text = s
            textSize = sp
            setTextColor(theme.text)
            this.alpha = alpha
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val top = View(context)
        col.addView(top, LinearLayout.LayoutParams(0, 0, 1f))

        col.addView(
            text(if (limitReached) "$appLabel, 오늘 한도를 넘었어요" else appLabel, 15f, alpha = 0.7f),
            LinearLayout.LayoutParams(-2, -2),
        )
        col.addView(
            text(message, 24f, bold = true).apply { setPadding(0, dp(12), 0, dp(24)) },
            LinearLayout.LayoutParams(-2, -2),
        )

        if (breathing) {
            val phase = text("들이쉬세요", 16f, alpha = 0.85f)
            val breath = BreathView(context, theme.accent) { inhale ->
                phase.text = if (inhale) "들이쉬세요" else "내쉬세요"
            }
            col.addView(breath, LinearLayout.LayoutParams(dp(220), dp(220)))
            col.addView(phase.apply { setPadding(0, dp(12), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
        }

        val bottom = View(context)
        col.addView(bottom, LinearLayout.LayoutParams(0, 0, 1f))

        // 닫기: 강조. 열기: 약하게 + 대기 시간.
        val close = text("닫기", 18f, bold = true).apply {
            setTextColor(if (theme.bg == 0xFFF5F1E8.toInt()) 0xFFFFFFFF.toInt() else theme.bg)
            background = GradientDrawable().apply { setColor(theme.accent); cornerRadius = dp(28).toFloat() }
            setOnClickListener { onClose() }
        }
        col.addView(close, LinearLayout.LayoutParams(-1, dp(56)))

        openBtn = text("", 14f, alpha = 0.6f).apply {
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { if (remaining <= 0) onOpen() }
        }
        col.addView(openBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        refreshOpenButton()
    }

    private fun refreshOpenButton() {
        openBtn.text = if (remaining > 0) "${remaining}초 후에 열 수 있어요" else "그래도 열기"
        openBtn.alpha = if (remaining > 0) 0.4f else 0.7f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        postDelayed(tick, 1000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** 뒤로 가기는 "닫기"와 같게 처리한다. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
