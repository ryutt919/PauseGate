package com.kimt9.pausegate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable

/**
 * 화면 색 조합. 설정에서 고르고, 개입 화면에 그대로 반영된다.
 * bg는 물에 잠긴 글자와 버튼 글자 색으로도 쓰인다. metal이면 배경, 버튼, 물결에 금속 그라데이션을 쓴다.
 */
data class Theme(val name: String, val bg: Int, val accent: Int, val text: Int, val metal: Boolean = false) {
    fun backgroundDrawable(): GradientDrawable =
        if (metal) {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF4A4E55.toInt(), 0xFF2B2E33.toInt(), 0xFF1B1D21.toInt()),
            )
        } else {
            GradientDrawable().apply { setColor(bg) }
        }
}

object Themes {
    val all = listOf(
        Theme("숲", 0xFF10211C.toInt(), 0xFF5FD3AE.toInt(), 0xFFEAF5F0.toInt()),
        Theme("밤바다", 0xFF0E1B2E.toInt(), 0xFF6AA9FF.toInt(), 0xFFE8EEF8.toInt()),
        Theme("노을", 0xFF2A1618.toInt(), 0xFFFF9A76.toInt(), 0xFFFBEDE8.toInt()),
        Theme("라벤더", 0xFF1D1830.toInt(), 0xFFB79CFF.toInt(), 0xFFF0EBFF.toInt()),
        Theme("종이", 0xFFF5F1E8.toInt(), 0xFF3F7D6E.toInt(), 0xFF222222.toInt()),
        Theme("메탈", 0xFF1E2024.toInt(), 0xFFB9BEC5.toInt(), 0xFFF2F3F5.toInt(), metal = true),
    )
}

class Prefs internal constructor(private val sp: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("pausegate", Context.MODE_PRIVATE),
    )

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var gated: Set<String>
        get() = sp.getStringSet("gated", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("gated", HashSet(v)).apply()

    /** 개입 화면에서 "그냥 열기"를 누를 수 있기까지 기다리는 초 */
    var delaySec: Int
        get() = sp.getInt("delaySec", 7).coerceIn(DurationPolicy.MIN_DELAY_SECONDS, DurationPolicy.MAX_SECONDS)
        set(v) {
            require(v in DurationPolicy.MIN_DELAY_SECONDS..DurationPolicy.MAX_SECONDS)
            sp.edit().putInt("delaySec", v).apply()
        }

    /** "그래도 열기" 후 다시 묻지 않는 초. 기존 분 단위 설정을 최초 접근 시 보존한다. */
    var graceSec: Int
        get() {
            if (!sp.contains("graceSec")) {
                val seconds = DurationPolicy.legacyGraceSeconds(sp.getInt("graceMin", 5))
                sp.edit().putInt("graceSec", seconds).apply()
            }
            return sp.getInt("graceSec", 300).coerceIn(1, DurationPolicy.MAX_SECONDS)
        }
        set(v) {
            require(v in 1..DurationPolicy.MAX_SECONDS)
            sp.edit().putInt("graceSec", v).apply()
        }

    /** 쿨타임 기간 중 개입 화면에서 기다리는 초. 기존 설정 키를 보존한다. */
    var limitDelaySec: Int
        get() = sp.getInt("limitDelaySec", DurationPolicy.DEFAULT_LIMIT_DELAY_SECONDS)
            .coerceIn(1, DurationPolicy.MAX_SECONDS)
        set(v) {
            require(v in 1..DurationPolicy.MAX_SECONDS)
            sp.edit().putInt("limitDelaySec", v).apply()
        }

    /** 한도 소진 후 다른 대기 시간을 적용하는 기간. */
    var cooldownSec: Int
        get() = sp.getInt("cooldownSec", 1_800).coerceIn(1, DurationPolicy.MAX_SECONDS)
        set(v) {
            require(v in 1..DurationPolicy.MAX_SECONDS)
            sp.edit().putInt("cooldownSec", v).apply()
        }

    /** 서비스가 다시 시작되어도 유예를 유지하기 위해 실제 시각으로 저장한다. */
    fun graceUntilMs(pkg: String): Long = sp.getLong("graceUntil_$pkg", 0L)
    fun setGraceUntilMs(pkg: String, untilMs: Long) =
        sp.edit().putLong("graceUntil_$pkg", untilMs).apply()

    var message: String
        get() = sp.getString("message", "정말 지금 열어야 할까요?") ?: ""
        set(v) = sp.edit().putString("message", v).apply()

    var themeIdx: Int
        get() = sp.getInt("themeIdx", 0).coerceIn(0, Themes.all.lastIndex)
        set(v) = sp.edit().putInt("themeIdx", v).apply()

    var breathing: Boolean
        get() = sp.getBoolean("breathing", true)
        set(v) = sp.edit().putBoolean("breathing", v).apply()

    val theme: Theme get() = Themes.all[themeIdx]

    /** 앱별 주기당 사용 한도(분). 0이면 한도 없음. */
    fun limitMin(pkg: String): Int = sp.getInt("limit_$pkg", 0)
    fun setLimitMin(pkg: String, min: Int) {
        require(min >= 0)
        if (min != limitMin(pkg)) {
            sp.edit().putInt("limit_$pkg", min)
                .remove("cycle_used_$pkg").remove("cycle_until_$pkg").remove("cycle_limit_$pkg").apply()
        }
    }
}
