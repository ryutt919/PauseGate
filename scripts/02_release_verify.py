"""YouSure v5 release runtime checks and reproducible screenshots.

Inputs: rooted API35 emulator, release APK; output: evidence directory with JSON/PNG.
Run: python scripts/02_release_verify.py --apk app/build/outputs/apk/release/app-release.apk
Only emulator-5554 is accepted. Existing package data and device settings are restored.
"""
import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

VERSION = "v5"
ROOT = Path(__file__).resolve().parents[1]
ADB = "C:/dev/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
PACKAGE = "com.kimt9.pausegate"
TARGET = "com.android.settings"
SERVICE = f"{PACKAGE}/{PACKAGE}.GateService"
DATA = f"/data/data/{PACKAGE}"
PREF = f"{DATA}/shared_prefs/pausegate.xml"
DEFAULT_OUTPUT = ROOT / "evidence/v5_20261030_release"
EXTENSION_TAP = (540, 400)  # Measured from the 1080x2400 intervention screenshot.
OVERLAY_CONFIRM_TAP = (895, 1616)  # Measured from overlay_dialog_probe2.png, keyboard closed.
KST = timezone(timedelta(hours=9))


class Verify:
    def __init__(self, output):
        self.output = output
        output.mkdir(parents=True, exist_ok=True)
        self.events = []
        self.started = time.monotonic()

    def adb(self, *args, data=None, timeout=45):
        result = subprocess.run([ADB, "-s", SERIAL, *map(str, args)], input=data,
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"adb {args[:3]}: {result.stderr.decode('utf-8', 'replace')}")
        return result.stdout

    def sh(self, text):
        return self.adb("shell", text).decode("utf-8").strip()

    def record(self, name, **data):
        event = {"check": name, "elapsed": round(time.monotonic() - self.started, 2),
                 "time": datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST"), **data}
        self.events.append(event)
        (self.output / "00_runtime.json").write_text(json.dumps(
            {"version": VERSION, "events": self.events}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(event, ensure_ascii=False), flush=True)

    def snap(self, name):
        path = self.output / f"{name}.png"
        path.write_bytes(self.adb("exec-out", "screencap", "-p"))
        return path

    def dump(self, name):
        self.sh("uiautomator dump /sdcard/yousure.xml")
        data = self.adb("exec-out", "cat", "/sdcard/yousure.xml")
        diagnostics = ROOT / "build/runtime/v5/ui"
        diagnostics.mkdir(parents=True, exist_ok=True)
        (diagnostics / f"{len(list(diagnostics.glob('*.xml'))):02d}_{name}.xml").write_bytes(data)
        return ET.fromstring(data)

    def tap_text(self, text, name, contains=False):
        root = self.dump(name)
        nodes = [n for n in root.iter("node") if
                 (text in n.get("text", "") if contains else n.get("text") == text)]
        assert nodes, f"Missing UI text {text}"
        bounds = [int(v) for v in re.findall(r"\d+", nodes[0].get("bounds"))]
        self.sh(f"input tap {(bounds[0] + bounds[2]) // 2} {(bounds[1] + bounds[3]) // 2}")

    def pref(self):
        return {n.get("name"): n.get("value") for n in ET.fromstring(self.adb("exec-out", "cat", PREF))}

    def wait_pref(self, key, expected):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if int(self.pref().get(key, -1)) == expected:
                return
            time.sleep(0.1)
        raise AssertionError(f"{key}: expected {expected}, got {self.pref().get(key)}")

    def recover_baseline(self, baseline):
        """Restore the project's preserved synthetic emulator baseline after an interrupted test."""
        self.adb("root")
        self.adb("wait-for-device")
        self.sh(f"am force-stop {PACKAGE}")
        for local, remote in [(baseline / "00_prefs_시험전.xml", PREF),
                              (baseline / "00_stats_시험전.db", f"{DATA}/databases/pausegate_stats.db")]:
            assert local.is_file()
            self.adb("push", local, remote)
        uid = self.sh(f"stat -c %u {DATA}")
        self.sh(f"chown {uid}:{uid} {DATA}/databases/pausegate_stats.db {PREF}")
        import sqlite3
        database = self.adb("exec-out", "cat", f"{DATA}/databases/pausegate_stats.db")
        with sqlite3.connect(":memory:") as connection:
            connection.deserialize(database)
            assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        self.record("preserved synthetic emulator baseline restored", source=str(baseline))

    def configure(self, updates):
        self.sh(f"am force-stop {PACKAGE}")
        root = ET.fromstring(self.adb("exec-out", "cat", PREF))
        for node in list(root):
            if node.get("name") in updates or node.get("name") == "gated":
                root.remove(node)
        ET.SubElement(ET.SubElement(root, "set", {"name": "gated"}), "string").text = TARGET
        for name, (kind, value) in updates.items():
            ET.SubElement(root, kind, name=name, value=str(value))
        self.adb("shell", f"cat > {PREF}", data=ET.tostring(root, encoding="utf-8", xml_declaration=True))
        self.sh(f"am start -n {PACKAGE}/.MainActivity")
        self.sh(f"settings put secure enabled_accessibility_services {SERVICE}")
        self.sh("settings put secure accessibility_enabled 1")
        time.sleep(2)

    def home(self):
        self.sh(f"am start -n {PACKAGE}/.MainActivity")
        time.sleep(0.8)

    def target(self):
        self.sh("input keyevent KEYCODE_HOME")
        time.sleep(0.4)
        self.sh("am start -n com.android.settings/.Settings")
        time.sleep(0.8)

    def set_hours(self, value):
        root = self.dump("input_hours")
        node = next(n for n in root.iter("node") if n.get("class") == "android.widget.EditText")
        a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
        self.sh(f"input tap {(a+c)//2} {(b+d)//2}")
        self.sh("input keyevent KEYCODE_MOVE_END")
        for _ in range(4):
            self.sh("input keyevent KEYCODE_DEL")
        self.sh(f"input text {value}")

    def event(self):
        import sqlite3
        database = self.adb("exec-out", "cat", f"{DATA}/databases/pausegate_stats.db")
        with sqlite3.connect(":memory:") as connection:
            connection.deserialize(database)
            return connection.execute("SELECT id, outcome, (SELECT COUNT(*) FROM events WHERE pkg=?) "
                                      "FROM events WHERE pkg=? ORDER BY id DESC LIMIT 1", (TARGET, TARGET)).fetchone()

    def run(self, apk):
        assert self.sh("getprop ro.kernel.qemu") == "1"
        self.adb("root")
        self.adb("wait-for-device")
        self.adb("install", "-r", apk, timeout=120)
        assert "versionName=0.9.0" in self.sh(f"dumpsys package {PACKAGE}")
        self.home()
        settings = {key: self.sh(f"settings get {scope} {key}") for scope, key in [
            ("secure", "enabled_accessibility_services"), ("secure", "accessibility_enabled"),
            ("system", "accelerometer_rotation"), ("system", "user_rotation")]}
        self.sh("settings delete secure enabled_accessibility_services")
        self.sh(f"am force-stop {PACKAGE}")
        recovery = ROOT / "build/recovery/v5"
        recovery.mkdir(parents=True, exist_ok=True)
        archive = recovery / "package_data.tar"
        self.sh(f"tar -C {DATA} -cf /data/local/tmp/yousure_backup.tar shared_prefs databases")
        self.adb("pull", "/data/local/tmp/yousure_backup.tar", archive)
        backup = archive.read_bytes()
        (recovery / "settings.json").write_text(json.dumps(settings), encoding="utf-8")
        with tarfile.open(fileobj=io.BytesIO(backup)) as tar:
            original_files = {m.name: hashlib.sha256(tar.extractfile(m).read()).hexdigest()
                              for m in tar.getmembers() if m.isfile()}
        try:
            self.sh("input keyevent KEYCODE_WAKEUP")
            self.sh("wm dismiss-keyguard")
            self.configure({"enabled": ("boolean", "true"), "breathing": ("boolean", "true"),
                "delaySec": ("int", 3), "limitDelaySec": ("int", 6), "cooldownSec": ("int", 10),
                "themeIdx": ("int", 0), "graceSec": ("int", 1), f"limit_{TARGET}": ("int", 30),
                f"cycle_used_{TARGET}": ("long", 600_000), f"cycle_until_{TARGET}": ("long", 0),
                f"cycle_limit_{TARGET}": ("long", 0), f"graceUntil_{TARGET}": ("long", 0)})
            self.tap_text("이번 주기만 시간 늘리기", "home_before", contains=True)
            self.snap("01_extension_dialog")
            self.set_hours("0")
            self.tap_text("늘리기", "invalid_input")
            assert "이번 주기만 시간 늘리기" in ET.tostring(self.dump("invalid_stays"), encoding="unicode")
            assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 0
            self.set_hours("2")
            self.tap_text("취소", "cancel")
            assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 0
            self.record("invalid input and cancellation preserve quota", done=1, total=7)
            self.tap_text("이번 주기만 시간 늘리기", "home_cancelled", contains=True)
            self.set_hours("2")
            self.tap_text("늘리기", "save")
            self.wait_pref(f"cycle_limit_{TARGET}", 7_200_000)
            values = self.pref()
            assert int(values[f"cycle_limit_{TARGET}"]) == 7_200_000
            assert int(values[f"cycle_used_{TARGET}"]) == 600_000
            assert int(values[f"limit_{TARGET}"]) == 30
            self.snap("02_extended_home")
            self.record("home extension preserves usage and default", done=2, total=7)
            self.sh(f"am force-stop {PACKAGE}")
            self.home()
            assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 7_200_000
            self.record("extension survives process restart", done=3, total=7)
            self.configure({f"cycle_used_{TARGET}": ("long", 600_000)})
            self.target()
            start = time.monotonic()
            self.snap("03_wave_start")
            for elapsed, name in [(4, "04_wave_top"), (9, "05_wave_finished"), (13, "06_wave_still_finished")]:
                time.sleep(max(0, elapsed - (time.monotonic() - start)))
                self.snap(name)
            from PIL import Image
            top = Image.open(self.output / "04_wave_top.png").convert("RGB")
            end = Image.open(self.output / "05_wave_finished.png").convert("RGB")
            later = Image.open(self.output / "06_wave_still_finished.png").convert("RGB")
            point = (top.width // 10, top.height * 3 // 4)
            assert sum(abs(a-b) for a,b in zip(top.getpixel(point), end.getpixel(point))) > 50
            assert end.getpixel(point) == later.getpixel(point)
            self.record("wave rises once then stays drained", done=4, total=7, point=point)
            # The accessibility overlay can be absent from uiautomator's hierarchy.
            # Its extension row is 48dp below the top 52dp action row and system inset.
            self.sh(f"input tap {EXTENSION_TAP[0]} {EXTENSION_TAP[1]}")
            time.sleep(0.5)
            dialog_path = self.snap("07_overlay_dialog")
            # Android omits accessibility overlay windows from uiautomator dumps.
            # The visible light dialog and the saved next whole-hour quota verify this path.
            assert Image.open(dialog_path).convert("RGB").getpixel((100, 1300)) == (255, 255, 255)
            previous_event = self.event()
            self.sh(f"input tap {OVERLAY_CONFIRM_TAP[0]} {OVERLAY_CONFIRM_TAP[1]}")
            self.wait_pref(f"cycle_limit_{TARGET}", 10_800_000)
            assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 10_800_000
            self.snap("08_overlay_extended")
            assert self.event() == previous_event and previous_event[1] == "PENDING"
            self.record("accessibility overlay extension works", done=5, total=7)
            self.home()
            now = int(self.sh("date +%s")) * 1000
            self.configure({f"cycle_used_{TARGET}": ("long", 1_800_000),
                            f"cycle_limit_{TARGET}": ("long", 0),
                            "limitDelaySec": ("int", 60),
                            f"cycle_until_{TARGET}": ("long", now + 90_000)})
            self.target()
            time.sleep(0.5)
            self.sh(f"input tap {EXTENSION_TAP[0]} {EXTENSION_TAP[1]}")
            time.sleep(0.5)
            self.snap("12_cooldown_extension_dialog")
            previous_event = self.event()
            self.sh(f"input tap {OVERLAY_CONFIRM_TAP[0]} {OVERLAY_CONFIRM_TAP[1]}")
            self.wait_pref(f"cycle_until_{TARGET}", 0)
            values = self.pref()
            assert int(values[f"cycle_until_{TARGET}"]) == 0
            assert int(values[f"cycle_used_{TARGET}"]) == 1_800_000
            assert int(values[f"cycle_limit_{TARGET}"]) == 3_600_000
            self.snap("09_cooldown_extended")
            assert self.event() == previous_event and previous_event[1] == "PENDING"
            time.sleep(2)
            self.snap("11_no_wave_restart")
            resumed = Image.open(self.output / "11_no_wave_restart.png").convert("RGB")
            assert resumed.getpixel(point) == end.getpixel(point)
            self.record("cooldown extension preserves usage and resumes quota", done=6, total=7)
            self.home()
            now = int(self.sh("date +%s")) * 1000
            self.configure({f"cycle_used_{TARGET}": ("long", 3_600_000),
                            f"cycle_until_{TARGET}": ("long", now + 9_000)})
            time.sleep(10)
            values = self.pref()
            assert int(values[f"cycle_limit_{TARGET}"]) == 0
            assert int(values[f"cycle_used_{TARGET}"]) == 0
            self.snap("10_next_cycle")
            self.record("next cycle restores default quota", done=7, total=7)
        finally:
            self.sh("settings delete secure enabled_accessibility_services")
            self.sh(f"am force-stop {PACKAGE}")
            self.adb("push", archive, "/data/local/tmp/yousure_backup.tar")
            self.sh(f"tar -C {DATA} -xf /data/local/tmp/yousure_backup.tar")
            uid = self.sh(f"stat -c %u {DATA}")
            self.sh(f"chown -R {uid}:{uid} {DATA}/shared_prefs {DATA}/databases")
            for name, digest in original_files.items():
                assert hashlib.sha256(self.adb("exec-out", "cat", f"{DATA}/{name}")).hexdigest() == digest, name
            for key, value in settings.items():
                scope = "secure" if key in ("enabled_accessibility_services", "accessibility_enabled") else "system"
                self.sh(f"settings delete {scope} {key}" if value == "null" else f"settings put {scope} {key} {value}")
                assert self.sh(f"settings get {scope} {key}") == value
            self.record("original data hashes and settings restored", files=len(original_files))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--recover-baseline", type=Path)
    args = parser.parse_args()
    verifier = Verify(args.output)
    if args.recover_baseline:
        verifier.recover_baseline(args.recover_baseline.resolve())
    else:
        verifier.run(args.apk.resolve())
