package com.kimt9.pausegate

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** 권한 상태, 전체 설정, 디자인 선택, 통계를 한 화면에 모았다. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private val dp: Float get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(40))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    // 설정 화면에 다녀오면 상태가 바뀌어 있으므로 매번 다시 그린다.
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        title("멈칫")
        body("앱을 열기 전에 잠깐 멈추고 숨을 고릅니다.")

        section("1. 권한")
        permissionRow("접근성 서비스 (필수)", Util.isGateEnabled(this), "켜기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permissionRow("사용 정보 접근 (사용 시간 한도용)", Util.hasUsageAccess(this), "허용") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        permissionRow("배터리 최적화 제외 (권장)", null, "열기") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        section("2. 개입 대상")
        content.addView(Switch(this).apply {
            text = "멈칫 사용"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on }
        })
        content.addView(Button(this).apply {
            text = "앱 선택 (${prefs.gated.size}개 선택됨)"
            setOnClickListener { startActivity(Intent(this@MainActivity, AppPickerActivity::class.java)) }
        })

        section("3. 동작")
        slider("기다리는 시간", prefs.delaySec, 3, 30, "초") { prefs.delaySec = it }
        slider("그래도 열기 후 다시 묻지 않는 시간", prefs.graceMin, 1, 30, "분") { prefs.graceMin = it }
        content.addView(Switch(this).apply {
            text = "숨쉬기 연출 보이기"
            isChecked = prefs.breathing
            setOnCheckedChangeListener { _, on -> prefs.breathing = on }
        })

        section("4. 디자인")
        label("문구")
        content.addView(EditText(this).apply {
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
        content.addView(Button(this).apply {
            text = "개입 화면 미리보기"
            setOnClickListener { preview() }
        })

        section("5. 통계")
        body("오늘 개입 ${prefs.statToday(Prefs.Stat.SHOWN)}회 / 닫기 ${prefs.statToday(Prefs.Stat.CLOSED)}회 / 그래도 열기 ${prefs.statToday(Prefs.Stat.OPENED)}회")
        body("누적 개입 ${prefs.statTotal(Prefs.Stat.SHOWN)}회 / 닫기 ${prefs.statTotal(Prefs.Stat.CLOSED)}회 / 그래도 열기 ${prefs.statTotal(Prefs.Stat.OPENED)}회")
        usageList()
    }

    // ---- 작은 UI 도우미 ----
    private fun title(s: String) = content.addView(TextView(this).apply {
        text = s; textSize = 30f; setTypeface(typeface, Typeface.BOLD)
    })

    private fun section(s: String) = content.addView(TextView(this).apply {
        text = s; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, px(28), 0, px(8))
    })

    private fun body(s: String) = content.addView(TextView(this).apply {
        text = s; textSize = 14f; setPadding(0, px(2), 0, px(2))
    })

    private fun label(s: String) = content.addView(TextView(this).apply {
        text = s; textSize = 13f; alpha = 0.7f; setPadding(0, px(10), 0, 0)
    })

    /** ok가 null이면 상태를 알 수 없는 항목이라 버튼만 보여 준다. */
    private fun permissionRow(name: String, ok: Boolean?, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val state = when (ok) { true -> "켜짐"; false -> "꺼짐"; null -> "" }
        row.addView(
            TextView(this).apply { text = if (state.isEmpty()) name else "$name  [$state]"; textSize = 14f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (ok != true) row.addView(Button(this).apply { text = action; setOnClickListener { onClick() } })
        content.addView(row)
    }

    private fun slider(name: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
        val text = TextView(this).apply { textSize = 14f; setPadding(0, px(8), 0, 0) }
        fun show(v: Int) { text.text = "$name: $v$unit" }
        show(value)
        content.addView(text)
        content.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    show(p + min)
                    if (fromUser) onChange(p + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
    }

    private fun themeChips() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Themes.all.forEachIndexed { i, t ->
            row.addView(
                TextView(this).apply {
                    text = t.name
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(t.text)
                    background = t.backgroundDrawable().apply {
                        cornerRadius = px(12).toFloat()
                        setStroke(px(if (i == prefs.themeIdx) 3 else 1), t.accent)
                    }
                    setOnClickListener { prefs.themeIdx = i; render() }
                },
                LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(3), px(6), px(3), px(6)) },
            )
        }
        content.addView(row)
    }

    private fun usageList() {
        if (!Util.hasUsageAccess(this)) {
            body("사용 정보 접근을 허용하면 오늘 사용 시간이 여기에 보입니다.")
            return
        }
        val rows = prefs.gated
            .map { it to Util.usedTodayMs(this, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(8)
        label("오늘 사용 시간 (선택한 앱)")
        if (rows.isEmpty()) body("아직 기록이 없습니다.")
        rows.forEach { (pkg, ms) ->
            val limit = prefs.limitMin(pkg)
            body("${Util.labelOf(this, pkg)}: ${ms / 60_000}분" + if (limit > 0) " / 한도 ${limit}분" else "")
        }
    }

    private fun preview() {
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.setContentView(
            OverlayView(
                context = this, theme = prefs.theme, appLabel = "미리보기",
                message = prefs.message, delaySec = prefs.delaySec,
                breathing = prefs.breathing, limitReached = false,
                onOpen = { dialog.dismiss() }, onClose = { dialog.dismiss() },
            ),
        )
        dialog.show()
    }
}
