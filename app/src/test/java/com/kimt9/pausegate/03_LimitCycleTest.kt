package com.kimt9.pausegate

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitCycleTest {
    private fun advance(state: LimitCycleState, usage: Long, now: Long) =
        LimitCyclePolicy.advance(state, usage, now, 60_000L, 30_000L)

    @Test fun quotaStartsCooldownPeriodAtExactThreshold() {
        val partial = advance(LimitCycleState(), 59_000L, 100_000L)
        assertEquals(59_000L, partial.usedMs)
        assertFalse(partial.isCoolingDown(100_000L))
        val blocked = advance(partial, 1_000L, 101_000L)
        assertEquals(60_000L, blocked.usedMs)
        assertEquals(131_000L, blocked.cooldownUntilMs)
        assertTrue(blocked.isCoolingDown(130_999L))
    }

    @Test fun cooldownIgnoresUsageAndExpiresWhileAppIsClosed() {
        val blocked = advance(LimitCycleState(), 60_000L, 100_000L)
        assertEquals(blocked, advance(blocked, 20_000L, 120_000L))
        assertEquals(LimitCycleState(), advance(blocked, 0L, 130_000L))
    }

    @Test fun freshQuotaCanBeExhaustedRepeatedly() {
        val first = advance(LimitCycleState(), 60_000L, 100_000L)
        val second = advance(advance(first, 0L, 130_000L), 60_000L, 190_000L)
        assertEquals(LimitCycleState(60_000L, 220_000L), second)
    }

    @Test fun midnightDoesNotResetPartialQuota() {
        val partial = advance(LimitCycleState(), 40_000L, 86_399_000L)
        assertEquals(40_000L, advance(partial, 0L, 86_401_000L).usedMs)
        assertTrue(advance(partial, 20_000L, 86_420_000L).isCoolingDown(86_420_000L))
    }

    @Test fun noLimitDoesNotAccumulateOrBlock() {
        assertEquals(LimitCycleState(), LimitCyclePolicy.advance(
            LimitCycleState(60_000L, 200_000L), 5_000L, 100_000L, 0L, 30_000L,
        ))
    }

    @Test fun fullDayCooldownUsesLongMilliseconds() {
        val state = LimitCyclePolicy.advance(LimitCycleState(), 60_000L, 1_800_000_000_000L,
            60_000L, DurationPolicy.millis(86_400))
        assertEquals(1_800_086_400_000L, state.cooldownUntilMs)
    }

    @Test fun usageAndCooldownSurviveRepositoryRecreationAndStayPerApp() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "limit_second" to 1, "cooldownSec" to 30))
        val first = LimitCycleRepository(store.preferences)
        first.addUsage("first", 40_000L, 100_000L)
        val restarted = LimitCycleRepository(store.preferences)
        assertEquals(40_000L, restarted.state("first", 110_000L).usedMs)
        assertEquals(LimitCycleState(), restarted.state("second", 110_000L))
        restarted.addUsage("first", 20_000L, 120_000L)
        assertTrue(LimitCycleRepository(store.preferences).state("first", 130_000L).isCoolingDown(130_000L))
        assertEquals(LimitCycleState(), LimitCycleRepository(store.preferences).state("first", 150_000L))
    }

    @Test fun changedQuotaStartsNewCycleButSameValuePreservesCurrentCycle() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 30))
        val repo = LimitCycleRepository(store.preferences)
        repo.addUsage("first", 40_000L, 100_000L)
        val prefs = Prefs(store.preferences)
        prefs.setLimitMin("first", 1)
        assertEquals(40_000L, repo.state("first", 110_000L).usedMs)
        prefs.setLimitMin("first", 2)
        assertEquals(LimitCycleState(), repo.state("first", 110_000L))
    }

    @Test fun changingQuotaAlsoClearsExistingCooldown() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 30))
        val repo = LimitCycleRepository(store.preferences)
        repo.addUsage("first", 60_000L, 100_000L)
        Prefs(store.preferences).setLimitMin("first", 2)
        assertEquals(LimitCycleState(), repo.state("first", 110_000L))
    }

    @Test fun ordinaryGraceCannotChangeCycleState() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 30))
        val repo = LimitCycleRepository(store.preferences)
        val blocked = repo.addUsage("first", 60_000L, 100_000L)
        Prefs(store.preferences).setGraceUntilMs("first", 86_500_000L)
        assertEquals(blocked, repo.state("first", 110_000L))
    }

    @Test fun repositoryUsesSameCooldownBoundsAsSettings() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 0))
        val blocked = LimitCycleRepository(store.preferences).addUsage("first", 60_000L, 100_000L)
        assertEquals(101_000L, blocked.cooldownUntilMs)
    }

    @Test fun cooldownPeriodAndWaitHaveIndependentDefaultsAndSavedValues() {
        val store = MemoryPreferences(mutableMapOf("limitDelaySec" to 25))
        val prefs = Prefs(store.preferences)
        assertEquals(1_800, prefs.cooldownSec)
        assertEquals(25, prefs.limitDelaySec)
        prefs.cooldownSec = 86_400
        assertEquals(25, prefs.limitDelaySec)
        prefs.limitDelaySec = 6
        assertEquals(86_400, prefs.cooldownSec)
    }

    @Test fun changingWaitDoesNotExtendCurrentCooldownPeriod() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 120, "limitDelaySec" to 6))
        val repo = LimitCycleRepository(store.preferences)
        val state = repo.addUsage("first", 60_000L, 100_000L)
        assertEquals(220_000L, state.cooldownUntilMs)
        Prefs(store.preferences).limitDelaySec = 30
        assertEquals(state, repo.state("first", 110_000L))
        assertEquals(state, repo.addUsage("first", 60_000L, 120_000L))
        assertEquals(LimitCycleState(), repo.state("first", 220_000L))
    }

    private class MemoryPreferences(val values: MutableMap<String, Any> = mutableMapOf()) {
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putInt", "putLong" -> { values[args!![0] as String] = args[1]; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "apply" -> null
                else -> error("지원하지 않는 Editor 호출: ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt", "getLong" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("지원하지 않는 Preferences 호출: ${method.name}")
            }
        } as SharedPreferences
    }
}
