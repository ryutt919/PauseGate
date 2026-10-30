package com.kimt9.pausegate

data class LimitCycleState(
    val usedMs: Long = 0L,
    val cooldownUntilMs: Long = 0L,
    val limitOverrideMs: Long = 0L,
) {
    fun isCoolingDown(nowMs: Long): Boolean = cooldownUntilMs > nowMs
    fun effectiveLimitMs(defaultMs: Long): Long = maxOf(defaultMs, limitOverrideMs)
}

/** 날짜와 독립된 사용 한도와 쿨타임의 반복 정책. */
object LimitCyclePolicy {
    fun advance(
        state: LimitCycleState,
        usageMs: Long,
        nowMs: Long,
        limitMs: Long,
        cooldownMs: Long,
    ): LimitCycleState {
        require(usageMs >= 0L && cooldownMs > 0L)
        if (limitMs <= 0L) return LimitCycleState()
        if (state.isCoolingDown(nowMs)) return state
        val current = if (state.cooldownUntilMs > 0L) LimitCycleState() else state
        val effectiveLimit = current.effectiveLimitMs(limitMs)
        val used = current.usedMs + usageMs.coerceAtMost(effectiveLimit)
        return if (used >= effectiveLimit) {
            current.copy(usedMs = effectiveLimit, cooldownUntilMs = nowMs + cooldownMs)
        } else current.copy(usedMs = used)
    }
}
