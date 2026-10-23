package com.kimt9.pausegate

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** 홈에서 들어오는 설정 화면. */
class SettingsActivity : ThemedActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(40))
        }
        setContentView(ScrollView(this).apply { background = screenTheme.backgroundDrawable(); addView(content) })
    }

    // 설정 화면에 다녀오면 상태가 바뀌어 있으므로 매번 다시 그린다.
    override fun onResume() {
        super.onResume()
        applyScreenTheme()
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(actionButton("‹ 홈으로", false) { finish() })
        title("설정")
        body("나에게 맞는 멈춤을 설정하세요.")

        section("1. 권한")
        permissionRow("접근성 서비스 (필수)", Util.isGateEnabled(this), "켜기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permissionRow("사용 정보 접근 (사용 통계와 재연결용)", Util.hasUsageAccess(this), "허용") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        permissionRow("배터리 최적화 제외 (권장)", null, "열기") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        section("2. 개입 대상")
        content.addView(Switch(this).apply {
            setTextColor(screenTheme.text)
            text = "You sure? 사용"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on }
        })
        content.addView(actionButton("앱 선택 (${prefs.gated.size}개 선택됨)", false) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        })

        section("3. 동작")
        durationRow("평소 기다리는 시간", prefs.delaySec, 3) { prefs.delaySec = it }
        durationRow("쿨타임 동안 기다리는 시간", prefs.limitDelaySec, 1) { prefs.limitDelaySec = it }
        durationRow("한도 소진 후 쿨타임 기간", prefs.cooldownSec, 1) { prefs.cooldownSec = it }
        body("쿨타임 중에도 위 대기 시간이 지나면 앱을 열 수 있어요. 쿨타임 기간이 끝나면 이용 한도가 다시 주어져요.")
        durationRow("그래도 열기 후 다시 묻지 않는 시간", prefs.graceSec, 1) { prefs.graceSec = it }
        content.addView(Switch(this).apply {
            setTextColor(screenTheme.text)
            text = "숨쉬기 연출 보이기"
            isChecked = prefs.breathing
            setOnCheckedChangeListener { _, on -> prefs.breathing = on }
        })

        section("4. 디자인")
        label("문구")
        content.addView(EditText(this).apply {
            setTextColor(screenTheme.text)
            backgroundTintList = android.content.res.ColorStateList.valueOf(screenTheme.accent)
            setText(prefs.message)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { prefs.message = s?.toString().orEmpty() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        label("색 조합")
        themeChips()
        content.addView(actionButton("개입 화면 미리보기", false) { preview() })

        content.addView(actionButton("통계 보기", false) {
            startActivity(Intent(this, StatsActivity::class.java))
        })
    }

    // ---- 작은 UI 도우미 ----
    private fun title(s: String) = content.addView(screenText(s, 30f, bold = true))
    private fun section(s: String) = content.addView(screenText(s, 18f, bold = true).apply {
        setPadding(0, px(28), 0, px(8))
    })
    private fun body(s: String) = content.addView(screenText(s, 14f).apply {
        setPadding(0, px(4), 0, px(4))
    })
    private fun label(s: String) = content.addView(screenText(s, 14f).apply {
        setPadding(0, px(10), 0, 0)
    })

    /** ok가 null이면 상태를 알 수 없는 항목이라 버튼만 보여 준다. */
    private fun permissionRow(name: String, ok: Boolean?, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val state = when (ok) { true -> "켜짐"; false -> "꺼짐"; null -> "" }
        row.addView(
            screenText(if (state.isEmpty()) name else "$name  [$state]", 14f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (ok != true) row.addView(actionButton(action, false, onClick))
        content.addView(row)
    }

    private fun durationRow(name: String, value: Int, min: Int, onChange: (Int) -> Unit) {
        content.addView(screenText(name, 16f).apply { setPadding(0, px(16), 0, 0) })
        content.addView(actionButton(DurationPolicy.format(value), false) {
            showDurationPicker(this, name, value, min) {
                onChange(it)
                val scroll = content.parent as ScrollView
                val y = scroll.scrollY
                render()
                scroll.post { scroll.scrollTo(0, y) }
            }
        })
    }

    private fun themeChips() {
        Themes.all.chunked(3).forEachIndexed { rowIndex, themes ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            themes.forEachIndexed { column, t ->
                val index = rowIndex * 3 + column
                row.addView(
                    TextView(this).apply {
                        text = t.name + if (index == prefs.themeIdx) " ✓" else ""
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(t.text)
                        background = t.backgroundDrawable().apply {
                            cornerRadius = px(12).toFloat()
                            setStroke(px(if (index == prefs.themeIdx) 3 else 1), t.accent)
                        }
                        isFocusable = true
                        setOnClickListener {
                            val scroll = content.parent as ScrollView
                            val y = scroll.scrollY
                            prefs.themeIdx = index
                            applyScreenTheme()
                            scroll.background = screenTheme.backgroundDrawable()
                            render()
                            scroll.post { scroll.scrollTo(0, y) }
                        }
                    },
                    LinearLayout.LayoutParams(0, px(52), 1f).apply { setMargins(px(3), px(6), px(3), px(6)) },
                )
            }
            content.addView(row)
        }
    }

    private fun preview() {
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.setContentView(
            OverlayView(
                context = this, theme = prefs.theme, appLabel = "미리보기",
                message = prefs.message, delaySec = prefs.delaySec, limitDelaySec = prefs.limitDelaySec,
                breathing = prefs.breathing, limitReached = false,
                onOpen = { dialog.dismiss() }, onClose = { dialog.dismiss() },
            ),
        )
        dialog.show()
    }
}
