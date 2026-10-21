package com.kimt9.pausegate

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefsTest {
    @Test fun existingDelayAndGraceArePreservedOnUpgrade() {
        val store = MemoryPreferences(mutableMapOf("delaySec" to 19, "graceMin" to 17))
        val prefs = Prefs(store.preferences)
        assertEquals(19, prefs.delaySec)
        assertEquals(1020, prefs.graceSec)
        assertEquals(1020, store.values["graceSec"])
        assertEquals(30, prefs.limitDelaySec)
    }

    @Test fun secondBasedGraceTakesPriorityOverLegacyValue() {
        val store = MemoryPreferences(mutableMapOf("graceMin" to 30, "graceSec" to 7))
        assertEquals(7, Prefs(store.preferences).graceSec)
    }

    @Test fun allSettingsSupportOneDayAndRemainIndependent() {
        val prefs = Prefs(MemoryPreferences().preferences)
        prefs.delaySec = 86_400
        prefs.graceSec = 86_400
        prefs.limitDelaySec = 86_400
        assertEquals(86_400, prefs.delaySec)
        assertEquals(86_400, prefs.graceSec)
        assertEquals(86_400, prefs.limitDelaySec)
        prefs.delaySec = 3
        prefs.graceSec = 1
        assertEquals(3, prefs.delaySec)
        assertEquals(1, prefs.graceSec)
        assertEquals(86_400, prefs.limitDelaySec)
    }

    @Test fun graceDeadlineSurvivesNewPrefsInstanceAndIsPerApp() {
        val store = MemoryPreferences()
        val deadline = 1_800_000_000_000L + DurationPolicy.millis(86_400)
        Prefs(store.preferences).setGraceUntilMs("first.app", deadline)
        val restarted = Prefs(store.preferences)
        assertEquals(deadline, restarted.graceUntilMs("first.app"))
        assertEquals(0L, restarted.graceUntilMs("second.app"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDelayBelowMinimum() {
        Prefs(MemoryPreferences().preferences).delaySec = 2
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGraceOverOneDay() {
        Prefs(MemoryPreferences().preferences).graceSec = 86_401
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroLimitDelay() {
        Prefs(MemoryPreferences().preferences).limitDelaySec = 0
    }

    /** Android 런타임 없이 저장과 재생성 경로를 확인하는 최소 저장소. */
    private class MemoryPreferences(val values: MutableMap<String, Any> = mutableMapOf()) {
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putInt", "putLong" -> {
                    values[args!![0] as String] = args[1]
                    proxy
                }
                "apply" -> null
                else -> error("테스트에서 지원하지 않는 Editor 호출: ${method.name}")
            }
        } as SharedPreferences.Editor

        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0] as String)
                "getInt", "getLong" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("테스트에서 지원하지 않는 Preferences 호출: ${method.name}")
            }
        } as SharedPreferences
    }
}
