"""e2 큰 글자 2.0, g 사용 정보 권한 없음, f 설정 화면 미리보기, h 앱 홈/통계 화면 숫자 대조."""
import sys, time, json, re
import xml.etree.ElementTree as ET
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
def conf(theme=0, breathing=False, cooldown=True):
    now = L.emu_ms()
    u = {"enabled": ("boolean", "true"), "breathing": ("boolean", "true" if breathing else "false"), "delaySec": ("int", "3"),
         "graceSec": ("int", "5"), "limitDelaySec": ("int", "6"), "cooldownSec": ("int", "1200"), "themeIdx": ("int", str(theme)),
         "graceUntil_" + L.TARGET: ("long", "0")}
    rem = ["limit_" + L.TARGET, "cycle_used_" + L.TARGET, "cycle_until_" + L.TARGET]
    if cooldown:
        u.update({"limit_" + L.TARGET: ("int", "1"), "cycle_used_" + L.TARGET: ("long", "60000"), "cycle_until_" + L.TARGET: ("long", str(now + 1_190_000))})
    L.set_prefs(u, remove=rem if not cooldown else []); L.reconnect()
    L.log("설정", theme=theme, breathing=breathing, cooldown=cooldown, stats=L.stats_today())
def dump(name):
    L.adb("shell", "uiautomator dump --windows /sdcard/ui.xml")
    xml = L.adb("exec-out", "cat", "/sdcard/ui.xml").decode("utf-8", "replace")
    (L.OUT / f"{name}.xml").write_text(xml, encoding="utf-8")
    return xml, [(n.get("text"), n.get("bounds")) for n in ET.fromstring(xml).iter("node") if n.get("text")]
def usage_lines(tag):
    out = L.sh("dumpsys usagestats | grep 'package=com.android.settings totalTimeUsed'")
    L.log("usagestats " + tag, lines=[l.strip()[:58] for l in out.splitlines()])
L.wake()
# e2. 글자 2.0
conf(0, False)
L.sh("settings put system font_scale 2.0"); time.sleep(1.5)
try:
    L.log("font_scale", v=L.sh("settings get system font_scale"))
    L.target(); time.sleep(2.5)
    for i in range(2): L.snap(f"E2_글자2.0세로_{i+1}"); time.sleep(0.5)
    L.sh("settings put system accelerometer_rotation 0"); L.sh("settings put system user_rotation 1"); time.sleep(3)
    L.snap("E2_글자2.0가로_1")
    L.sh("input swipe 1200 900 1200 150 400"); time.sleep(1); L.snap("E2_글자2.0가로_스크롤후1")
    L.sh("input swipe 1200 900 1200 100 400"); time.sleep(1); L.snap("E2_글자2.0가로_스크롤후2")
finally:
    L.sh("settings put system user_rotation 0"); L.sh("settings put system accelerometer_rotation 1")
    L.sh("settings put system font_scale 1.0"); time.sleep(2.5)
L.log("E2 복원", font_scale=L.sh("settings get system font_scale"), rot=L.sh("settings get system user_rotation"))
L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
# g. 권한 없음
L.sh(f"cmd appops set {L.PKG} GET_USAGE_STATS deny"); L.log("appops", v=L.sh(f"cmd appops get {L.PKG} GET_USAGE_STATS"))
try:
    conf(0, False, cooldown=False)
    L.target(); time.sleep(2.2)
    for i in range(2): L.snap(f"G_권한없음_일반_{i+1}"); time.sleep(0.5)
    L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
    conf(0, False, cooldown=True)
    L.target(); time.sleep(2.2)
    for i in range(2): L.snap(f"G_권한없음_쿨타임_{i+1}"); time.sleep(0.5)
    L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
finally:
    L.sh(f"cmd appops set {L.PKG} GET_USAGE_STATS allow")
    L.log("appops 복원", v=L.sh(f"cmd appops get {L.PKG} GET_USAGE_STATS"))
# f. 설정 화면 미리보기 (실제 기록이 늘지 않는지)
conf(0, False, cooldown=False)
L.sh(f"cmd appops set {L.PKG} GET_USAGE_STATS allow")
before = {"events": L.db("select count(*) from events"), "usage_rows": L.db("select group_concat(day||':'||ms) from daily_usage"), "stats": L.stats_today()}
L.log("f 미리보기 전", **before)
L.adb("shell", f"am start -n {L.PKG}/.SettingsActivity"); time.sleep(2)
btn = None
for k in range(8):
    xml, texts = dump(f"11_f_설정화면_dump{k}")
    cand = [b for t, b in texts if t and "개입 화면 미리보기" in t]
    if cand: btn = cand[0]; break
    L.sh("input swipe 540 1800 540 700 300"); time.sleep(0.8)
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", btn)
cx, cy = (int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2
L.log("f 미리보기 버튼", bounds=btn, tap=[cx, cy]); L.tap(cx, cy); time.sleep(1.5)
for i in range(3): L.snap(f"F_미리보기_{i+1}"); time.sleep(0.5)
after1 = {"events": L.db("select count(*) from events"), "usage_rows": L.db("select group_concat(day||':'||ms) from daily_usage")}
L.log("f 미리보기 표시 중 db", **after1)
L.sh("input keyevent KEYCODE_BACK"); time.sleep(1)
L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
after = {"events": L.db("select count(*) from events"), "usage_rows": L.db("select group_concat(day||':'||ms) from daily_usage"), "stats": L.stats_today()}
L.log("f 미리보기 후", **after)
# h. 앱 홈/통계 화면 대조
time.sleep(1)
L.adb("shell", f"am start -n {L.PKG}/.MainActivity"); time.sleep(2)
x, t = dump("12_h_앱홈_dump"); L.snap("H_앱홈")
L.log("h 앱 홈 텍스트", texts=[a for a, b in t])
L.adb("shell", f"am start -n {L.PKG}/.StatsActivity"); time.sleep(2)
x, t = dump("13_h_통계_dump"); L.snap("H_통계")
L.log("h 통계 텍스트", texts=[a for a, b in t])
L.log("h db 오늘", stats=L.stats_today(), pending=L.db("select count(*) from events where outcome='PENDING'"))
L.sh("input keyevent KEYCODE_HOME")
