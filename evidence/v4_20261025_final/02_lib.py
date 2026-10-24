"""v4 release 요약 패널 검증용 공용 함수 (emulator-5554, adb root 로 prefs/db 접근). 소스는 수정하지 않는다.
VERSION v4 (앱 0.8.0 / versionCode 9). v3 의 02_cooldown_base.py 를 바탕으로 복사해 수정했다.
"""
import json
import subprocess
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
PREF = f"/data/data/{PKG}/shared_prefs/pausegate.xml"
DB = f"/data/data/{PKG}/databases/pausegate_stats.db"
OUT = Path(__file__).resolve().parent
KST = timezone(timedelta(hours=9))
LOG = OUT / "03_실행로그.json"
events = []
shots = []
t0 = time.monotonic()


def adb(*args, data=None, timeout=60):
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
    old = json.loads(LOG.read_text(encoding="utf-8")) if LOG.exists() else []
    LOG.write_text(json.dumps(old + [ev], ensure_ascii=False, indent=2), encoding="utf-8")


def emu_ms():
    return int(sh("date +%s")) * 1000


def prefs():
    return ET.fromstring(adb("exec-out", "cat", PREF))


def write_prefs(root):
    adb("shell", f"sh -c 'cat > {PREF}'", data=ET.tostring(root, encoding="utf-8", xml_declaration=True))


def set_prefs(updates, remove=(), gated=True):
    """updates: {key: (tag, value)}. 앱을 멈추고 prefs 를 쓴다(재연결은 reconnect 로)."""
    sh(f"am force-stop {PKG}")
    root = prefs()
    for node in list(root):
        n = node.get("name")
        if n in updates or n in remove or n == "gated":
            root.remove(node)
    for k, (tag, val) in updates.items():
        ET.SubElement(root, tag, {"name": k, "value": str(val)})
    if gated:
        ET.SubElement(ET.SubElement(root, "set", {"name": "gated"}), "string").text = TARGET
    write_prefs(root)


def reconnect():
    adb("shell", f"am start -n {PKG}/.MainActivity")
    sh("settings put secure enabled_accessibility_services " + SERVICE)
    sh("settings put secure accessibility_enabled 1")
    time.sleep(2.5)
    sh("input keyevent KEYCODE_HOME")
    time.sleep(0.8)


def wake():
    sh("svc power stayon true")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")


def target():
    adb("shell", f"am start -n {COMPONENT}")


def snap(tag):
    """스크린샷 저장. 촬영 직전/직후 KST 를 목록에 남긴다."""
    k1 = now_kst()
    data = adb("exec-out", "screencap", "-p")
    path = OUT / f"{len(list(OUT.glob('[0-9][0-9]_*.png'))) + 10:02d}_{tag}.png"
    path.write_bytes(data)
    rec = {"file": path.name, "tag": tag, "kst": k1}
    shots.append(rec)
    print(json.dumps(rec, ensure_ascii=False), flush=True)
    return path


def db(sql):
    return sh(f"sqlite3 {DB} \"{sql}\"")


def stats_today():
    day = sh("date +%Y%m%d")
    r = db(f"select count(*), sum(case when outcome='OPENED' then 1 else 0 end) from events where pkg='{TARGET}' and day='{day}' and outcome!='PENDING'")
    shown, opened = (int(x) if x else 0 for x in r.split("|"))
    return {"day": day, "shown": shown, "opened": opened, "kept": shown - opened}


def tap(x, y):
    sh(f"input tap {x} {y}")
