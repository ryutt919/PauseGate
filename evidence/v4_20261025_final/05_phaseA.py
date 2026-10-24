"""A. 일반 개입: 패널 2행 문구('이 앱 오늘 개입 N회...'), 폭, 위치, 숫자 일치. VERSION v4 final (0.8.0/9)."""
import sys, time, json
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
OPEN = (792, 248)
L.wake(); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
L.set_prefs({"enabled": ("boolean", "true"), "breathing": ("boolean", "false"), "delaySec": ("int", "3"), "graceSec": ("int", "5"),
             "limitDelaySec": ("int", "6"), "cooldownSec": ("int", "120"), "themeIdx": ("int", "0"),
             "limit_" + L.TARGET: ("int", "1"), "cycle_used_" + L.TARGET: ("long", "0"), "cycle_until_" + L.TARGET: ("long", "0"),
             "graceUntil_" + L.TARGET: ("long", "0")})
L.wake(); L.reconnect()
L.log("A 시작", stats=L.stats_today(), prefs={n.get('name'): n.get('value') for n in L.prefs() if n.get('value')})
res = {}
for rnd in (1, 2):
    L.target(); time.sleep(1.8)
    st = L.stats_today()
    L.log(f"A 라운드{rnd} 개입 표시", db_stats_before_this_event=st)
    paths = []
    for i in range(3):
        paths.append(L.snap(f"A{rnd}_개입_{i+1}")); time.sleep(0.8)
    res[rnd] = {"db": st, "shots": [p.name for p in paths]}
    if rnd == 1:
        time.sleep(1.5); L.tap(*OPEN); time.sleep(1.5)
        L.log("A 그래도 열기 탭", db=L.stats_today(), fg=L.sh("dumpsys window | grep -m1 mCurrentFocus"))
        time.sleep(3); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1.5)
json.dump(res, open('05_phaseA_결과.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
L.log("A 끝", stats=L.stats_today())
