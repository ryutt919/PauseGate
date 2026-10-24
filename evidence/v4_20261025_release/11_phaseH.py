"""h. 앱 홈/통계 화면(오늘 탭)의 오늘 이 앱 숫자와 개입 패널 숫자 대조."""
import sys, time, re
import xml.etree.ElementTree as ET
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
def dump(name):
    L.adb("shell", "uiautomator dump --windows /sdcard/ui.xml")
    xml = L.adb("exec-out", "cat", "/sdcard/ui.xml").decode("utf-8", "replace")
    (L.OUT / f"{name}.xml").write_text(xml, encoding="utf-8")
    return [(n.get("text"), n.get("bounds")) for n in ET.fromstring(xml).iter("node") if n.get("text")]
L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
L.sh(f"am force-stop {L.PKG}"); time.sleep(0.5)
L.adb("shell", f"am start -n {L.PKG}/.MainActivity"); time.sleep(2.5)
t = dump("12_h_앱홈_dump"); L.snap("H_앱홈")
L.log("h 앱 홈 텍스트", texts=[a for a, b in t][:14], db=L.stats_today())
L.adb("shell", f"am start -n {L.PKG}/.StatsActivity"); time.sleep(2)
t = dump("13_h_통계_전체탭_dump")
b = [bb for a, bb in t if a == "오늘"][0]
m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b); L.tap((int(m[1]) + int(m[3])) // 2, (int(m[2]) + int(m[4])) // 2); time.sleep(1.5)
t = dump("14_h_통계_오늘탭_dump"); L.snap("H_통계_오늘")
L.log("h 통계 오늘탭 텍스트", texts=[a for a, b in t], db=L.stats_today(), daily_usage=L.db("select * from daily_usage where day='" + L.stats_today()['day'] + "'"))
L.sh("input keyevent KEYCODE_HOME")
