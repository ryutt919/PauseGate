"""i. 다른 앱(Clock): 통계 0일 때 통계 행 숨김, 사용 1분 미만. 다른 앱(Contacts): 권한 없음+통계 0+쿨타임이면 쿨타임 줄만 있는 패널, 만료 시 패널째 사라짐."""
import sys, time
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
def launch(pkg): L.sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
def conf(pkg, cooldown_ms=0):
    L.TARGET = pkg
    now = L.emu_ms()
    u = {"enabled": ("boolean", "true"), "breathing": ("boolean", "false"), "delaySec": ("int", "3"), "graceSec": ("int", "5"),
         "limitDelaySec": ("int", "6"), "cooldownSec": ("int", "1200"), "themeIdx": ("int", "0"), "graceUntil_" + pkg: ("long", "0")}
    rem = ["limit_" + pkg, "cycle_used_" + pkg, "cycle_until_" + pkg]
    if cooldown_ms:
        u.update({"limit_" + pkg: ("int", "1"), "cycle_used_" + pkg: ("long", "60000"), "cycle_until_" + pkg: ("long", str(now + cooldown_ms))}); rem = []
    L.set_prefs(u, remove=rem); L.reconnect()
    L.log("설정", pkg=pkg, cooldown_ms=cooldown_ms, stats=L.stats_today())
L.wake(); L.sh(f"cmd appops set {L.PKG} GET_USAGE_STATS allow")
# i1. Clock: 통계 0 -> 사용 행만, 닫은 뒤 통계 행 등장
conf("com.google.android.deskclock"); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
launch("com.google.android.deskclock"); time.sleep(2.5)
for i in range(2): L.snap(f"I1_Clock첫개입_{i+1}"); time.sleep(0.5)
L.tap(288, 248); time.sleep(2); L.log("I1 닫기 후", stats=L.stats_today(), fg=L.sh("dumpsys window | grep -m1 mCurrentFocus"))
launch("com.google.android.deskclock"); time.sleep(2.5)
for i in range(2): L.snap(f"I1_Clock두번째개입_{i+1}"); time.sleep(0.5)
L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
# i2. Contacts: 권한 없음, 통계 0, 쿨타임 40초
L.sh(f"cmd appops set {L.PKG} GET_USAGE_STATS deny")
try:
    conf("com.google.android.contacts", cooldown_ms=40_000); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
    until = int([n.get("value") for n in L.prefs() if n.get("name") == "cycle_until_com.google.android.contacts"][0])
    launch("com.google.android.contacts"); time.sleep(2.0)
    while True:
        rem = until / 1000 - float(L.sh("date +%s.%N"))
        if rem < -6: break
        L.snap(f"I2_Contacts쿨타임만_남은{max(int(rem),-9)}s_기대"); time.sleep(0.3 if -4 < rem < 4 else 4)
finally:
    L.sh(f"cmd appops set {L.PKG} GET_USAGE_STATS allow")
    L.log("appops 복원", v=L.sh(f"cmd appops get {L.PKG} GET_USAGE_STATS"))
L.sh("input keyevent KEYCODE_HOME")
