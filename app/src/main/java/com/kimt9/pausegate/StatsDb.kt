package com.kimt9.pausegate

import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 하루 단위 집계. 참음은 "열지 않은 개입"(닫기와 그냥 나감)이다. */
data class DayStat(val shown: Int, val opened: Int) {
    val resisted: Int get() = shown - opened
    val ratio: Float get() = if (shown == 0) 0f else resisted.toFloat() / shown
}

data class AppStat(val pkg: String, val shown: Int, val opened: Int) {
    val resisted: Int get() = shown - opened
    val ratio: Float get() = if (shown == 0) 0f else resisted.toFloat() / shown
}

/**
 * 통계 저장소. 개입 1건을 events 한 줄로 남기고, 일별, 앱별, 시간대별 값은 모두 여기서 계산한다.
 * outcome: PENDING(화면이 떠 있음), CLOSED(닫기), OPENED(그래도 열기), DISMISSED(버튼 없이 다른 곳으로 나감).
 * daily_usage는 시스템 사용 기록의 보관 기간이 짧아서 앱별 하루 사용 시간을 따로 쌓는다.
 * legacy_daily는 0.3.1 이전의 앱 구분 없는 날짜별 합계다.
 */
class StatsDb private constructor(private val ctx: Context) :
    SQLiteOpenHelper(ctx, "pausegate_stats.db", null, 1) {

    companion object {
        @Volatile private var inst: StatsDb? = null
        fun get(c: Context): StatsDb =
            inst ?: synchronized(this) { inst ?: StatsDb(c.applicationContext).also { inst = it } }

        fun dayOf(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))
        fun today(): String = dayOf(System.currentTimeMillis())
        fun daysAgo(n: Int): String {
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }
            return dayOf(c.timeInMillis)
        }
        const val FIRST = "00000000"
        const val LAST = "99999999"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, day TEXT NOT NULL, " +
                "hour INTEGER NOT NULL, pkg TEXT NOT NULL, outcome TEXT NOT NULL, " +
                "waited_ms INTEGER NOT NULL DEFAULT 0, limit_reached INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX idx_events_day ON events(day)")
        db.execSQL("CREATE INDEX idx_events_pkg_day ON events(pkg, day)")
        db.execSQL("CREATE TABLE daily_usage (day TEXT NOT NULL, pkg TEXT NOT NULL, ms INTEGER NOT NULL, PRIMARY KEY(day, pkg))")
        db.execSQL("CREATE TABLE legacy_daily (day TEXT PRIMARY KEY, shown INTEGER NOT NULL, opened INTEGER NOT NULL)")
        migrateLegacy(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** 0.3.1 이전에 설정 파일에 쌓던 stat_날짜_종류 합계를 옮긴다. 원본 키는 지우지 않는다. */
    private fun migrateLegacy(db: SQLiteDatabase) {
        val sp = ctx.getSharedPreferences("pausegate", Context.MODE_PRIVATE)
        val shown = HashMap<String, Int>()
        val opened = HashMap<String, Int>()
        for ((k, v) in sp.all) {
            if (!k.startsWith("stat_") || v !is Int) continue
            val p = k.split('_')
            if (p.size != 3) continue
            when (p[2]) {
                "SHOWN" -> shown[p[1]] = v
                "OPENED" -> opened[p[1]] = v
            }
        }
        for ((day, n) in shown) {
            db.execSQL(
                "INSERT OR REPLACE INTO legacy_daily(day, shown, opened) VALUES(?, ?, ?)",
                arrayOf<Any>(day, n, opened[day] ?: 0),
            )
        }
    }

    // ---- 기록 ----

    /** 개입 화면을 띄울 때 한 줄을 만들고 id를 돌려준다. */
    fun begin(pkg: String, limitReached: Boolean): Long {
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val v = ContentValues().apply {
            put("ts", now)
            put("day", dayOf(now))
            put("hour", hour)
            put("pkg", pkg)
            put("outcome", "PENDING")
            put("limit_reached", if (limitReached) 1 else 0)
        }
        return writableDatabase.insertOrThrow("events", null, v)
    }

    fun finish(id: Long, outcome: String) {
        writableDatabase.execSQL(
            "UPDATE events SET outcome = ?, waited_ms = ? - ts WHERE id = ?",
            arrayOf<Any>(outcome, System.currentTimeMillis(), id),
        )
    }

    /** 서비스가 갑자기 꺼져 PENDING으로 남은 줄을 정리한다. */
    fun settlePending() {
        writableDatabase.execSQL("UPDATE events SET outcome = 'DISMISSED' WHERE outcome = 'PENDING'")
    }

    fun recordUsage(day: String, pkg: String, ms: Long) = putUsage(writableDatabase, day, pkg, ms)

    private fun putUsage(db: SQLiteDatabase, day: String, pkg: String, ms: Long) {
        db.execSQL("UPDATE daily_usage SET ms = MAX(ms, ?) WHERE day = ? AND pkg = ?", arrayOf<Any>(ms, day, pkg))
        db.execSQL("INSERT OR IGNORE INTO daily_usage(day, pkg, ms) VALUES(?, ?, ?)", arrayOf<Any>(day, pkg, ms))
    }

    /** 시스템이 가진 최근 7일의 앱별 하루 사용 시간을 가져와 쌓는다. */
    fun snapshotUsage(pkgs: Set<String>) {
        if (pkgs.isEmpty() || !Util.hasUsageAccess(ctx)) return
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 7L * 24 * 3600 * 1000, end) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in list) {
                if (s.packageName !in pkgs || s.totalTimeInForeground <= 0) continue
                putUsage(db, dayOf(s.firstTimeStamp), s.packageName, s.totalTimeInForeground)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- 조회 ----

    private fun <T> query(sql: String, args: Array<String>, row: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(row(c)) }
        return out
    }

    private val openedExpr = "SUM(CASE WHEN outcome = 'OPENED' THEN 1 ELSE 0 END)"

    /** 기간 안의 날짜별 집계. 앱 구분이 없는 이전 기록도 포함한다. */
    fun dayStats(from: String, to: String): Map<String, DayStat> {
        val m = HashMap<String, DayStat>()
        query("SELECT day, shown, opened FROM legacy_daily WHERE day BETWEEN ? AND ?", arrayOf(from, to)) {
            m[it.getString(0)] = DayStat(it.getInt(1), it.getInt(2))
        }
        query(
            "SELECT day, COUNT(*), $openedExpr FROM events WHERE day BETWEEN ? AND ? AND outcome != 'PENDING' GROUP BY day",
            arrayOf(from, to),
        ) {
            val day = it.getString(0)
            val prev = m[day]
            m[day] = DayStat((prev?.shown ?: 0) + it.getInt(1), (prev?.opened ?: 0) + it.getInt(2))
        }
        return m
    }

    /** 앱 구분이 있는 기록만. 개입이 많은 순. */
    fun perApp(from: String, to: String): List<AppStat> = query(
        "SELECT pkg, COUNT(*), $openedExpr FROM events WHERE day BETWEEN ? AND ? AND outcome != 'PENDING' " +
            "GROUP BY pkg ORDER BY COUNT(*) DESC, pkg",
        arrayOf(from, to),
    ) { AppStat(it.getString(0), it.getInt(1), it.getInt(2)) }

    fun legacyShown(from: String, to: String): Int = query(
        "SELECT COALESCE(SUM(shown), 0) FROM legacy_daily WHERE day BETWEEN ? AND ?", arrayOf(from, to),
    ) { it.getInt(0) }.firstOrNull() ?: 0

    fun appSeries(pkg: String, from: String, to: String): Map<String, DayStat> {
        val m = HashMap<String, DayStat>()
        query(
            "SELECT day, COUNT(*), $openedExpr FROM events WHERE pkg = ? AND day BETWEEN ? AND ? AND outcome != 'PENDING' GROUP BY day",
            arrayOf(pkg, from, to),
        ) { m[it.getString(0)] = DayStat(it.getInt(1), it.getInt(2)) }
        return m
    }

    fun usageByApp(from: String, to: String): Map<String, Long> {
        val m = HashMap<String, Long>()
        query("SELECT pkg, SUM(ms) FROM daily_usage WHERE day BETWEEN ? AND ? GROUP BY pkg", arrayOf(from, to)) {
            m[it.getString(0)] = it.getLong(1)
        }
        return m
    }

    fun hourly(day: String): IntArray {
        val h = IntArray(24)
        query("SELECT hour, COUNT(*) FROM events WHERE day = ? AND outcome != 'PENDING' GROUP BY hour", arrayOf(day)) {
            h[it.getInt(0).coerceIn(0, 23)] = it.getInt(1)
        }
        return h
    }

    /**
     * 연속으로 잘 참은 날 수. 개입이 있었고 참은 비율이 60% 이상인 날을 센다.
     * 오늘 개입이 아직 없으면 오늘은 건너뛰고 어제부터 센다.
     */
    fun streak(): Int {
        val stats = dayStats(daysAgo(400), today())
        var n = 0
        for (i in 0..400) {
            val s = stats[daysAgo(i)]
            if (s == null || s.shown == 0) {
                if (i == 0) continue
                break
            }
            if (s.ratio >= 0.6f) n++ else break
        }
        return n
    }
}
