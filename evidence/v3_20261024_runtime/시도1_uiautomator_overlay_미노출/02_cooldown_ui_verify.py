"""쿨타임 남은 시간 줄 실행 검증 (emulator-5554 전용). 소스 코드는 수정하지 않는다.

prefs 로 대상 앱(com.android.settings), 주기 한도 1분, 쿨타임 120초, 쿨타임 중 대기 6초를 설정하고
uiautomator dump 와 스크린샷으로 개입 화면과 앱 홈의 쿨타임 문구를 관측한다.
"""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

ADB = "C:/dev/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
PKG = "com.kimt9.pausegate"
TARGET = "com.android.settings"
COMPONENT = "com.android.settings/.Settings"
SERVICE = PKG + "/" + PKG + ".GateService"
PREF = "shared_prefs/pausegate.xml"
OUT = Path(__file__).resolve().parent
KST = timezone(timedelta(hours=9))
COOLDOWN = 120
LINE_RE = re.compile(r"쿨타임 .*남음")

events = []
checks = []
t0 = time.monotonic()


def adb(*args, data=None, timeout=40):
    r = subprocess.run([ADB, "-s", SERIAL, *args], input=data, capture_output=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError(f"adb {args[:3]} failed: {r.stderr.decode('utf-8', 'replace')[:300]}")
    return r.stdout


def sh(cmd):
    return adb("shell", cmd).decode("utf-8", "replace").strip()


def now_kst():
    return datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST")


def log(action, **kw):
    ev = {"t": round(time.monotonic() - t0, 1), "kst": now_kst(), "action": action, **kw}
    events.append(ev)
    print(json.dumps(ev, ensure_ascii=False), flush=True)
    (OUT / "02_실행로그.json").write_text(json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8")


def emu_ms():
    return int(sh("date +%s")) * 1000


def prefs():
    return ET.fromstring(adb("exec-out", "run-as", PKG, "cat", PREF))


def write_prefs(root):
    adb("shell", f"run-as {PKG} sh -c 'cat > {PREF}'",
        data=ET.tostring(root, encoding="utf-8", xml_declaration=True))


def state():
    v = {n.get("name"): n.get("value") for n in prefs()}
    return int(v.get("cycle_used_" + TARGET) or 0), int(v.get("cycle_until_" + TARGET) or 0)


def snap(name):
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))


def dump(name):
    """uiautomator dump --windows 를 저장하고 (모든 text, 쿨타임 줄 목록, 열기 버튼 문구) 를 돌려준다."""
    stamp_before = time.monotonic()
    adb("shell", "uiautomator dump --windows /sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml").decode("utf-8", "replace")
    (OUT / f"{name}.xml").write_text(xml, encoding="utf-8")
    texts = [n.get("text") for n in ET.fromstring(xml).iter("node") if n.get("text")]
    lines = [t for t in texts if LINE_RE.search(t)]
    openbtn = [t for t in texts if "열기" in t]
    return {"kst": now_kst(), "dump_sec": round(time.monotonic() - stamp_before, 1),
            "cooldown_lines": lines, "open_button": openbtn, "texts": texts}


def parse_seconds(line):
    """'쿨타임 1분 40초 남음' 또는 '쿨타임 01:02:03 남음' 을 초로 바꾼다."""
    body = line.replace("쿨타임", "").replace("남음", "").strip()
    if ":" in body:
        h, m, s = (int(x) for x in body.split(":"))
        return h * 3600 + m * 60 + s
    total = 0
    for num, unit in re.findall(r"(\d+)\s*(시간|분|초)", body):
        total += int(num) * {"시간": 3600, "분": 60, "초": 1}[unit]
    return total


def check(item, ok, observed):
    checks.append({"항목": item, "결과": "통과" if ok else "실패", "관측값": observed, "시각 KST": now_kst()})
    log("판정 " + item, result="통과" if ok else "실패")


def reconnect():
    adb("shell", f"am start -n {PKG}/.MainActivity")
    prev = sh("settings get secure enabled_accessibility_services")
    svcs = [s for s in prev.split(":") if s and s != "null" and PKG not in s]
    sh("settings put secure enabled_accessibility_services " + ":".join(svcs + [SERVICE]))
    sh("settings put secure accessibility_enabled 1")
    time.sleep(2)


