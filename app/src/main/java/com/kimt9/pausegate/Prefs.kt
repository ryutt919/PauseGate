package com.kimt9.pausegate

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면 색 조합. 설정에서 고르고, 개입 화면에 그대로 반영된다. */
data class Theme(val name: String, val bg: Int, val accent: Int, val text: Int)

object Themes {
    val all = listOf(
        Theme("숲", 0xFF10211C.toInt(), 0xFF5FD3AE.toInt(), 0xFFEAF5F0.toInt()),
        Theme("밤바다", 0xFF0E1B2E.toInt(), 0xFF6AA9FF.toInt(), 0xFFE8EEF8.toInt()),
        Theme("노을", 0xFF2A1618.toInt(), 0xFFFF9A76.toInt(), 0xFFFBEDE8.toInt()),
        Theme("라벤더", 0xFF1D1830.toInt(), 0xFFB79CFF.toInt(), 0xFFF0EBFF.toInt()),
        Theme("종이", 0xFFF5F1E8.toInt(), 0xFF3F7D6E.toInt(), 0xFF222222.toInt()),
    )
}

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("pausegate", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var gated: Set<String>
        get() = sp.getStringSet("gated", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("gated", HashSet(v)).apply()

    /** 개입 화면에서 "그냥 열기"를 누를 수 있기까지 기다리는 초 */
    var delaySec: Int
        get() = sp.getInt("delaySec", 7)
        set(v) = sp.edit().putInt("delaySec", v).apply()

    /** "그냥 열기" 후 다시 묻지 않는 분 */
    var graceMin: Int
        get() = sp.getInt("graceMin", 5)
        set(v) = sp.edit().putInt("graceMin", v).apply()

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

    /** 앱별 하루 사용 한도(분). 0이면 한도 없음. */
    fun limitMin(pkg: String): Int = sp.getInt("limit_$pkg", 0)
    fun setLimitMin(pkg: String, min: Int) = sp.edit().putInt("limit_$pkg", min).apply()

    // ---- 통계: 날짜별 개입 횟수 ----
    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    enum class Stat { SHOWN, CLOSED, OPENED }

    fun bump(stat: Stat) {
        val key = "stat_${today()}_${stat.name}"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun statToday(stat: Stat): Int = sp.getInt("stat_${today()}_${stat.name}", 0)

    fun statTotal(stat: Stat): Int =
        sp.all.entries.filter { it.key.startsWith("stat_") && it.key.endsWith("_${stat.name}") }
            .sumOf { (it.value as? Int) ?: 0 }
}
