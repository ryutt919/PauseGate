package com.kimt9.pausegate

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** 통계 화면. 앱별 탭(기간별 앱 목록)과 달력 탭(날짜별 참은 비율)으로 나뉜다. 앱에서 고른 색 조합을 따른다. */
class StatsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var db: StatsDb
    private lateinit var theme: Theme
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout

    private val d: Float get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private var tab = 0
    private var period = 1
    private var monthOffset = 0
    private var selectedDay = StatsDb.today()
    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = StatsDb.get(this)
        theme = prefs.theme

        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        if (Color.luminance(theme.bg) > 0.5f) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(40))
        }
        scroll = ScrollView(this).apply {
            background = theme.backgroundDrawable()
            addView(body)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        db.snapshotUsage(prefs.gated)
        render()
    }

    private fun render() {
        val y = scroll.scrollY
        body.removeAllViews()
        body.addView(text("통계", 26f, bold = true))
        body.addView(segment(listOf("앱별", "달력"), tab) { tab = it; render() }, lp(topMargin = 12, bottomMargin = 12))
        if (tab == 0) renderApps() else renderCalendar()
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---- 앱별 탭 ----

    private fun renderApps() {
        body.addView(
            segment(listOf("오늘", "7일", "30일", "전체"), period) { period = it; render() },
            lp(bottomMargin = 12),
        )
        val from = when (period) {
            0 -> StatsDb.today()
            1 -> StatsDb.daysAgo(6)
            2 -> StatsDb.daysAgo(29)
            else -> StatsDb.FIRST
        }
        val to = StatsDb.today()

        val days = db.dayStats(from, to).values
        val shown = days.sumOf { it.shown }
        val opened = days.sumOf { it.opened }
        val ratio = if (shown == 0) 0 else Math.round((shown - opened) * 100f / shown)

        val summary = card()
        summary.addView(text("참은 비율", 12f, alpha = 0xAA))
        summary.addView(text(if (shown == 0) "-" else "$ratio%", 34f, bold = true))
        summary.addView(text("개입 ${shown}회 / 참음 ${shown - opened}회 / 그래도 열기 ${opened}회", 13f, alpha = 0xCC))
        body.addView(summary, lp(bottomMargin = 12))

        val hasUsage = Util.hasUsageAccess(this)
        val usage = if (hasUsage) db.usageByApp(from, to) else emptyMap()
        val apps = db.perApp(from, to)
        val maxShown = (apps.maxOfOrNull { it.shown } ?: 1).coerceAtLeast(1)

        body.addView(text("앱별, 개입이 많은 순", 12f, alpha = 0xAA))
        if (apps.isEmpty()) {
            body.addView(
                text("아직 앱별 기록이 없습니다. 선택한 앱을 열면 여기에 쌓입니다.", 14f, alpha = 0xCC)
                    .apply { setPadding(0, px(12), 0, px(12)); gravity = Gravity.START },
            )
        }
        for (app in apps) body.addView(appRow(app, usage[app.pkg] ?: 0L, maxShown, hasUsage))

        val legacy = db.legacyShown(from, to)
        if (legacy > 0) {
            body.addView(
                text("앱 정보 없이 저장된 이전 기록 ${legacy}회는 위 요약에는 들어 있고 목록에서는 빠집니다.", 12f, alpha = 0x99)
                    .apply { setPadding(0, px(12), 0, 0); gravity = Gravity.START },
            )
        }
        if (!hasUsage) {
            body.addView(
                text("사용 정보 접근을 허용하면 앱별 사용 시간이 함께 보입니다.", 12f, alpha = 0x99)
                    .apply { setPadding(0, px(8), 0, 0); gravity = Gravity.START },
            )
        } else {
            body.addView(
                text("사용 시간은 이 앱이 기록하기 시작한 날부터(최대 최근 7일 소급) 쌓입니다.", 12f, alpha = 0x99)
                    .apply { setPadding(0, px(8), 0, 0); gravity = Gravity.START },
            )
        }
    }

    private fun appRow(app: AppStat, usageMs: Long, maxShown: Int, hasUsage: Boolean): View {
        val limit = prefs.limitMin(app.pkg)
        val limited = period == 0 && limit > 0
        val fraction = if (limited) (usageMs / (limit * 60_000f)).coerceIn(0f, 1f) else app.shown.toFloat() / maxShown

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, px(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (!expanded.add(app.pkg)) expanded.remove(app.pkg); render() }
        }
        top.addView(
            ImageView(this).apply { setImageDrawable(iconOf(app.pkg)) },
            LinearLayout.LayoutParams(px(36), px(36)),
        )

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(Util.labelOf(this, app.pkg), 15f, gravity = Gravity.START))
        mid.addView(bar(fraction), LinearLayout.LayoutParams(-1, px(5)).apply { topMargin = px(5); bottomMargin = px(4) })
        val sub = buildString {
            if (hasUsage) append("사용 ${fmtMs(usageMs)}")
            if (limited) append(" / 한도 ${limit}분")
        }
        if (sub.isNotEmpty()) mid.addView(text(sub, 12f, alpha = 0xAA, gravity = Gravity.START))
        top.addView(mid, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(12); rightMargin = px(12) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(text("${app.shown}", 20f, bold = true, gravity = Gravity.END))
        right.addView(text("참음 ${app.resisted}", 12f, alpha = 0xAA, gravity = Gravity.END))
        top.addView(right)
        wrap.addView(top)

        if (app.pkg in expanded) wrap.addView(appDetail(app.pkg), lp(topMargin = 10))
        wrap.addView(
            View(this).apply { setBackgroundColor(a(theme.text, 0x22)) },
            LinearLayout.LayoutParams(-1, 1).apply { topMargin = px(10) },
        )
        return wrap
    }

    /** 앱 한 개의 최근 7일 추이 */
    private fun appDetail(pkg: String): View {
        val series = db.appSeries(pkg, StatsDb.daysAgo(6), StatsDb.today())
        val items = (6 downTo 0).map { n ->
            val day = StatsDb.daysAgo(n)
            val s = series[day]
            Triple(weekdayOf(day), s?.resisted ?: 0, s?.opened ?: 0)
        }
        val total = series.values.sumOf { it.shown }
        val opened = series.values.sumOf { it.opened }
        val col = card()
        col.addView(text("최근 7일, 참음은 밝게 열기는 어둡게", 12f, alpha = 0xAA, gravity = Gravity.START))
        col.addView(StackedBarsView(this, theme, items), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        val avg = Math.round(total / 7f * 10) / 10f
        val ratio = if (total == 0) "-" else "${Math.round((total - opened) * 100f / total)}%"
        col.addView(text("하루 평균 ${avg}회 / 참은 비율 $ratio", 13f, alpha = 0xCC, gravity = Gravity.START))
        return col
    }

    // ---- 달력 탭 ----

    private fun renderCalendar() {
        val streak = db.streak()
        val sc = card()
        sc.gravity = Gravity.CENTER_HORIZONTAL
        sc.addView(text("연속으로 잘 참은 날", 12f, alpha = 0xAA))
        sc.addView(text("${streak}일", 36f, bold = true))
        sc.addView(text("개입이 있었고 참은 비율이 60% 이상인 날 기준", 12f, alpha = 0x99))
        body.addView(sc, lp(bottomMargin = 12))

        val target = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, monthOffset) }
        val year = target.get(Calendar.YEAR)
        val month = target.get(Calendar.MONTH)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(
            text("‹", 24f).apply { setOnClickListener { monthOffset--; render() } },
            LinearLayout.LayoutParams(px(48), px(44)),
        )
        nav.addView(text("${year}년 ${month + 1}월", 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(
            text(if (monthOffset < 0) "›" else "", 24f).apply { if (monthOffset < 0) setOnClickListener { monthOffset++; render() } },
            LinearLayout.LayoutParams(px(48), px(44)),
        )
        body.addView(nav)

        val first = String.format("%04d%02d01", year, month + 1)
        val last = String.format("%04d%02d31", year, month + 1)
        val stats = db.dayStats(first, last)
        body.addView(
            CalendarView(this, theme, year, month, stats, selectedDay, StatsDb.today()) { selectedDay = it; render() },
            LinearLayout.LayoutParams(-1, -2),
        )
        body.addView(
            text("참은 비율이 높을수록 진하게 칠해집니다. 날짜를 누르면 아래에 그날의 내용이 나옵니다.", 12f, alpha = 0x99, gravity = Gravity.START)
                .apply { setPadding(0, px(6), 0, px(12)) },
        )

        body.addView(dayDetail(selectedDay))
    }

    private fun dayDetail(day: String): View {
        val s = db.dayStats(day, day)[day]
        val col = card()
        col.addView(text(prettyDay(day), 15f, bold = true, gravity = Gravity.START))
        if (s == null || s.shown == 0) {
            col.addView(text("이 날은 개입 기록이 없습니다.", 13f, alpha = 0xCC, gravity = Gravity.START).apply { setPadding(0, px(6), 0, 0) })
            return col
        }
        col.addView(
            text("개입 ${s.shown}회 / 참음 ${s.resisted}회 / 그래도 열기 ${s.opened}회", 13f, alpha = 0xCC, gravity = Gravity.START)
                .apply { setPadding(0, px(6), 0, px(10)) },
        )
        val apps = db.perApp(day, day)
        val usage = if (Util.hasUsageAccess(this)) db.usageByApp(day, day) else emptyMap()
        if (apps.isEmpty()) {
            col.addView(text("앱 정보 없이 저장된 이전 기록입니다.", 12f, alpha = 0x99, gravity = Gravity.START))
            return col
        }
        for (app in apps.take(6)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(3), 0, px(3)) }
            row.addView(text(Util.labelOf(this, app.pkg), 14f, gravity = Gravity.START), LinearLayout.LayoutParams(0, -2, 1f))
            val u = usage[app.pkg]
            row.addView(
                text("개입 ${app.shown} / 참음 ${app.resisted}" + if (u != null && u > 0) " / ${fmtMs(u)}" else "", 12f, alpha = 0xAA, gravity = Gravity.END),
            )
            col.addView(row)
        }
        col.addView(text("시간대별 개입", 12f, alpha = 0xAA, gravity = Gravity.START).apply { setPadding(0, px(10), 0, px(4)) })
        col.addView(HourBarsView(this, theme, db.hourly(day)), LinearLayout.LayoutParams(-1, -2))
        return col
    }

    // ---- 작은 UI 도우미 ----

    private fun text(s: String, sp: Float, bold: Boolean = false, alpha: Int = 0xFF, gravity: Int = Gravity.CENTER) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(a(theme.text, alpha))
            this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(topMargin: Int = 0, bottomMargin: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { this.topMargin = px(topMargin); this.bottomMargin = px(bottomMargin) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(12), px(14), px(12))
        background = GradientDrawable().apply {
            setColor(a(theme.text, 0x14))
            setStroke(px(1), a(theme.text, 0x26))
            cornerRadius = px(12).toFloat()
        }
    }

    private fun bar(fraction: Float): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(a(theme.text, 0x22)); cornerRadius = px(3).toFloat() }
        }
        val f = fraction.coerceIn(0f, 1f)
        track.addView(
            View(this).apply { background = GradientDrawable().apply { setColor(theme.accent); cornerRadius = px(3).toFloat() } },
            LinearLayout.LayoutParams(0, -1, f),
        )
        track.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f - f))
        return track
    }

    private fun segment(names: List<String>, sel: Int, onSel: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(3), px(3), px(3), px(3))
            background = GradientDrawable().apply { setColor(a(theme.text, 0x14)); cornerRadius = px(20).toFloat() }
        }
        names.forEachIndexed { i, n ->
            row.addView(
                TextView(this).apply {
                    text = n
                    textSize = 14f
                    gravity = Gravity.CENTER
                    if (i == sel) {
                        setTextColor(theme.bg)
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply { setColor(theme.accent); cornerRadius = px(17).toFloat() }
                    } else {
                        setTextColor(a(theme.text, 0xB0))
                    }
                    setOnClickListener { onSel(i) }
                },
                LinearLayout.LayoutParams(0, px(38), 1f),
            )
        }
        return row
    }

    /** 삭제된 앱이면 null을 돌려 아이콘 없이 이름만 보여 준다. */
    private fun iconOf(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun fmtMs(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
    }

    private fun calOf(day: String): Calendar = Calendar.getInstance().apply {
        clear()
        set(day.substring(0, 4).toInt(), day.substring(4, 6).toInt() - 1, day.substring(6, 8).toInt())
    }

    private fun weekdayOf(day: String): String = "일월화수목금토"[calOf(day).get(Calendar.DAY_OF_WEEK) - 1].toString()

    private fun prettyDay(day: String): String {
        val c = calOf(day)
        return "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일 (${weekdayOf(day)})"
    }
}