def configure():
    sh(f"am force-stop {PKG}")
    root = prefs()
    grace = emu_ms() + 86_400_000  # 24시간 유예를 미리 부여해 일반 개입을 건너뛴다
    keys = {"enabled": ("boolean", "true"), "breathing": ("boolean", "false"),
            "delaySec": ("int", "3"), "graceSec": ("int", "86400"),
            "limitDelaySec": ("int", "6"), "cooldownSec": ("int", str(COOLDOWN)),
            "limit_" + TARGET: ("int", "1"),
            "cycle_used_" + TARGET: ("long", "0"), "cycle_until_" + TARGET: ("long", "0"),
            "graceUntil_" + TARGET: ("long", str(grace))}
    for node in list(root):
        if node.get("name") in keys or node.get("name") == "gated":
            root.remove(node)
    for k, (tag, val) in keys.items():
        ET.SubElement(root, tag, {"name": k, "value": val})
    ET.SubElement(ET.SubElement(root, "set", {"name": "gated"}), "string").text = TARGET
    write_prefs(root)
    sh(f"cmd appops set {PKG} GET_USAGE_STATS allow")
    sh("svc power stayon true")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    reconnect()
    log("시험 설정 적용", limit_min=1, cooldown_sec=COOLDOWN, cooldown_wait_sec=6, target=TARGET)


def target():
    adb("shell", f"am start -n {COMPONENT}")
    time.sleep(1)


