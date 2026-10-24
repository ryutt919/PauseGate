"""a. 일반 개입: 패널 위치/크기, 사용 시간/통계 행, 숨쉬기와 겹침, 통계 숫자 변화."""
import sys, time, json
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib'); M = importlib.import_module('04_measure')
CLOSE, OPEN = (288, 248), (792, 248)
L.wake(); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
L.log("A 시작", stats=L.stats_today(), prefs={n.get('name'): n.get('value') for n in L.prefs() if n.get('value')})
res = {}
for rnd in (1, 2):
    L.target(); time.sleep(1.8)
    st = L.stats_today()
    L.log(f"A 라운드{rnd} 개입 표시", db_stats_before_this_event=st)
    paths = []
    for i in range(4):
        paths.append(L.snap(f"A{rnd}_개입_{i+1}")); time.sleep(1.0)
    res[rnd] = {"db": st, "shots": [p.name for p in paths]}
    if rnd == 1:
        time.sleep(1); L.tap(*OPEN); time.sleep(1.5)   # 3초 대기 후 그래도 열기 -> OPENED 기록
        L.log("A 그래도 열기 탭", db=L.stats_today(), fg=L.sh("dumpsys window | grep -m1 mCurrentFocus"))
        time.sleep(7); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1.5)
json.dump(res, open('05_phaseA_결과.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
L.log("A 끝", stats=L.stats_today())
