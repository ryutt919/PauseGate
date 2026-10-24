package com.kimt9.pausegate

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/** 실제 사용을 매초 저장하고 한도 소진 후 쿨타임 기간에는 별도의 대기를 적용한다. */
class GateService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private lateinit var cycles: LimitCycleRepository
    private lateinit var wm: WindowManager
    private lateinit var stats: StatsDb
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var receiverRegistered = false
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var cooldownOverlay = false
    private var foregroundPkg: String? = null
    private var countingPkg: String? = null
    private var checkpointElapsed = 0L
    private var eventId: Long? = null
    private var lastClosePkg: String? = null
    private var lastCloseAt = 0L

    private val monitor = object : Runnable {
        override fun run() {
            if (!connected) return
            val enteredCooldown = settleUsage()
            val pkg = foregroundPkg
            if (!canUse(pkg)) {
                removeOverlay()
            } else if (pkg != null) {
                val state = cycles.state(pkg)
                if (!state.isCoolingDown(System.currentTimeMillis()) && cooldownOverlay) {
                    val currentEvent = eventId
                    eventId = null
                    removeOverlay()
                    showOverlay(pkg, false, currentEvent)
                } else if (enteredCooldown) {
                    enterApp(pkg)
                }
            }
            refreshCounting()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            settleUsage()
            if (!screenAvailable()) removeOverlay()
            else foregroundPkg?.let { enterApp(it) }
            refreshCounting()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        cycles = LimitCycleRepository(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        stats = StatsDb.get(this)
        stats.settlePending()
        connected = true
        checkpointElapsed = SystemClock.elapsedRealtime()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        foregroundPkg = Util.foregroundPackage(this)?.takeUnless { it == packageName || isTransient(it) }
        foregroundPkg?.let { enterApp(it) }
        refreshCounting()
        handler.removeCallbacks(monitor)
        handler.postDelayed(monitor, 1_000L)
    }

    private fun screenAvailable(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun canUse(pkg: String?): Boolean =
        prefs.enabled && pkg != null && pkg in prefs.gated && screenAvailable()

    /** 저장한 이전 구간만 확정한다. 화면 전환과 오버레이 표시 전에 호출한다. */
    private fun settleUsage(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val pkg = countingPkg
        var enteredCooldown = false
        if (pkg != null && prefs.enabled && pkg in prefs.gated) {
            val nowMs = System.currentTimeMillis()
            val previous = cycles.state(pkg, nowMs)
            val next = cycles.addUsage(pkg, (now - checkpointElapsed).coerceAtLeast(0L), nowMs)
            enteredCooldown = !previous.isCoolingDown(nowMs) && next.isCoolingDown(nowMs)
            if (enteredCooldown) prefs.setGraceUntilMs(pkg, 0L)
        }
        checkpointElapsed = now
        return enteredCooldown
    }

    private fun refreshCounting() {
        val pkg = foregroundPkg
        countingPkg = if (canUse(pkg) && overlay == null && pkg != null &&
            !cycles.state(pkg).isCoolingDown(System.currentTimeMillis())) pkg else null
        checkpointElapsed = SystemClock.elapsedRealtime()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!connected || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 접근성 오버레이 자체가 내보내는 이벤트와 키보드는 대상 앱 전환이 아니다.
        if (isTransient(pkg) || (pkg == packageName && overlay != null)) return
        settleUsage()
        if (overlay != null && pkg != overlayPkg) removeOverlay()
        foregroundPkg = pkg
        enterApp(pkg)
        refreshCounting()
    }

    private fun enterApp(pkg: String) {
        if (!canUse(pkg)) return
        val state = cycles.state(pkg)
        if (overlay != null) return
        val cooldown = state.isCoolingDown(System.currentTimeMillis())
        if (!cooldown && pkg == lastClosePkg && SystemClock.uptimeMillis() - lastCloseAt < 1500) return
        if (System.currentTimeMillis() < prefs.graceUntilMs(pkg)) return
        showOverlay(pkg, cooldown, cooldownUntilMs = state.cooldownUntilMs)
    }

    private fun isTransient(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun finishEvent(outcome: String) {
        eventId?.let { stats.finish(it, outcome) }
        eventId = null
    }

    private fun closeApp(pkg: String) {
        finishEvent("CLOSED")
        lastClosePkg = pkg
        lastCloseAt = SystemClock.uptimeMillis()
        removeOverlay()
        countingPkg = null
        foregroundPkg = null
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** cooldownUntilMs는 쿨타임 여부를 판정한 같은 상태에서 읽은 값을 받아 판정과 표시를 맞춘다. */
    private fun showOverlay(pkg: String, cooldown: Boolean, eventToReuse: Long? = null, cooldownUntilMs: Long = 0L) {
        val usedMs = Util.usedTodayMs(this, pkg)
        if (usedMs > 0) stats.recordUsage(StatsDb.today(), pkg, usedMs)
        val view = OverlayView(
            context = this, theme = prefs.theme, appLabel = Util.labelOf(this, pkg),
            message = prefs.message, delaySec = prefs.delaySec, breathing = prefs.breathing,
            limitReached = cooldown, limitDelaySec = prefs.limitDelaySec,
            cooldownUntilMs = cooldownUntilMs,
            onOpen = {
                finishEvent("OPENED")
                prefs.setGraceUntilMs(pkg, System.currentTimeMillis() + DurationPolicy.millis(prefs.graceSec))
                removeOverlay()
                refreshCounting()
            },
            onClose = { closeApp(pkg) },
        )
        attachOverlay(pkg, view, cooldown)
        eventId = eventToReuse ?: stats.begin(pkg, cooldown)
    }

    private fun attachOverlay(pkg: String, view: View, cooldown: Boolean) {
        countingPkg = null
        overlay = view
        overlayPkg = pkg
        cooldownOverlay = cooldown
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(view, lp)
    }

    private fun removeOverlay() {
        finishEvent("DISMISSED")
        overlay?.let { wm.removeView(it) }
        overlay = null
        overlayPkg = null
        cooldownOverlay = false
    }

    override fun onInterrupt() {
        if (!connected) return
        settleUsage()
        countingPkg = null
        foregroundPkg = null
        removeOverlay()
    }

    override fun onDestroy() {
        if (connected) {
            settleUsage()
            connected = false
            handler.removeCallbacks(monitor)
            removeOverlay()
        }
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }
}
