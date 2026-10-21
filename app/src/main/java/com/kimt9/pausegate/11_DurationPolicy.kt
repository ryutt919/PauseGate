package com.kimt9.pausegate

/** 24시간 범위의 시간 설정과 표시. Android에 의존하지 않는다. */
object DurationPolicy {
    const val MAX_SECONDS = 86_400
    const val MIN_DELAY_SECONDS = 3
    const val DEFAULT_LIMIT_DELAY_SECONDS = 30

    fun legacyGraceSeconds(minutes: Int): Int =
        (minutes.toLong() * 60L).coerceIn(1L, MAX_SECONDS.toLong()).toInt()

    fun millis(seconds: Int): Long = seconds.toLong() * 1_000L

    /** 콜백이 늦거나 기기가 잠들어도 실제 경과 시간으로 남은 초를 계산한다. */
    fun remainingSeconds(deadlineMs: Long, nowMs: Long): Int =
        ((deadlineMs - nowMs).coerceAtLeast(0L).plus(999L) / 1_000L).toInt()

    /** 작은 버튼에서는 한 시간 이상을 HH:MM:SS로 표시한다. */
    fun formatCountdown(seconds: Int): String =
        if (seconds >= 3600) {
            listOf(seconds / 3600, seconds % 3600 / 60, seconds % 60)
                .joinToString(":") { it.toString().padStart(2, '0') }
        } else {
            format(seconds)
        }

    fun format(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val rest = seconds % 60
        return buildList {
            if (hours > 0) add("${hours}시간")
            if (minutes > 0) add("${minutes}분")
            if (rest > 0 || isEmpty()) add("${rest}초")
        }.joinToString(" ")
    }
}
