package com.kimt9.pausegate

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * 숨쉬기 연출. 4초 동안 화면 아래에서 물결 모양의 색이 차오르고(들이쉬기),
 * 4초 동안 다시 내려간다(내쉬기). 화면 전체 배경으로 깔린다.
 */
class BreathView(
    context: Context,
    tint: Int,
    private val onPhase: (inhale: Boolean) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var level = 0f
    private var shift = 0f
    private var inhale = true
    private val amp = 8f * density
    private val waveLen = 360f * density
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; alpha = 80 }

    private val levelAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { level = it.animatedValue as Float }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) {
                inhale = !inhale
                onPhase(inhale)
            }
        })
    }

    private val waveAnim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 3500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { shift = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        levelAnim.start()
        waveAnim.start()
    }

    override fun onDetachedFromWindow() {
        levelAnim.cancel()
        waveAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // level 0이면 물결이 화면 밖 아래로, 1이면 화면 위쪽 밖까지 올라가 전체를 덮는다.
        val top = (h + 2 * amp) * (1f - level) - amp
        path.reset()
        path.moveTo(0f, h)
        val step = 8f * density
        var x = 0f
        while (x < w) {
            path.lineTo(x, top + amp * sin(2 * PI * x / waveLen + shift).toFloat())
            x += step
        }
        path.lineTo(w, top + amp * sin(2 * PI * w / waveLen + shift).toFloat())
        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, fill)
    }
}

/**
 * 개입 화면 전체. 접근성 오버레이 창에 그대로 붙인다.
 * 세로에서는 한 열, 가로에서는 왼쪽 문구와 오른쪽 버튼의 두 열로 배치한다.
 * @param limitReached 오늘 사용 한도를 넘긴 앱이면 true. 더 오래 기다리게 한다.
 */
class OverlayView(
    context: Context,
    private val theme: Theme,
    appLabel: String,
    private val message: String,
    delaySec: Int,
    private val breathing: Boolean,
    limitReached: Boolean,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val labelText = if (limitReached) "$appLabel, 오늘 한도를 넘었어요" else appLabel
    private var remaining = if (limitReached) maxOf(delaySec * 3, 20) else delaySec

    private var inhale = true
    private var phaseText: TextView? = null
    private var openBtn: TextView? = null
    private var landscape: Boolean? = null
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }

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
        if (breathing) addView(BreathView(context, theme.accent) { setPhase(it) }, LayoutParams(-1, -1))
        addView(scroll, LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
    }

    /** 상태 표시줄, 내비게이션 바, 노치 영역을 피해서 내용을 배치한다. */
    private fun applyInsets(i: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            scroll.setPadding(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            scroll.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom)
        }
    }

    private fun setPhase(nowInhale: Boolean) {
        inhale = nowInhale
        phaseText?.text = phaseLabel()
    }

    private fun phaseLabel() = if (inhale) "들이쉬세요" else "내쉬세요"

    private fun text(s: String, sp: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(theme.text)
        this.alpha = alpha
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val land = w > h
        if (land != landscape) {
            landscape = land
            post { build(land) }
        }
    }

    private fun build(land: Boolean) {
        scroll.removeAllViews()
        val padV = if (land) 16 else 24
        val root = LinearLayout(context).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(padV), dp(32), dp(padV))
        }

        val label = text(labelText, 16f, alpha = 0.7f)
        val msg = text(message, if (land) 28f else 34f, bold = true)
            .apply { setPadding(0, dp(12), 0, dp(if (land) 8 else 16)) }
        val phase = if (breathing) {
            text(phaseLabel(), if (land) 36f else 44f, bold = true)
                .apply { setPadding(0, dp(if (land) 8 else 24), 0, 0) }
        } else null
        phaseText = phase

        // 닫기: 강조. 열기: 약하게 + 대기 시간.
        val close = text("닫기", 20f, bold = true).apply {
            setTextColor(if (theme.bg == 0xFFF5F1E8.toInt()) 0xFFFFFFFF.toInt() else theme.bg)
            background = GradientDrawable().apply { setColor(theme.accent); cornerRadius = dp(28).toFloat() }
            setOnClickListener { onClose() }
        }
        val open = text("", 15f, alpha = 0.6f).apply {
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { if (remaining <= 0) onOpen() }
        }
        openBtn = open

        if (land) {
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            left.addView(label, LinearLayout.LayoutParams(-2, -2))
            left.addView(msg, LinearLayout.LayoutParams(-2, -2))
            phase?.let { left.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            right.addView(close, LinearLayout.LayoutParams(-1, dp(52)))
            right.addView(open, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            root.addView(left, LinearLayout.LayoutParams(0, -2, 1.3f))
            root.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(32) })
        } else {
            root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            root.addView(label, LinearLayout.LayoutParams(-2, -2))
            root.addView(msg, LinearLayout.LayoutParams(-2, -2))
            phase?.let { root.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            root.addView(close, LinearLayout.LayoutParams(-1, dp(56)))
            root.addView(open, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        scroll.addView(root, LayoutParams(-1, -1))
        refreshOpenButton()
    }

    private fun refreshOpenButton() {
        openBtn?.apply {
            text = if (remaining > 0) "${remaining}초 후에 열 수 있어요" else "그래도 열기"
            alpha = if (remaining > 0) 0.5f else 0.8f
        }
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
