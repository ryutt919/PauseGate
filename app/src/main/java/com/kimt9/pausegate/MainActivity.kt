package com.kimt9.pausegate

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch

/** 실행 첫 화면. 통계 조회는 StatsDb에, 설정 저장은 Prefs에 맡긴다. */
class MainActivity : ThemedActivity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(40))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        applyScreenTheme()
        scroll.background = screenTheme.backgroundDrawable()
        render()
    }

    private fun space(value: Int = 20) = content.addView(android.view.View(this), LinearLayout.LayoutParams(1, px(value)))

    private fun render() {
        content.removeAllViews()
        content.addView(screenText("멈칫", 34f, true))
        content.addView(screenText("잠깐의 멈춤, 나를 위한 선택", 16f).apply { setPadding(0, px(8), 0, px(20)) })
        val status = surface()
        val gateEnabled = Util.isGateEnabled(this)
        status.addView(Switch(this).apply {
            text = "멈칫 사용"
            textSize = 17f
            minHeight = px(48)
            setTextColor(screenTheme.text)
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, enabled -> prefs.enabled = enabled; render() }
        })
        status.addView(screenText(when {
            !prefs.enabled -> "지금은 쉬고 있어요"
            !gateEnabled -> "접근성 서비스를 켜 주세요"
            prefs.gated.isEmpty() -> "멈추고 싶은 앱을 선택해 주세요"
            else -> "${prefs.gated.size}개 앱에서 잠깐 멈추는 중"
        }, 16f).apply { setPadding(0, px(8), 0, 0) })
        if (!gateEnabled || prefs.gated.isEmpty()) status.addView(actionButton("설정에서 준비하기", false) { settings() })
        content.addView(status)
        space(28)
        val db = StatsDb.get(this)
        val today = StatsDb.today()
        val stats = db.dayStats(today, today)[today]
        val shown = stats?.shown ?: 0
        val resisted = stats?.resisted ?: 0
        content.addView(screenText("오늘의 멈춤", 21f, true))
        space(12)
        val summary = surface()
        summary.addView(screenText(if (shown == 0) "아직 기록이 없어요" else "${Math.round(resisted * 100f / shown)}%", 36f, true))
        summary.addView(screenText(if (shown == 0) "선택한 앱을 열면 통계가 쌓여요" else "오늘 앱을 열지 않고 참은 비율", 15f))
        summary.addView(screenText("개입 ${shown}회 / 참음 ${resisted}회\n그래도 열기 ${stats?.opened ?: 0}회", 16f).apply {
            setPadding(0, px(16), 0, px(12))
            setLineSpacing(px(4).toFloat(), 1f)
        })
        summary.addView(actionButton("통계 보기") { startActivity(Intent(this, StatsActivity::class.java)) })
        content.addView(summary)
        space()
        content.addView(actionButton("설정", false) { settings() })
        space(12)
        content.addView(screenText("일반 대기 ${DurationPolicy.format(prefs.delaySec)}\n일한도 초과 ${DurationPolicy.format(prefs.limitDelaySec)}\n다시 묻지 않기 ${DurationPolicy.format(prefs.graceSec)}", 14f).apply {
            setLineSpacing(px(4).toFloat(), 1f)
            gravity = Gravity.START
        })
    }
    private fun settings() = startActivity(Intent(this, SettingsActivity::class.java))
}
