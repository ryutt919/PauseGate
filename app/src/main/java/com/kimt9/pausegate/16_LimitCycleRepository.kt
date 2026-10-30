package com.kimt9.pausegate

import android.content.Context
import android.content.SharedPreferences

/** 매초 확정한 누적과 실제 시각의 쿨타임 종료를 함께 저장한다. */
class LimitCycleRepository internal constructor(private val sp: SharedPreferences) {
    private val prefs = Prefs(sp)
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("pausegate", Context.MODE_PRIVATE),
    )

    fun state(pkg: String, nowMs: Long = System.currentTimeMillis()): LimitCycleState =
        addUsage(pkg, 0L, nowMs)

    fun addUsage(pkg: String, usageMs: Long, nowMs: Long): LimitCycleState {
        val previous = LimitCycleState(
            sp.getLong("cycle_used_$pkg", 0L), sp.getLong("cycle_until_$pkg", 0L),
            sp.getLong("cycle_limit_$pkg", 0L),
        )
        val next = LimitCyclePolicy.advance(
            previous, usageMs, nowMs,
            prefs.limitMin(pkg).toLong() * 60_000L,
            DurationPolicy.millis(prefs.cooldownSec),
        )
        if (next != previous) {
            save(pkg, next)
        }
        return next
    }

    /** Extend the total quota, preserving usage and the default for subsequent cycles. */
    fun extendCurrentCycle(pkg: String, hours: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (hours !in 1..24 || prefs.limitMin(pkg) <= 0) return false
        val current = state(pkg, nowMs)
        val limit = hours * 3_600_000L
        if (limit <= current.effectiveLimitMs(prefs.limitMin(pkg) * 60_000L) || limit <= current.usedMs) return false
        save(pkg, current.copy(cooldownUntilMs = 0L, limitOverrideMs = limit))
        return true
    }

    private fun save(pkg: String, state: LimitCycleState) {
        sp.edit().putLong("cycle_used_$pkg", state.usedMs)
            .putLong("cycle_until_$pkg", state.cooldownUntilMs)
            .putLong("cycle_limit_$pkg", state.limitOverrideMs).apply()
    }
}