def main():
    assert sh("getprop ro.kernel.qemu") == "1" and "1080x2400" in sh("wm size")
    sh(f"run-as {PKG} id")
    configure()
    target()
    log("대상 앱 실사용 시작, 쿨타임 진입 대기")
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        used, until = state()
        if until > emu_ms():
            break
        time.sleep(1)
    else:
        raise AssertionError("쿨타임이 시작되지 않음")
    t_cd = time.monotonic()
    log("쿨타임 시작 감지", used_ms=used, until_ms=until, remaining_ms=until - emu_ms())

    # a. 쿨타임 개입 화면 문구와 감소
    a1 = dump("03_a_개입화면_dump1")
    snap("03_a_개입화면_1")
    time.sleep(2)
    a2 = dump("03_a_개입화면_dump2")
    snap("03_a_개입화면_2")
    log("a 관측", dump1=a1["cooldown_lines"], dump1_kst=a1["kst"], dump2=a2["cooldown_lines"],
        dump2_kst=a2["kst"], open_button=a1["open_button"], all_texts_dump1=a1["texts"])
    ok_a = bool(a1["cooldown_lines"] and a2["cooldown_lines"]) and \
        parse_seconds(a2["cooldown_lines"][0]) < parse_seconds(a1["cooldown_lines"][0])
    check("a 쿨타임 개입 화면 문구 표시와 감소", ok_a,
          {"dump1": a1["cooldown_lines"], "dump2": a2["cooldown_lines"],
           "열기 버튼(dump1)": a1["open_button"]})

    # b. 열기 대기(6초) 종료 후에도 줄이 감소
    while time.monotonic() - t_cd < 9:
        time.sleep(0.5)
    b1 = dump("04_b_대기완료후_dump1")
    snap("04_b_대기완료후_1")
    time.sleep(2)
    b2 = dump("04_b_대기완료후_dump2")
    snap("04_b_대기완료후_2")
    log("b 관측", dump1=b1["cooldown_lines"], dump2=b2["cooldown_lines"], open_button1=b1["open_button"],
        open_button2=b2["open_button"], dump1_kst=b1["kst"], dump2_kst=b2["kst"])
    ok_b = bool(b1["cooldown_lines"] and b2["cooldown_lines"]) and \
        parse_seconds(b2["cooldown_lines"][0]) < parse_seconds(b1["cooldown_lines"][0]) and \
        any("그래도 열기" in t for t in b1["open_button"] + b2["open_button"])
    check("b 열기 대기 종료 후에도 줄 감소(버튼은 그래도 열기)", ok_b,
          {"dump1": b1["cooldown_lines"], "dump2": b2["cooldown_lines"],
           "버튼1": b1["open_button"], "버튼2": b2["open_button"]})

    # e. 가로 회전
    orig_accel = sh("settings get system accelerometer_rotation")
    try:
        sh("settings put system accelerometer_rotation 0")
        sh("settings put system user_rotation 1")
        time.sleep(3)
        size = sh("dumpsys window displays | grep -m1 -o 'cur=[0-9x]*'")
        e1 = dump("05_e_가로_dump")
        snap("05_e_가로")
        time.sleep(2)
        e2 = dump("05_e_가로_dump2")
        log("e 관측(가로)", display=size, dump1=e1["cooldown_lines"], dump2=e2["cooldown_lines"],
            open_button=e1["open_button"])
        ok_e = bool(e1["cooldown_lines"] and e2["cooldown_lines"]) and \
            parse_seconds(e2["cooldown_lines"][0]) < parse_seconds(e1["cooldown_lines"][0])
        check("e 가로 회전에서도 줄 표시와 감소", ok_e,
              {"display": size, "dump1": e1["cooldown_lines"], "dump2": e2["cooldown_lines"]})
    finally:
        sh("settings put system user_rotation 0")
        sh("settings put system accelerometer_rotation " + orig_accel)
        time.sleep(3)
    snap("06_e_세로복귀")
    log("세로 복귀", display=sh("dumpsys window displays | grep -m1 -o 'cur=[0-9x]*'"))

    # c. 앱 홈
    adb("shell", f"am start -n {PKG}/.MainActivity")
    time.sleep(2)
    c = []
    for i in range(3):
        d = dump(f"07_c_앱홈_dump{i + 1}")
        c.append(d)
        if i == 0:
            snap("07_c_앱홈_1")
        if i == 2:
            snap("07_c_앱홈_3")
        if i < 2:
            time.sleep(3)
    lines = [d["cooldown_lines"] for d in c]
    log("c 관측", lines=lines, kst=[d["kst"] for d in c])
    secs = [parse_seconds(l[0]) for l in lines if l]
    ok_c = len(secs) == 3 and secs[0] > secs[1] > secs[2]
    check("c 앱 홈 쿨타임 남은 시간 갱신", ok_c, lines)

    # d. 만료
    target()
    snap("08_d_재진입개입")
    pre = dump("08_d_재진입개입_dump")
    log("d 재진입 개입", lines=pre["cooldown_lines"], open_button=pre["open_button"])
    used, until = state()
    gone = None
    seen = []
    while time.monotonic() - t_cd < COOLDOWN + 25:
        d = dump(f"09_d_만료관측_{int(time.monotonic() - t_cd):03d}s")
        rem = (until - emu_ms()) / 1000
        seen.append({"kst": d["kst"], "emu_remaining_s": round(rem, 1), "lines": d["cooldown_lines"],
                     "open_button": d["open_button"]})
        log("d 폴링", **seen[-1])
        if rem < -3 and not d["cooldown_lines"]:
            gone = d
            snap("10_d_만료후")
            break
        time.sleep(2)
    final = dump("10_d_만료후_dump")
    snap("10_d_만료후_최종")
    st = state()
    log("d 만료 후 상태", prefs_used_until=st, texts=final["texts"], open_button=final["open_button"])
    had_line_before = any(s["lines"] for s in seen) or bool(pre["cooldown_lines"])
    ok_d = had_line_before and gone is not None and not final["cooldown_lines"] and st[1] == 0
    check("d 만료 후 일반 모드 전환과 줄 사라짐", ok_d,
          {"만료 전 줄 있었음": had_line_before, "만료 후 줄": final["cooldown_lines"],
           "버튼": final["open_button"], "cycle_until": st[1], "cycle_used": st[0]})


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as exc:
        log("검증 중단", error=type(exc).__name__, reason=str(exc))
        raise
    finally:
        (OUT / "01_실행검증.json").write_text(
            json.dumps({"항목들": checks, "생성 시각 KST": now_kst()}, ensure_ascii=False, indent=2),
            encoding="utf-8")
