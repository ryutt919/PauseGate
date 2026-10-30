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

    @Test fun extensionPreservesUsageAndDefaultAndSurvivesRestartPerApp() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 30, "limit_second" to 30))
        val repository = LimitCycleRepository(store.preferences)
        repository.addUsage("first", 600_000L, 100_000L)
        assertTrue(repository.extendCurrentCycle("first", 2, 110_000L))
        val restarted = LimitCycleRepository(store.preferences)
        val state = restarted.state("first", 120_000L)
        assertEquals(600_000L, state.usedMs)
        assertEquals(7_200_000L, state.effectiveLimitMs(1_800_000L))
        assertEquals(30, Prefs(store.preferences).limitMin("first"))
        assertEquals(LimitCycleState(), restarted.state("second", 120_000L))
    }

    @Test fun extendedQuotaStartsCooldownAndExpiryRestoresDefault() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 30))
        val repository = LimitCycleRepository(store.preferences)
        assertTrue(repository.extendCurrentCycle("first", 1, 100_000L))
        assertFalse(repository.addUsage("first", 3_599_999L, 110_000L).isCoolingDown(110_000L))
        val full = repository.addUsage("first", 1L, 111_000L)
        assertEquals(141_000L, full.cooldownUntilMs)
        assertEquals(3_600_000L, full.limitOverrideMs)
        assertEquals(LimitCycleState(), repository.state("first", 141_000L))
        assertTrue(repository.addUsage("first", 60_000L, 142_000L).isCoolingDown(142_000L))
    }

    @Test fun extendingDuringCooldownResumesSameUsageWithoutChangingGrace() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 1, "cooldownSec" to 30, "graceUntil_first" to 99L))
        val repository = LimitCycleRepository(store.preferences)
        repository.addUsage("first", 60_000L, 100_000L)
        assertTrue(repository.extendCurrentCycle("first", 1, 110_000L))
        assertEquals(LimitCycleState(60_000L, 0L, 3_600_000L), repository.state("first", 110_000L))
        assertEquals(99L, Prefs(store.preferences).graceUntilMs("first"))
    }

    @Test fun rejectsInvalidHoursUnlimitedAppsAndQuotaReduction() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 60))
        val repository = LimitCycleRepository(store.preferences)
        for (hours in listOf(-1, 0, 1, 25, Int.MAX_VALUE)) assertFalse(repository.extendCurrentCycle("first", hours, 100_000L))
        assertFalse(repository.extendCurrentCycle("unlimited", 2, 100_000L))
        assertTrue(repository.extendCurrentCycle("first", 24, 100_000L))
        assertFalse(repository.extendCurrentCycle("first", 23, 100_000L))
        assertFalse(repository.extendCurrentCycle("first", 24, 100_000L))
        assertEquals(86_400_000L, repository.state("first", 100_000L).limitOverrideMs)
    }

    @Test fun changingDefaultClearsOverrideButSameDefaultAndMidnightPreserveIt() {
        val store = MemoryPreferences(mutableMapOf("limit_first" to 30))
        val repository = LimitCycleRepository(store.preferences)
        assertTrue(repository.extendCurrentCycle("first", 2, 86_399_000L))
        val prefs = Prefs(store.preferences)
        prefs.setLimitMin("first", 30)
        assertEquals(7_200_000L, repository.state("first", 86_401_000L).limitOverrideMs)
        prefs.setLimitMin("first", 60)
        assertEquals(LimitCycleState(), repository.state("first", 86_402_000L))
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
