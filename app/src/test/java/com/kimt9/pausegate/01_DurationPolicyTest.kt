package com.kimt9.pausegate

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationPolicyTest {
    @Test fun legacyMinutesBecomeSecondsWithoutOverflow() {
        assertEquals(300, DurationPolicy.legacyGraceSeconds(5))
        assertEquals(1800, DurationPolicy.legacyGraceSeconds(30))
        assertEquals(86_400, DurationPolicy.legacyGraceSeconds(Int.MAX_VALUE))
    }

    @Test fun oneDayUsesLongMilliseconds() {
        assertEquals(86_400_000L, DurationPolicy.millis(86_400))
        assertEquals(86_400, DurationPolicy.remainingSeconds(90_000_000L, 3_600_000L))
    }

    @Test fun delayedCallbacksAndSleepDoNotExtendCountdown() {
        assertEquals(4, DurationPolicy.remainingSeconds(10_000L, 6_001L))
        assertEquals(1, DurationPolicy.remainingSeconds(10_000L, 9_999L))
        assertEquals(0, DurationPolicy.remainingSeconds(10_000L, 10_000L))
        assertEquals(0, DurationPolicy.remainingSeconds(10_000L, 100_000L))
    }

    @Test fun durationTextStaysReadableAcrossUnits() {
        assertEquals("0초", DurationPolicy.format(0))
        assertEquals("30초", DurationPolicy.format(30))
        assertEquals("1분 1초", DurationPolicy.format(61))
        assertEquals("1시간 2분 3초", DurationPolicy.format(3723))
        assertEquals("24시간", DurationPolicy.format(86_400))
    }

    @Test fun longCountdownFitsCompactButtonWithoutLosingSeconds() {
        assertEquals("30초", DurationPolicy.formatCountdown(30))
        assertEquals("59분 59초", DurationPolicy.formatCountdown(3599))
        assertEquals("01:00:00", DurationPolicy.formatCountdown(3600))
        assertEquals("23:59:59", DurationPolicy.formatCountdown(86_399))
        assertEquals("24:00:00", DurationPolicy.formatCountdown(86_400))
    }
}
