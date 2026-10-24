package com.kimt9.pausegate

import org.junit.Assert.assertEquals
import org.junit.Test

class CooldownRemainingTest {
    @Test fun remainingSecondsRoundUpPartialSecond() {
        assertEquals(10, DurationPolicy.cooldownRemainingSeconds(110_000L, 100_000L))
        assertEquals(10, DurationPolicy.cooldownRemainingSeconds(110_000L, 100_001L))
        assertEquals(1, DurationPolicy.cooldownRemainingSeconds(110_000L, 109_999L))
    }

    @Test fun expiredOrPastDeadlineClampsToZero() {
        assertEquals(0, DurationPolicy.cooldownRemainingSeconds(110_000L, 110_000L))
        assertEquals(0, DurationPolicy.cooldownRemainingSeconds(110_000L, 500_000L))
        assertEquals(0, DurationPolicy.cooldownRemainingSeconds(0L, 100_000L))
    }

    @Test fun justBeforeAndAfterExpiry() {
        assertEquals(1, DurationPolicy.cooldownRemainingSeconds(110_000L, 109_000L))
        assertEquals(1, DurationPolicy.cooldownRemainingSeconds(110_000L, 109_999L))
        assertEquals(0, DurationPolicy.cooldownRemainingSeconds(110_000L, 110_000L))
        assertEquals(0, DurationPolicy.cooldownRemainingSeconds(110_000L, 110_001L))
    }

    @Test fun hourBoundaryUsesMinutesBelowAndClockAtOrAbove() {
        val now = 1_000_000L
        val just = DurationPolicy.cooldownRemainingSeconds(now + 3_599_000L, now)
        assertEquals(3599, just)
        assertEquals("쿨타임 59분 59초 남음", DurationPolicy.cooldownLabel(just))
        val hour = DurationPolicy.cooldownRemainingSeconds(now + 3_600_000L, now)
        assertEquals(3600, hour)
        assertEquals("쿨타임 01:00:00 남음", DurationPolicy.cooldownLabel(hour))
    }

    @Test fun maximumCooldownOfOneDay() {
        val now = 1_800_000_000_000L
        val seconds = DurationPolicy.cooldownRemainingSeconds(now + 86_400_000L, now)
        assertEquals(86_400, seconds)
        assertEquals("쿨타임 24:00:00 남음", DurationPolicy.cooldownLabel(seconds))
    }

    @Test fun labelFormatsShortRemainders() {
        assertEquals("쿨타임 30초 남음", DurationPolicy.cooldownLabel(30))
        assertEquals("쿨타임 1분 1초 남음", DurationPolicy.cooldownLabel(61))
        assertEquals("쿨타임 1분 남음", DurationPolicy.cooldownLabel(60))
        assertEquals("쿨타임 23:59:59 남음", DurationPolicy.cooldownLabel(86_399))
    }
}
