package com.kimt9.pausegate

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * 지정한 앱이 앞으로 올라오는 순간을 감지해 개입 화면을 덮어씌운다.
 * 화면 내용은 읽지 않고(canRetrieveWindowContent=false) 패키지 이름만 본다.
 */
class GateService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var overlayPkg: String? = null

    /** 앱별 "그냥 열기" 허용이 끝나는 시각(uptime ms) */
    private val graceUntil = HashMap<String, Long>()
    private var lastClosePkg: String? = null
    private var lastCloseAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || isTransient(pkg)) return

        // 개입 화면이 떠 있는데 다른 앱으로 넘어갔다면 정리한다.
        if (overlay != null && pkg != overlayPkg) removeOverlay()

        if (!prefs.enabled || pkg !in prefs.gated || overlay != null) return
        if (pkg == lastClosePkg && SystemClock.uptimeMillis() - lastCloseAt < 1500) return
        if (SystemClock.uptimeMillis() < (graceUntil[pkg] ?: 0L)) return

        showOverlay(pkg)
    }

    /** 알림창, 키보드처럼 앱 전환으로 보지 않을 창 */
    private fun isTransient(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun showOverlay(pkg: String) {
        val limit = prefs.limitMin(pkg)
        val limitReached = limit > 0 && Util.usedTodayMs(this, pkg) >= limit * 60_000L
        val view = OverlayView(
            context = this,
            theme = prefs.theme,
            appLabel = Util.labelOf(this, pkg),
            message = prefs.message,
            delaySec = prefs.delaySec,
            breathing = prefs.breathing,
            limitReached = limitReached,
            onOpen = {
                prefs.bump(Prefs.Stat.OPENED)
                graceUntil[pkg] = SystemClock.uptimeMillis() + prefs.graceMin * 60_000L
                removeOverlay()
            },
            onClose = {
                prefs.bump(Prefs.Stat.CLOSED)
                lastClosePkg = pkg
                lastCloseAt = SystemClock.uptimeMillis()
                removeOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(view, lp)
        overlay = view
        overlayPkg = pkg
        prefs.bump(Prefs.Stat.SHOWN)
    }

    private fun removeOverlay() {
        overlay?.let { wm.removeView(it) }
        overlay = null
        overlayPkg = null
    }

    override fun onInterrupt() = removeOverlay()

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
