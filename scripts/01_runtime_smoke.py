"""API35 emulator runtime smoke test. VERSION = "v2".

Requires an already installed debuggable APK. Only emulator-5554 is accepted.
Run with --run to exercise two real 60s cycles with a 120s cooldown period.
Cooldown allows opening after an independent six-second intervention delay.
Existing statistics remain intact; test preferences and permissions are changed.
"""

import argparse
import json
from pathlib import Path
import sqlite3
import sys
import subprocess
import time
import xml.etree.ElementTree as ET


VERSION = "v2"
SERIAL = "emulator-5554"
PACKAGE = "com.kimt9.pausegate"
TARGET = "com.android.settings"
COMPONENT = "com.android.settings/.Settings"
SERVICE = PACKAGE + "/" + PACKAGE + ".GateService"
PREF_FILE = "shared_prefs/pausegate.xml"
# Measured from 03_도구간섭확인전.png on the asserted 1080x2400/420dpi AVD.
OPEN_TAP = (810, 250)


class SmokeTest:
    def __init__(self, adb: str, output: Path, cooldown: int):
        self.adb = adb
        self.output = output / (VERSION + time.strftime("_%Y%m%d_%H%M%S"))
        self.output.mkdir(parents=True, exist_ok=False)
        self.cooldown = cooldown
        self.events = []
        self.index = 0

    def command(self, *args: str, data=None, timeout=25):
        result = subprocess.run(
            [self.adb, "-s", SERIAL, *args], input=data,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout,
        )
        if result.returncode:
            raise RuntimeError(f"adb command failed: {args[:3]}: "
                               + result.stderr.decode("utf-8", errors="replace")[:300])
        return result.stdout

    def shell(self, *args: str):
        return self.command("shell", *args).decode("utf-8", errors="strict").strip()

    def record(self, action: str, **values):
        event = {"elapsed_seconds": round(time.monotonic() - self.started, 2),
                 "action": action, **values}
        self.events.append(event)
        print(json.dumps(event, ensure_ascii=False), flush=True)
        (self.output / "00_runtime_log.json").write_text(
            json.dumps({"version": VERSION, "serial": SERIAL, "events": self.events},
                       ensure_ascii=False, indent=2), encoding="utf-8")

    def prefs(self):
        return ET.fromstring(self.shell("run-as", PACKAGE, "cat", PREF_FILE))

    def write_prefs(self, root):
        self.command("shell", f"run-as {PACKAGE} sh -c 'cat > {PREF_FILE}'",
                     data=ET.tostring(root, encoding="utf-8", xml_declaration=True))

    def prepare_reentry_grace(self):
        # Keep the already granted 24h deadline but make later openings grant 1s.
        # Stop first so Android's cached SharedPreferences cannot overwrite setup.
        self.shell("am", "force-stop", PACKAGE)
        root = self.prefs()
        values = {node.get("name"): node for node in root}
        grace_until = int(values["graceUntil_" + TARGET].get("value"))
        assert grace_until > self.now() + 23 * 3_600_000, "Ordinary 24h grace was not granted"
        values["graceSec"].set("value", "1")
        self.write_prefs(root)
        self.reconnect()
        self.target()
        self.record("첫 유예 유지 및 이후 재진입 시험 준비", initial_grace_hours=24,
                    subsequent_grace_seconds=1)

    def state(self):
        values = {node.get("name"): node.get("value") for node in self.prefs()}
        return {"used_ms": int(values.get("cycle_used_" + TARGET) or 0),
                "until_ms": int(values.get("cycle_until_" + TARGET) or 0)}

    def now(self):
        # Milliseconds in the emulator clock domain, not the host clock.
        return int(self.shell("date", "+%s")) * 1000

    def latest_event(self, cooldown: bool):
        # Inspect only this test target's newest cooldown event, in memory.
        # Do not save the database or emit unrelated usage history.
        database = self.command("exec-out", "run-as", PACKAGE, "cat", "databases/pausegate_stats.db")
        with sqlite3.connect(":memory:") as connection:
            connection.deserialize(database)
            row = connection.execute(
                "SELECT id, outcome, waited_ms FROM events WHERE pkg=? AND ts>=? AND limit_reached=? "
                "ORDER BY id DESC LIMIT 1", (TARGET, self.test_started_ms, int(cooldown)),
            ).fetchone()
        assert row is not None, "Intervention event is missing"
        return row

    def configure(self, initial_used_ms=0):
        self.shell("am", "force-stop", PACKAGE)
        root = self.prefs()
        keys = {"enabled": ("boolean", "true"), "breathing": ("boolean", "false"),
                "delaySec": ("int", "3"), "graceSec": ("int", "86400"),
                "limitDelaySec": ("int", "6"), "cooldownSec": ("int", str(self.cooldown)),
                "limit_" + TARGET: ("int", "1"),
                "cycle_used_" + TARGET: ("long", str(initial_used_ms)),
                "cycle_until_" + TARGET: ("long", "0"),
                "graceUntil_" + TARGET: ("long", "0")}
        for node in list(root):
            if node.get("name") in keys or node.get("name") == "gated":
                root.remove(node)
        for key, (tag, value) in keys.items():
            ET.SubElement(root, tag, {"name": key, "value": value})
        ET.SubElement(ET.SubElement(root, "set", {"name": "gated"}), "string").text = TARGET
        self.write_prefs(root)
        self.shell("cmd", "appops", "set", PACKAGE, "GET_USAGE_STATS", "allow")
        self.shell("svc", "power", "stayon", "true")
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard")
        self.reconnect()
        self.record("시험 설정 적용", limit_seconds=60, cooldown_seconds=self.cooldown,
                    cooldown_delay_seconds=6, ordinary_delay_seconds=3,
                    grace_seconds=86400, target=TARGET)

    def reconnect(self):
        self.shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        previous = self.shell("settings", "get", "secure", "enabled_accessibility_services")
        services = [item for item in previous.split(":")
                    if item and item != "null" and PACKAGE not in item]
        if services:
            self.shell("settings", "put", "secure", "enabled_accessibility_services", ":".join(services))
        else:
            self.shell("settings", "delete", "secure", "enabled_accessibility_services")
        self.shell("settings", "put", "secure", "enabled_accessibility_services", ":".join(services + [SERVICE]))
        self.shell("settings", "put", "secure", "accessibility_enabled", "1")
        time.sleep(2)

    def snapshot(self, name: str):
        self.index += 1
        stem = f"{self.index:02d}_{name}"
        (self.output / (stem + ".png")).write_bytes(self.command("exec-out", "screencap", "-p"))

    def tap_open(self):
        self.shell("input", "tap", str(OPEN_TAP[0]), str(OPEN_TAP[1]))

    def target(self):
        self.shell("am", "start", "-n", COMPONENT)
        time.sleep(1)

    def open_cooldown(self, label: str):
        self.snapshot(label)
        before = self.latest_event(True)
        assert before[1] == "PENDING", "Cooldown intervention must await a choice"
        self.tap_open()
        early = self.latest_event(True)
        assert early[0] == before[0] and early[1] == "PENDING", "Early tap bypassed the cooldown delay"
        self.record("쿨타임 조기 열기 거부 확인", checkpoint=label, event_id=early[0])
        time.sleep(6)
        self.snapshot(label + "_대기완료")
        self.tap_open()
        time.sleep(1)
        row = self.latest_event(True)
        assert row[1] == "OPENED", "Cooldown opening was not recorded"
        assert row[2] >= 6000, "Cooldown opened before its six-second delay"
        state = self.state()
        assert state["used_ms"] == 60_000, "Usage changed during the cooldown period"
        assert state["until_ms"] == self.active_cooldown_until, "Opening extended the cooldown period"
        self.record("쿨타임 대기 후 열기 확인", checkpoint=label, event_id=row[0],
                    waited_ms=row[2], outcome=row[1], **state)

    def wait_cooling(self, timeout=85):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.state()
            if state["until_ms"] > self.now():
                assert state["used_ms"] == 60_000, "Consumed budget must be capped at one minute"
                return state
            time.sleep(2)
        raise AssertionError("No cooldown after actual continuous foreground use")

    def run(self):
        self.started = time.monotonic()
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Only an emulator may be used"
        assert self.shell("getprop", "sys.boot_completed") == "1", "Emulator is not booted"
        assert "1080x2400" in self.shell("wm", "size"), "Screenshot-based tap requires 1080x2400"
        assert "420" in self.shell("wm", "density"), "Screenshot-based tap requires 420dpi"
        self.shell("run-as", PACKAGE, "id")  # Release APK must fail here before any mutation.
        self.test_started_ms = self.now()
        self.configure()
        self.target()
        time.sleep(4)
        self.snapshot("일반개입")
        assert self.latest_event(False)[1] == "PENDING", "Ordinary intervention is missing"
        self.tap_open()
        time.sleep(2)
        ordinary = self.latest_event(False)
        assert ordinary[1] == "OPENED" and ordinary[2] >= 3000, "Ordinary opening was not recorded"
        self.prepare_reentry_grace()
        self.record("첫 주기 실사용 시작")
        first = self.wait_cooling()
        self.active_cooldown_until = first["until_ms"]
        self.open_cooldown("첫쿨타임개입")
        self.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(1)
        self.target()
        self.open_cooldown("쿨타임중재진입")
        self.shell("am", "force-stop", PACKAGE)
        self.reconnect()
        assert self.state()["until_ms"] == first["until_ms"], "Restart changed cooldown deadline"
        self.target()
        self.open_cooldown("재시작쿨타임")
        self.record("쿨타임 만료 대기", **self.state())
        deadline = time.monotonic() + self.cooldown + 15
        while time.monotonic() < deadline:
            state = self.state()
            if state["until_ms"] == 0:
                assert state["used_ms"] < 15_000, "New cycle did not reset its budget"
                break
            time.sleep(1)
        else:
            raise AssertionError("Cooldown did not expire and reset")
        self.snapshot("새한도시작")
        assert self.latest_event(True)[1] == "OPENED", "An unexpected cooldown intervention remains pending"
        self.record("두 번째 실사용 주기 시작", **self.state())
        second = self.wait_cooling()
        assert second["until_ms"] > first["until_ms"], "Second cycle reused the first cooldown"
        self.active_cooldown_until = second["until_ms"]
        self.open_cooldown("두번째쿨타임개입")
        self.record("검증 통과", cycles=2)

    def run_expiry_boundary(self):
        self.started = time.monotonic()
        assert self.shell("getprop", "ro.kernel.qemu") == "1", "Only an emulator may be used"
        assert "1080x2400" in self.shell("wm", "size"), "Screenshot-based tap requires 1080x2400"
        assert "420" in self.shell("wm", "density"), "Screenshot-based tap requires 420dpi"
        self.shell("run-as", PACKAGE, "id")
        self.test_started_ms = self.now()
        self.cooldown = 2
        self.configure(initial_used_ms=59_900)
        self.target()
        time.sleep(4)
        assert self.latest_event(False)[1] == "PENDING", "Initial ordinary intervention is missing"
        self.tap_open()
        time.sleep(9)
        self.snapshot("짧은기간만료후일반대기전환")
        row = self.latest_event(True)
        assert row[1] == "PENDING", "Expiry must not record an opening without a tap"
        assert self.state()["until_ms"] == 0, "Short cooldown period did not expire"
        self.record("기간만료 후 실제열기 전 통계 확인", event_id=row[0], outcome=row[1])
        self.tap_open()
        time.sleep(1)
        opened = self.latest_event(True)
        assert opened[0] == row[0] and opened[1] == "OPENED", "Replacement must reuse the same event"
        database = self.command("exec-out", "run-as", PACKAGE, "cat", "databases/pausegate_stats.db")
        with sqlite3.connect(":memory:") as connection:
            connection.deserialize(database)
            count = connection.execute(
                "SELECT COUNT(*) FROM events WHERE pkg=? AND ts>=?", (TARGET, self.test_started_ms),
            ).fetchone()[0]
        assert count == 2, "Expiry created an extra intervention event"
        self.record("기간만료 경계 검증 통과", event_count=count, reused_event_id=opened[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Explicitly configure and test emulator-5554")
    parser.add_argument("--expiry-boundary", action="store_true", help="Test 2s period expiry during a 6s delay")
    parser.add_argument("--adb", default="C:/dev/android-sdk/platform-tools/adb.exe")
    parser.add_argument("--cooldown-seconds", type=int, default=120)
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).resolve().parents[1] / "evidence/v2_20261024_runtime")
    args = parser.parse_args()
    if not args.run:
        parser.print_help()
        return
    if not 120 <= args.cooldown_seconds <= 300:
        parser.error("cooldown must be 120..300 seconds for restart and UI assertions")
    test = SmokeTest(args.adb, args.output, args.cooldown_seconds)
    try:
        if args.expiry_boundary:
            test.run_expiry_boundary()
        else:
            test.run()
    except Exception as exc:
        if hasattr(test, "started"):
            test.record("검증 실패", error_type=type(exc).__name__, reason=str(exc))
        raise


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
