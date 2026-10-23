package com.kimt9.pausegate

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

data class AppInfo(val pkg: String, val label: String)

object Util {
    /** 사용 정보 접근 권한이 있는지 */
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 접근성 서비스가 켜져 있는지 */
    fun isGateEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, GateService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** 오늘 0시부터 지금까지 해당 앱을 화면에 띄운 시간(ms). 권한이 없으면 0. */
    fun usedTodayMs(ctx: Context, pkg: String): Long {
        if (!hasUsageAccess(ctx)) return 0L
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())[pkg]?.totalTimeInForeground ?: 0L
    }

    /** 서비스 재연결 시 현재 앱을 추정한다. 조회 불가/열린 구간 없음은 사용량 0이라는 뜻이 아니다. */
    fun foregroundPackage(ctx: Context): String? {
        if (!hasUsageAccess(ctx)) return null
        val now = System.currentTimeMillis()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(now - 86_400_000L, now) ?: return null
        val event = UsageEvents.Event()
        var pkg: String? = null
        var activity: String? = null
        while (events.getNextEvent(event)) {
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    pkg = event.packageName
                    activity = event.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (event.packageName == pkg && (event.className == activity || event.className == null)) pkg = null
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> pkg = null
            }
        }
        return pkg
    }

    /** 홈 화면에 아이콘이 있는 모든 앱(자기 자신 제외), 이름순 */
    fun launchableApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        pkg
    }
}
