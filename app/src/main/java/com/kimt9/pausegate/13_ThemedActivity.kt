package com.kimt9.pausegate

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 홈과 설정이 통계 화면의 색 조합을 함께 사용한다. */
abstract class ThemedActivity : Activity() {
    protected lateinit var prefs: Prefs
    protected val screenTheme: Theme get() = prefs.theme
    protected fun px(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyScreenTheme()
    }

    protected fun applyScreenTheme() {
        window.statusBarColor = screenTheme.bg
        window.navigationBarColor = screenTheme.bg
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Color.luminance(screenTheme.bg) > 0.5) {
            window.decorView.systemUiVisibility or light
        } else window.decorView.systemUiVisibility and light.inv()
    }

    protected fun tint(alpha: Int) = (screenTheme.text and 0x00FFFFFF) or (alpha shl 24)
    protected fun screenText(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(screenTheme.text)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    protected fun surface() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(20), px(20), px(20))
        background = GradientDrawable().apply { setColor(tint(0x14)); cornerRadius = px(16).toFloat() }
    }
    protected fun actionButton(label: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = px(52)
        setPadding(px(16), px(10), px(16), px(10))
        setTextColor(if (primary) {
            if (Color.luminance(screenTheme.bg) > 0.5) Color.WHITE else screenTheme.bg
        } else screenTheme.text)
        val shape = GradientDrawable().apply {
            setColor(if (primary) screenTheme.accent else tint(0x14))
            cornerRadius = px(14).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(tint(0x30)), shape, null)
        stateListAnimator = null
        setOnClickListener { action() }
    }
}
