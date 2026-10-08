package com.kimt9.pausegate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
 * 숨쉬기 연출. 4초 동안 화면 아래에서 불투명한 물결이 차올라 화면 전체를 덮고(들숨),
 * 4초 동안 다시 내려간다(날숨). 내용 위에 그려지므로 글자와 버튼도 덮는다.
 * 안내 문구(cue)는 slot 위치에 직접 그린다. 물 위쪽은 밝은 글자, 물에 잠긴 쪽은 어두운 글자로 나눠 그려
 * 어느 쪽에서도 읽힌다.
 * 터치는 받지 않으므로 물이 차 있어도 아래의 버튼이 눌린다.
 */
class BreathView(
    context: Context,
    private val theme: Theme,
    private val cue: String,
) : View(context) {
    /** 안내 문구를 그릴 자리. OverlayView가 레이아웃을 만들 때마다 갱신한다. */
    var slot: View? = null

    private val density = resources.displayMetrics.density
    private var level = 0f
    private var shift = 0f
    private val amp = 8f * density
    private val waveLen = 360f * density
    private val water = Path()
    private val surface = Path()
    private val rect = Rect()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xE6FFFFFF.toInt()
    }
    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT_BOLD
    }

    private val levelAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { level = it.animatedValue as Float }
    }

    private val waveAnim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 3500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { shift = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (theme.metal) {
            fill.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFFF4F6F9.toInt(), 0xFFBEC4CC.toInt(), 0xFF7F8791.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
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

    /** 터치는 모두 아래의 버튼에게 넘긴다. */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // level 0이면 물결이 화면 밖 아래로, 1이면 화면 위쪽 밖까지 올라가 전체를 덮는다.
        val top = (h + 2 * amp) * (1f - level) - amp
        water.reset()
        surface.reset()
        water.moveTo(0f, h)
        val step = 8f * density
        var x = 0f
        while (true) {
            val px = minOf(x, w)
            val y = top + amp * sin(2 * PI * px / waveLen + shift).toFloat()
            water.lineTo(px, y)
            if (x == 0f) surface.moveTo(px, y) else surface.lineTo(px, y)
            if (px >= w) break
            x += step
        }
        water.lineTo(w, h)
        water.close()

        canvas.drawPath(water, fill)
        if (theme.metal) canvas.drawPath(surface, line)
        drawCue(canvas)
    }

    private fun drawCue(canvas: Canvas) {
        val s = slot ?: return
        if (s.height == 0 || s.parent == null) return
        rect.set(0, 0, s.width, s.height)
        (parent as ViewGroup).offsetDescendantRectToMyCoords(s, rect)
        val fm = cuePaint.fontMetrics
        val baseline = rect.exactCenterY() - (fm.ascent + fm.descent) / 2f
        val cx = rect.exactCenterX()

        canvas.save()
        canvas.clipOutPath(water)
        cuePaint.color = theme.text
        canvas.drawText(cue, cx, baseline, cuePaint)
        canvas.restore()

        canvas.save()
        canvas.clipPath(water)
        cuePaint.color = theme.bg
        canvas.drawText(cue, cx, baseline, cuePaint)
        canvas.restore()
    }
}

/**
 * 개입 화면 전체. 접근성 오버레이 창에 그대로 붙인다.
 * 맨 위에 닫기와 열기 버튼, 가운데에 문구, 그 아래에 안내 문구 자리를 둔다.
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

    private var openBtn: TextView? = null
    private var landscape: Boolean? = null
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }
    private val breath: BreathView? = if (breathing) BreathView(context, theme, "호흡하세요") else null

    private val tick = object : Runnable {
        override fun run() {
            remaining--
            refreshOpenButton()
            if (remaining > 0) postDelayed(this, 1000)
        }
    }

    init {
        background = theme.backgroundDrawable()
        isFocusableInTouchMode = true
        isClickable = true // 뒤쪽 앱으로 터치가 새지 않게 막는다.
        addView(scroll, LayoutParams(-1, -1))
        // 물결이 내용(글자와 버튼) 위에 그려지도록 마지막에 붙인다.
        breath?.let { addView(it, LayoutParams(-1, -1)) }
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
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(if (land) 12 else 20), dp(20), dp(if (land) 12 else 24))
        }

        // 맨 위: 닫기(강조)와 열기(약하게, 대기 시간 표시)
        val close = text("닫기", 18f, bold = true).apply {
            setTextColor(if (theme.bg == 0xFFF5F1E8.toInt()) 0xFFFFFFFF.toInt() else theme.bg)
            // 터치 영역은 52dp, 눈에 보이는 모양은 사방 2dp 안쪽으로 그려 가장자리가 잘리지 않게 한다.
            background = InsetDrawable(
                if (theme.metal) {
                    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFF1F3F6.toInt(), 0xFFA9B0B9.toInt()))
                        .apply { cornerRadius = dp(24).toFloat() }
                } else {
                    GradientDrawable().apply { setColor(theme.accent); cornerRadius = dp(24).toFloat() }
                },
                dp(2),
            )
            setOnClickListener { onClose() }
        }
        val open = text("", 15f).apply {
            background = InsetDrawable(
                GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke(dp(1), (theme.text and 0x00FFFFFF) or (0x99 shl 24))
                    cornerRadius = dp(24).toFloat()
                },
                dp(2),
            )
            setOnClickListener { if (remaining <= 0) onOpen() }
        }
        openBtn = open
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        buttons.addView(close, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(open, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(10) })
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        val label = text(labelText, 16f, alpha = 0.7f)
        val msg = text(message, if (land) 28f else 34f, bold = true).apply { setPadding(0, dp(12), 0, 0) }

        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        root.addView(label, LinearLayout.LayoutParams(-2, -2))
        root.addView(msg, LinearLayout.LayoutParams(-2, -2))
        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1.2f))
        if (breathing) {
            // 안내 문구는 BreathView가 이 자리에 직접 그린다.
            val slot = View(context)
            root.addView(slot, LinearLayout.LayoutParams(-1, dp(44)))
            breath?.slot = slot
            root.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.8f))
        } else {
            root.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.8f))
        }

        scroll.addView(root, LayoutParams(-1, -1))
        refreshOpenButton()
    }

    private fun refreshOpenButton() {
        // 뷰 alpha를 쓰면 가장자리가 잘려 보이므로 글자 색의 투명도로만 흐리게 한다.
        openBtn?.apply {
            text = if (remaining > 0) "${remaining}초 후 열기" else "그래도 열기"
            setTextColor((theme.text and 0x00FFFFFF) or ((if (remaining > 0) 0x8C else 0xF2) shl 24))
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
