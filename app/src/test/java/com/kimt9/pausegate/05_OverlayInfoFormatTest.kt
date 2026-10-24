package com.kimt9.pausegate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayInfoFormatTest {
    private val min = 60_000L

    @Test fun usedDurationUnderOneMinute() {
        assertEquals("1분 미만", OverlayInfoFormat.usedDuration(0L))
        assertEquals("1분 미만", OverlayInfoFormat.usedDuration(59_999L))
        assertEquals("1분 미만", OverlayInfoFormat.usedDuration(-5L))
    }

    @Test fun usedDurationMinutesAndHours() {
        assertEquals("1분", OverlayInfoFormat.usedDuration(min))
        assertEquals("45분", OverlayInfoFormat.usedDuration(45 * min))
        assertEquals("59분", OverlayInfoFormat.usedDuration(59 * min + 59_000L))
        assertEquals("1시간", OverlayInfoFormat.usedDuration(60 * min))
        assertEquals("1시간 12분", OverlayInfoFormat.usedDuration(72 * min))
        assertEquals("24시간", OverlayInfoFormat.usedDuration(24 * 60 * min))
        assertEquals("25시간 3분", OverlayInfoFormat.usedDuration(25 * 60 * min + 3 * min))
    }

    @Test fun usedRowIncludesAppName() {
        assertEquals("오늘 유튜브 사용 1시간 12분", OverlayInfoFormat.usedRow("유튜브", 72 * min))
        assertEquals("오늘 유튜브 사용 1분 미만", OverlayInfoFormat.usedRow("유튜브", 30_000L))
    }

    @Test fun statsRowSampleAndAllKept() {
        assertEquals("이 앱 오늘 개입 5회, 참음 3회 (60%)", OverlayInfoFormat.statsRow(5, 3))
        assertEquals("이 앱 오늘 개입 4회, 참음 4회 (100%)", OverlayInfoFormat.statsRow(4, 4))
        assertEquals("이 앱 오늘 개입 3회, 참음 0회 (0%)", OverlayInfoFormat.statsRow(3, 0))
    }

    @Test fun noInterventionsHidesStats() {
        assertNull(OverlayInfoFormat.statsRow(0, 0))
        assertNull(OverlayInfoFormat.statsSpoken(0, 0))
        assertEquals(0, OverlayInfoFormat.keptPercent(0, 0))
    }

    @Test fun percentRoundsHalfUpLikeStatsScreen() {
        assertEquals(33, OverlayInfoFormat.keptPercent(3, 1))
        assertEquals(67, OverlayInfoFormat.keptPercent(3, 2))
        assertEquals(13, OverlayInfoFormat.keptPercent(8, 1)) // 12.5
        assertEquals(63, OverlayInfoFormat.keptPercent(8, 5)) // 62.5
        assertEquals(1, OverlayInfoFormat.keptPercent(200, 1)) // 0.5
        assertEquals(0, OverlayInfoFormat.keptPercent(201, 1)) // 0.4975
    }

    @Test fun percentMatchesDayStatDefinition() {
        // DayStat(shown, opened): 참음 = shown - opened
        val s = DayStat(shown = 7, opened = 2)
        assertEquals(5, s.resisted)
        assertEquals(71, OverlayInfoFormat.keptPercent(s.shown, s.resisted)) // 5/7 = 71.4
    }

    @Test fun keptIsClampedToShown() {
        assertEquals("이 앱 오늘 개입 2회, 참음 2회 (100%)", OverlayInfoFormat.statsRow(2, 5))
        assertEquals("이 앱 오늘 개입 2회, 참음 0회 (0%)", OverlayInfoFormat.statsRow(2, -1))
    }

    @Test fun spokenStatsUsesWords() {
        assertEquals("이 앱 오늘 개입 5회, 참음 3회, 참은 비율 60퍼센트", OverlayInfoFormat.statsSpoken(5, 3))
    }
}
