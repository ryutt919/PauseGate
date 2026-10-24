"""C. 설정 화면 미리보기 문구와 db 불변. H. 앱 홈/통계(오늘) 숫자 대조. VERSION v4 final (0.8.0/9)."""
import sys, time, re
import xml.etree.ElementTree as ET
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
def dump(name):
    L.adb("shell", "uiautomator dump --windows /sdcard/ui.xml")
    xml = L.adb("exec-out", "cat", "/sdcard/ui.xml").decode("utf-8", "replace")
    (L.OUT / f"{name}.xml").write_text(xml, encoding="utf-8")
    return [(n.get("text"), n.get("bounds")) for n in ET.fromstring(xml).iter("node") if n.get("text")]
L.wake(); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
L.set_prefs({"enabled": ("boolean", "true"), "breathing": ("boolean", "false"), "delaySec": ("int", "3"), "graceSec": ("int", "5"),
             "limitDelaySec": ("int", "6"), "cooldownSec": ("int", "120"), "themeIdx": ("int", "0"),
             "graceUntil_" + L.TARGET: ("long", "0")},
            remove=["limit_" + L.TARGET, "cycle_used_" + L.TARGET, "cycle_until_" + L.TARGET])
L.reconnect()
before = {"events": L.db("select count(*) from events"), "usage_rows": L.db("select group_concat(day||':'||ms) from daily_usage"), "stats": L.stats_today()}
L.log("C 미리보기 전", **before)
L.adb("shell", f"am start -n {L.PKG}/.SettingsActivity"); time.sleep(2)
btn = None
for k in range(8):
    texts = dump(f"11_C_설정화면_dump{k}")
    cand = [b for t, b in texts if t and "개입 화면 미리보기" in t]
    if cand: btn = cand[0]; break
    L.sh("input swipe 540 1800 540 700 300"); time.sleep(0.8)
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", btn)
cx, cy = (int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2
L.log("C 미리보기 버튼", bounds=btn, tap=[cx, cy]); L.tap(cx, cy); time.sleep(1.5)
for i in range(3): L.snap(f"C_미리보기_{i+1}"); time.sleep(0.5)
L.log("C 미리보기 표시 중 db", events=L.db("select count(*) from events"), usage_rows=L.db("select group_concat(day||':'||ms) from daily_usage"))
L.sh("input keyevent KEYCODE_BACK"); time.sleep(1)
L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
L.log("C 미리보기 후", events=L.db("select count(*) from events"), usage_rows=L.db("select group_concat(day||':'||ms) from daily_usage"), stats=L.stats_today())
# H
L.sh(f"am force-stop {L.PKG}"); time.sleep(0.5)
L.adb("shell", f"am start -n {L.PKG}/.MainActivity"); time.sleep(2.5)
t = dump("12_H_앱홈_dump"); L.snap("H_앱홈")
L.log("H 앱 홈 텍스트", texts=[a for a, b in t][:20], db=L.stats_today())
L.adb("shell", f"am start -n {L.PKG}/.StatsActivity"); time.sleep(2)
t = dump("13_H_통계_전체탭_dump")
b = [bb for a, bb in t if a == "오늘"][0]
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b); L.tap((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2); time.sleep(1.5)
t = dump("14_H_통계_오늘탭_dump"); L.snap("H_통계_오늘")
d = L.stats_today()["day"]
L.log("H 통계 오늘탭 텍스트", texts=[a for a, b in t], db=L.stats_today(), daily_usage=L.db(f"select * from daily_usage where day='{d}'"), pending=L.db("select count(*) from events where outcome='PENDING'"))
L.sh("input keyevent KEYCODE_HOME")
