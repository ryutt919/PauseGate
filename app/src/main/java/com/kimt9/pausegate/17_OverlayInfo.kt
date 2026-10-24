package com.kimt9.pausegate

/**
 * 개입 화면 요약 패널에 보여줄 값.
 * @param usedTodayMs 오늘 이 앱 사용 시간(ms). 사용 정보 접근 권한이 없어 알 수 없으면 null(0과 다르다).
 * @param shown 오늘 이 앱의 끝난 개입 횟수. 현재 개입은 포함하지 않는다.
 * @param kept 그중 참은 횟수(열지 않은 개입). StatsDb의 DayStat.resisted와 같은 정의다.
 */
data class OverlayInfo(val usedTodayMs: Long? = null, val shown: Int = 0, val kept: Int = 0)

/** 요약 패널 문구. Android에 의존하지 않는다. */
object OverlayInfoFormat {
    /** 사용 시간. 시/분 단위로만 쓰고 1분 미만은 "1분 미만"이다. */
    fun usedDuration(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0L) / 60_000L)
        if (minutes < 1L) return "1분 미만"
        val h = minutes / 60
        val m = minutes % 60
        return buildList {
            if (h > 0) add("${h}시간")
            if (m > 0) add("${m}분")
        }.joinToString(" ")
    }

    fun usedRow(appLabel: String, ms: Long): String = "오늘 $appLabel 사용 ${usedDuration(ms)}"

    /** 참은 비율(%). StatsActivity와 같은 반올림이며 개입이 0이면 0이다. */
    fun keptPercent(shown: Int, kept: Int): Int =
        if (shown <= 0) 0 else Math.round(kept.coerceIn(0, shown) * 100f / shown)

    /** 통계 한 줄. 홈의 전체 합계와 구분하도록 "이 앱"을 붙인다. 오늘 개입이 없으면 null. */
    fun statsRow(shown: Int, kept: Int): String? {
        if (shown <= 0) return null
        return "이 앱 오늘 개입 ${shown}회, 참음 ${kept.coerceIn(0, shown)}회 (${keptPercent(shown, kept)}%)"
    }

    /** 화면 낭독용 통계 문구. 기호 대신 말로 풀어 쓴다. */
    fun statsSpoken(shown: Int, kept: Int): String? {
        if (shown <= 0) return null
        return "이 앱 오늘 개입 ${shown}회, 참음 ${kept.coerceIn(0, shown)}회, 참은 비율 ${keptPercent(shown, kept)}퍼센트"
    }
}
