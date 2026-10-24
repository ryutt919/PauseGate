"""b. 쿨타임 개입(패널 3행), 열기 대기 종료 후 감소, 중복 줄 없음, 만료 후 일반 개입 전환. c. 가로 회전(쿨타임 3행 패널)."""
import sys, time, json
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
COOL = 120
def emu_now(): return float(L.sh("date +%s.%N"))
def state():
    v = {n.get("name"): n.get("value") for n in L.prefs()}
    return int(v.get("cycle_used_" + L.TARGET) or 0), int(v.get("cycle_until_" + L.TARGET) or 0)
shots = []
def shot(tag, until):
    exp = until / 1000 - emu_now()
    p = L.snap(tag)
    shots.append({"file": p.name, "tag": tag, "kst": L.shots[-1]["kst"], "expected_remaining_s_at_capture": round(exp, 1)})
    return p
grace = L.emu_ms() + 86_400_000
L.set_prefs({"enabled": ("boolean", "true"), "breathing": ("boolean", "false"), "delaySec": ("int", "3"), "graceSec": ("int", "86400"),
             "limitDelaySec": ("int", "6"), "cooldownSec": ("int", str(COOL)), "themeIdx": ("int", "0"),
             "limit_" + L.TARGET: ("int", "1"), "cycle_used_" + L.TARGET: ("long", "0"), "cycle_until_" + L.TARGET: ("long", "0"),
             "graceUntil_" + L.TARGET: ("long", str(grace))})
L.wake(); L.reconnect()
L.log("B 설정 적용", stats=L.stats_today())
L.target(); L.log("B 대상 앱 실사용 시작, 쿨타임 진입 대기")
while True:
    used, until = state()
    if until > L.emu_ms(): break
    time.sleep(1)
t_cd = time.monotonic()
L.log("B 쿨타임 시작 감지", used_ms=used, until_ms=until, remaining_s=round(until / 1000 - emu_now(), 1), stats=L.stats_today())
for i in range(3): shot(f"B_개입직후_{i+1}", until); time.sleep(0.5)
while time.monotonic() - t_cd < 11: time.sleep(0.3)
for i in range(3): shot(f"B_대기완료후_{i+1}", until); time.sleep(0.5)
orig = L.sh("settings get system accelerometer_rotation")
try:
    L.sh("settings put system accelerometer_rotation 0"); L.sh("settings put system user_rotation 1"); time.sleep(3)
    disp = L.sh("dumpsys window displays | grep -m1 -o 'cur=[0-9x]*'")
    for i in range(2): shot(f"C_가로_{i+1}", until); time.sleep(0.5)
    L.log("C 가로 회전", display=disp, remaining_s=round(until / 1000 - emu_now(), 1))
finally:
    L.sh("settings put system user_rotation 0"); L.sh("settings put system accelerometer_rotation " + orig); time.sleep(3)
L.log("C 세로 복귀", display=L.sh("dumpsys window displays | grep -m1 -o 'cur=[0-9x]*'"))
shot("C_세로복귀", until)
while True:
    rem = until / 1000 - emu_now()
    if rem < -10: break
    shot("D_만료관측", until)
    time.sleep(0.2 if -7 < rem < 9 else 7.0)
L.log("D 만료 후 상태", prefs_used_until=state(), stats=L.stats_today())
json.dump(shots, open("07_phaseB_스크린샷목록.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
