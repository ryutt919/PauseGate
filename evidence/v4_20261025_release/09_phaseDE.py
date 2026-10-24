"""d. 테마(메탈 5, 종이 4, 기본 숲 0, 숨쉬기 켬, 쿨타임 상태 3행 패널). e. 큰 글자 1.3(세로/가로, 가로 스크롤)."""
import sys, time, json
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
def usage_lines(tag):
    out = L.sh("dumpsys usagestats | grep 'package=com.android.settings totalTimeUsed'")
    L.log("usagestats " + tag, lines=[l.strip()[:75] for l in out.splitlines()])
def conf(theme, breathing, cooldown=True):
    now = L.emu_ms()
    u = {"enabled": ("boolean", "true"), "breathing": ("boolean", "true" if breathing else "false"), "delaySec": ("int", "3"),
         "graceSec": ("int", "5"), "limitDelaySec": ("int", "6"), "cooldownSec": ("int", "1200"), "themeIdx": ("int", str(theme)),
         "limit_" + L.TARGET: ("int", "1"), "cycle_used_" + L.TARGET: ("long", "60000"),
         "cycle_until_" + L.TARGET: ("long", str(now + 1_190_000 if cooldown else 0)),
         "graceUntil_" + L.TARGET: ("long", "0")}
    L.set_prefs(u); L.reconnect()
    L.log("설정", theme=theme, breathing=breathing, cooldown=cooldown, stats=L.stats_today())
L.wake()
for tag, theme, br in (("D_메탈", 5, True), ("D_종이", 4, True), ("D_숲쿨타임숨쉬기", 0, True)):
    conf(theme, br)
    L.target(); time.sleep(2)
    for i in range(5):
        L.snap(f"{tag}_{i+1}"); time.sleep(0.7)
    usage_lines(tag)
    L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
# e. 큰 글자
conf(0, False)
L.sh("settings put system font_scale 1.3"); time.sleep(1.5)
try:
    L.log("font_scale", v=L.sh("settings get system font_scale"))
    L.target(); time.sleep(2.5)
    for i in range(2): L.snap(f"E_큰글자세로_{i+1}"); time.sleep(0.5)
    L.sh("settings put system accelerometer_rotation 0"); L.sh("settings put system user_rotation 1"); time.sleep(3)
    for i in range(2): L.snap(f"E_큰글자가로_{i+1}"); time.sleep(0.5)
    L.sh("input swipe 1200 900 1200 200 400"); time.sleep(1)
    L.snap("E_큰글자가로_스크롤후1")
    L.sh("input swipe 1200 900 1200 100 400"); time.sleep(1)
    L.snap("E_큰글자가로_스크롤후2")
finally:
    L.sh("settings put system user_rotation 0"); L.sh("settings put system accelerometer_rotation 1")
    L.sh("settings put system font_scale 1.0"); time.sleep(2.5)
L.log("E 복원", font_scale=L.sh("settings get system font_scale"), rot=L.sh("settings get system user_rotation"))
L.sh("input keyevent KEYCODE_HOME")
