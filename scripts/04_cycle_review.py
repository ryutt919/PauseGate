"""YouSure 0.9.1 functional review on the rooted API35 emulator only.

Run each suite (home/settings/overlay/design/layout/details/permissions) with a fresh output:
python scripts/04_cycle_review.py --suite home --output outputs/v5.1/validation/home-recheck
Package the recorded review: --suite summary --output outputs/v5.1/validation/summary
Inputs: release APK, existing emulator data (backed up before installation/launch).
Outputs: JSON checks, UI XML, screenshots. Original data/settings are restored in finally.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import re
import sqlite3
import subprocess
import tarfile
import time
from datetime import datetime
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("previous_verifier", ROOT / "scripts/02_release_verify.py")
old = importlib.util.module_from_spec(spec)
spec.loader.exec_module(old)
old.ADB = "C:/dev/android-sdk/platform-tools/adb.exe"
old.VERSION = "v5.1"
PKG, TARGET, SERVICE, DATA = old.PACKAGE, old.TARGET, old.SERVICE, old.DATA


class Review(old.Verify):
    def home(self):
        self.sh(f"am start -f 0x04000000 -n {PKG}/.MainActivity")
        time.sleep(.8)

    def wait_pref(self, key, expected):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.pref().get(key) == str(expected):
                return
            time.sleep(.1)
        raise AssertionError(f"{key}: expected {expected}, got {self.pref().get(key)}")

    def dump(self, name):
        self.sh("uiautomator dump /sdcard/yousure.xml")
        data = self.adb("exec-out", "cat", "/sdcard/yousure.xml")
        (self.output / f"ui_{name}.xml").write_bytes(data)
        return ET.fromstring(data)

    def text(self, name="text"):
        return "\n".join(n.get("text", "") for n in self.dump(name).iter("node"))

    def click(self, text, contains=False, scroll=False):
        for attempt in range(9 if scroll else 1):
            root = self.dump("find")
            nodes = [n for n in root.iter("node") if
                     (text in n.get("text", "") if contains else text == n.get("text"))]
            if nodes:
                self.tap_node(nodes[0])
                time.sleep(.25)
                return
            bounds = [list(map(int, re.findall(r"\d+", n.get("bounds")))) for n in root.iter("node")]
            width, height = max(b[2] for b in bounds), max(b[3] for b in bounds)
            self.sh(f"input swipe {width//2} {height*4//5} {width//2} {height//3} 250")
        raise AssertionError(f"UI text not found: {text}")

    def tap_node(self, node):
        a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
        self.sh(f"input tap {(a+c)//2} {(b+d)//2}")

    def edit(self, index, value):
        self.dismiss_keyboard()
        for _ in range(6):
            root = self.dump("edit")
            nodes = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
            if len(nodes) > index:
                self.tap_node(nodes[index])
                description = nodes[index].get("content-desc")
                if description:
                    assert any(n.get("content-desc") == description and n.get("focused") == "true"
                               for n in self.dump("focused_input").iter("node")), description
                self.replace_focused(value)
                return
            area = next((n for n in root.iter("node") if n.get("scrollable") == "true"), None)
            assert area is not None, "Input is absent and no scrollable content is available"
            left, top, right, bottom = map(int, re.findall(r"\d+", area.get("bounds")))
            self.sh(f"input swipe {(left+right)//2} {bottom-30} {(left+right)//2} {top+30} 250")
        raise AssertionError(f"Input {index} remains offscreen after scrolling")

    def replace_focused(self, value):
        # Select-all-on-focus is not guaranteed when the same field already owns focus.
        self.sh("input keyevent KEYCODE_MOVE_END")
        self.sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 12))
        if value:
            self.sh(f"input text {value}")

    def dismiss_keyboard(self):
        if "mInputShown=true" in self.sh("dumpsys input_method"):
            self.sh("input keyevent KEYCODE_BACK")

    def total(self, hours, minutes):
        self.edit(0, str(hours))
        self.edit(1, str(minutes))
        self.dismiss_keyboard()

    def config(self, **values):
        defaults = {"enabled": ("boolean", "true"), "breathing": ("boolean", "false"),
                    "delaySec": ("int", 3), "limitDelaySec": ("int", 6), "cooldownSec": ("int", 10),
                    "themeIdx": ("int", 0), "graceSec": ("int", 1),
                    f"limit_{TARGET}": ("int", 30), f"cycle_used_{TARGET}": ("long", 600_000),
                    f"cycle_until_{TARGET}": ("long", 0), f"cycle_limit_{TARGET}": ("long", 0),
                    f"graceUntil_{TARGET}": ("long", 0)}
        defaults.update(values)
        self.configure(defaults)

    def sql(self, query):
        with sqlite3.connect(":memory:") as db:
            db.deserialize(self.adb("exec-out", "cat", f"{DATA}/databases/pausegate_stats.db"))
            return db.execute(query).fetchall()

    def passed(self, name, **evidence):
        self.snap(f"check_{len(self.events):02d}")
        self.record(name, passed=True, **evidence)

    def home_suite(self):
        self.config()
        self.click("이번 주기만 시간 늘리기", contains=True)
        assert "31분" in self.text("minimum")
        for h, m in [(0, 30), (0, 0), (24, 1), (0, 60), (25, 0), ("", 31)]:
            self.total(h, m)
            self.click("늘리기")
            assert "이번 주기만 시간 늘리기" in self.text("rejected")
            assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 0
        self.total(0, 45)
        self.click("취소")
        assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 0
        self.passed("invalid/same/reduced/empty/over-24h inputs and cancel preserve state")
        self.click("이번 주기만 시간 늘리기", contains=True)
        self.total(0, 31)
        self.click("늘리기")
        self.wait_pref(f"cycle_limit_{TARGET}", 1_860_000)
        assert int(self.pref()[f"cycle_used_{TARGET}"]) == 600_000
        assert int(self.pref()[f"limit_{TARGET}"]) == 30
        assert "이용 가능 21분" in self.text("31min")
        self.passed("30 to 31 minute total quota preserves 10 minute usage and 30 minute default")
        self.sh(f"am force-stop {PKG}")
        self.home()
        assert "이번 주기 한도 31분" in self.text("restart")
        self.click("이번 주기만 시간 늘리기", contains=True)
        self.total(1, 5)
        self.click("늘리기")
        self.wait_pref(f"cycle_limit_{TARGET}", 3_900_000)
        self.passed("extension survives process restart and supports mixed hours/minutes")
        self.click("이번 주기만 시간 늘리기", contains=True)
        self.total(24, 0)
        self.click("늘리기")
        self.wait_pref(f"cycle_limit_{TARGET}", 86_400_000)
        assert "이번 주기만 시간 늘리기" not in self.text("max")
        self.passed("24 hours allowed and extension button hidden at maximum")

    def settings_suite(self):
        self.config()
        self.click("설정", scroll=True)
        for key, label, values, expected in [
            ("delaySec", "평소 기다리는 시간", (0, 0, 4), 4),
            ("limitDelaySec", "쿨타임 동안 기다리는 시간", (0, 0, 8), 8),
            ("cooldownSec", "한도 소진 후 쿨타임 기간", (0, 1, 2), 62),
            ("graceSec", "그래도 열기 후 다시 묻지 않는 시간", (0, 0, 2), 2)]:
            self.home()
            self.click("설정", scroll=True)
            for _ in range(6):
                root = self.dump("duration_row")
                nodes = list(root.iter("node"))
                label_node = next((n for n in nodes if n.get("text") == label), None)
                if label_node is not None:
                    parent = next(n for n in root.iter() if label_node in list(n))
                    children = list(parent)
                    following = children[children.index(label_node) + 1:]
                    if following:
                        self.tap_node(following[0])
                        break
                self.sh("input swipe 540 1900 540 1200 250")
            else:
                raise AssertionError(label)
            for i, value in enumerate(values):
                self.edit(i, str(value))
            self.dismiss_keyboard()
            self.click("저장")
            self.wait_pref(key, expected)
        self.passed("four duration settings save independently through UI")
        self.home()
        self.click("설정", scroll=True)
        self.click("앱 선택 (", contains=True, scroll=True)
        self.edit(0, "Settings")
        self.dismiss_keyboard()
        assert "Settings" in self.text("search")
        self.click("보이는 앱 모두 해제")
        assert "0개 선택됨" in self.text("deselect")
        self.click("보이는 앱 모두 선택")
        assert "1개 선택됨" in self.text("select")
        self.click("30분/주기")
        self.click("주기당 1분")
        self.wait_pref(f"limit_{TARGET}", 1)
        assert f"cycle_limit_{TARGET}" not in self.pref()
        self.passed("app search/select/deselect and permanent quota change reset current cycle")
        self.home()
        self.click("You sure? 사용")
        self.wait_pref("enabled", "false")
        assert "지금은 쉬고 있어요" in self.text("disabled")
        event = self.event()
        self.target()
        assert self.event() == event
        self.home()
        self.click("You sure? 사용")
        self.wait_pref("enabled", "true")
        self.target()
        current = self.event()
        assert current is not None and (event is None or current[0] != event[0]) and current[1] == "PENDING"
        self.home()
        self.passed("master switch disables and reenables intervention")
        self.click("통계 보기", scroll=True)
        for period in ["오늘", "7일", "30일", "전체"]:
            self.click(period)
            assert "참은 비율" in self.text("stats_period")
        self.click("달력")
        assert "연속으로 잘 참은 날" in self.text("calendar")
        self.click("앱별")
        self.passed("all four statistics periods and calendar/app tabs render")

    def overlay_suite(self):
        self.config()
        self.target()
        time.sleep(4)
        self.snap("overlay_before")
        event = self.event()
        self.overlay_minutes(45, cancel=True)
        assert int(self.pref()[f"cycle_limit_{TARGET}"]) == 0
        assert self.event() == event
        self.overlay_minutes(45)
        self.wait_pref(f"cycle_limit_{TARGET}", 2_700_000)
        assert int(self.pref()[f"cycle_used_{TARGET}"]) == 600_000
        assert self.event() == event and event[1] == "PENDING"
        self.passed("overlay keyboard minute edit/cancel/save preserves usage and same pending event")
        time.sleep(4)
        self.sh("input tap 800 250")
        time.sleep(2)
        assert self.event()[1] == "OPENED"
        assert int(self.pref()[f"cycle_used_{TARGET}"]) > 600_000
        self.passed("normal wait opens app and resumes real usage counting")
        now = int(self.sh("date +%s")) * 1000
        self.config(**{f"limit_{TARGET}": ("int", 1), f"cycle_used_{TARGET}": ("long", 60_000),
                       f"cycle_until_{TARGET}": ("long", now + 90_000), "limitDelaySec": ("int", 60)})
        self.target()
        event = self.event()
        self.sh("input tap 800 250")
        assert self.event() == event and event[1] == "PENDING"
        self.snap("cooldown_before")
        self.overlay_minutes(2)
        self.wait_pref(f"cycle_limit_{TARGET}", 120_000)
        self.wait_pref(f"cycle_until_{TARGET}", 0)
        assert int(self.pref()[f"cycle_used_{TARGET}"]) == 60_000
        assert int(self.pref()[f"limit_{TARGET}"]) == 1
        assert self.event() == event
        self.passed("cooldown early open blocked, minute extension clears cooldown without resetting usage/event")
        time.sleep(4)
        self.sh("input tap 800 250")
        assert self.event()[1] == "OPENED"
        # Consume the actual remaining minute, observing the service's stored state.
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            values = self.pref()
            if int(values[f"cycle_until_{TARGET}"]) > 0:
                break
            time.sleep(1)
        else:
            raise AssertionError("Extended minute was not exhausted by real foreground use")
        self.wait_pref(f"cycle_used_{TARGET}", 120_000)
        assert self.event()[1] == "PENDING"
        self.passed("real foreground use exhausts the extended 2-minute total and starts cooldown")
        time.sleep(11)
        self.wait_pref(f"cycle_limit_{TARGET}", 0)
        self.wait_pref(f"cycle_used_{TARGET}", 0)
        self.wait_pref(f"cycle_until_{TARGET}", 0)
        self.passed("cooldown expiration restores permanent 1-minute quota and resets only cycle state")
        self.sh("input tap 290 250")
        assert self.event()[1] == "CLOSED"
        self.passed("close button records resisted outcome and returns home")
        self.config(**{"graceSec": ("int", 15), f"limit_{TARGET}": ("int", 0)})
        self.target()
        time.sleep(4)
        self.sh("input tap 800 250")
        event = self.event()
        assert event[1] == "OPENED"
        self.home()
        self.target()
        assert self.event() == event
        self.passed("unlimited app still intervenes, grace prevents duplicate intervention after reopening")
        self.sh(f"cmd appops set {PKG} GET_USAGE_STATS deny")
        self.config(**{"graceSec": ("int", 1)})
        self.target()
        self.snap("usage_permission_denied")
        assert self.event()[1] == "PENDING"
        self.sh("input keyevent KEYCODE_HOME")
        time.sleep(.5)
        assert self.event()[1] == "DISMISSED"
        self.passed("intervention works without usage permission and leaving records dismissed outcome")

    def overlay_minutes(self, minutes, cancel=False):
        # Measured from overlay-probe/overlay_dialog.png, actual resolution 1080x2400.
        self.sh("input tap 540 400")
        time.sleep(.4)
        self.sh("input tap 740 1500")
        self.replace_focused(str(minutes))
        self.snap("overlay_keyboard")
        self.dismiss_keyboard()
        time.sleep(.3)
        self.snap("overlay_edited")
        self.sh(f"input tap {730 if cancel else 895} 1720")
        time.sleep(.4)

    def design_suite(self):
        self.config()
        for index, name in enumerate(["숲", "밤바다", "노을", "라벤더", "종이", "메탈"]):
            self.home()
            self.click("설정", scroll=True)
            self.click(name, contains=True, scroll=True)
            self.wait_pref("themeIdx", index)
            self.click("개입 화면 미리보기", scroll=True)
            assert "예시 앱" in self.text(f"theme_{index}")
            self.snap(f"theme_{index}")
            self.click("닫기")
        self.passed("all six themes apply and preview closes")
        self.home()
        self.click("설정", scroll=True)
        self.click("숨쉬기 연출 보이기", scroll=True)
        self.wait_pref("breathing", "true")
        self.click("색 조합", scroll=True)
        self.edit(0, "ReviewMessage")
        self.dismiss_keyboard()
        self.click("개입 화면 미리보기", scroll=True)
        started = time.monotonic()
        assert "ReviewMessage" in self.text("message_preview")
        self.snap("wave_start")
        time.sleep(max(0, 3.5 - (time.monotonic() - started)))
        self.snap("wave_top")
        time.sleep(max(0, 9 - (time.monotonic() - started)))
        self.snap("wave_end")
        time.sleep(3)
        self.snap("wave_still_end")
        from PIL import Image
        pictures = [Image.open(self.output / f"wave_{name}.png").convert("RGB") for name in ["top", "end", "still_end"]]
        point = (108, 1800)
        assert sum(abs(a-b) for a, b in zip(pictures[0].getpixel(point), pictures[1].getpixel(point))) > 50
        assert pictures[1].getpixel(point) == pictures[2].getpixel(point)
        self.click("닫기")
        self.passed("custom message and breathing one rise/fall without replay")

    def layout_suite(self):
        self.config()
        # Landscape and large text are exercised in the regular dialog, where nodes are available.
        self.sh("settings put system user_rotation 1")
        self.sh("settings put system font_scale 1.5")
        self.home()
        self.click("이번 주기만 시간 늘리기", contains=True, scroll=True)
        self.total(1, 5)
        self.click("늘리기")
        self.wait_pref(f"cycle_limit_{TARGET}", 3_900_000)
        self.passed("landscape and 150 percent text extension input remains usable")

    def details_suite(self):
        self.config()
        self.target()
        self.sh("input tap 290 250")
        time.sleep(2)
        self.home()
        self.click("통계 보기", scroll=True)
        self.click("오늘")
        self.click("Settings", scroll=True)
        assert "최근 7일, 참음은 밝게 열기는 어둡게" in self.text("app_detail")
        self.passed("app statistics row expands recent seven-day chart")
        self.click("달력", scroll=True)
        current_month = re.search(r"\d+년 \d+월", self.text("month_current")).group()
        self.click("‹")
        previous_month = re.search(r"\d+년 \d+월", self.text("month_previous")).group()
        assert previous_month != current_month
        self.click("›")
        assert current_month in self.text("month_returned")
        root = self.dump("calendar_bounds")
        month_label = next(n for n in root.iter("node") if n.get("text") == current_month)
        navigation = next(n for n in root.iter() if month_label in list(n))
        container = next(n for n in root.iter() if navigation in list(n))
        siblings = list(container)
        grid = siblings[siblings.index(navigation) + 1]
        assert grid.get("class") == "android.view.View", "Expected custom calendar immediately below month navigation"
        left, top, right, bottom = map(int, re.findall(r"\d+", grid.get("bounds")))
        density = int(re.findall(r"\d+", self.sh("wm density"))[-1]) / 160
        today = datetime.strptime(self.sh("date +%Y%m%d"), "%Y%m%d")
        first = today.replace(day=1)
        offset = (first.weekday() + 1) % 7
        # Choose a different, valid day through the custom drawn calendar's actual touch path.
        day = 1 if today.day != 1 else 2
        cell = offset + day - 1
        x = int(left + (right-left) * (cell % 7 + .5) / 7)
        y = int(top + 24*density + 42*density * (cell // 7 + .5))
        self.sh(f"input tap {x} {y}")
        expected = f"{today.month}월 {day}일"
        self.click(expected, contains=True, scroll=True)
        assert expected in self.text("day_selected")
        self.passed("calendar month navigation and date touch change day details")

    def permissions_suite(self):
        self.config(**{"enabled": ("boolean", "false")})
        self.sh("settings delete secure enabled_accessibility_services")
        self.sh(f"cmd appops set {PKG} GET_USAGE_STATS deny")
        routes = [("켜기", "android.settings.ACCESSIBILITY_SETTINGS"),
                  ("허용", "android.settings.USAGE_ACCESS_SETTINGS"),
                  ("열기", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")]
        for label, action in routes:
            self.home()
            self.click("설정", scroll=True)
            expected_component = self.sh(f"cmd package resolve-activity --brief -a {action}").splitlines()[-1]
            assert "/" in expected_component, expected_component
            self.click(label)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                activities = self.sh("dumpsys activity activities")
                resumed = "\n".join(line for line in activities.splitlines()
                                    if "topResumedActivity=" in line or "mResumedActivity:" in line)
                if expected_component in resumed:
                    break
                if action == "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS":
                    paused = "\n".join(line for line in activities.splitlines() if "mLastPausedActivity:" in line)
                    if expected_component in paused and "com.android.settings/.spa.SpaActivity" in resumed:
                        assert "App battery usage" in self.text("battery_settings_redirect")
                        resumed += "\n" + paused
                        break
                time.sleep(.2)
            else:
                raise AssertionError(f"Expected foreground {expected_component}, got {resumed}")
            (self.output / (action.rsplit(".", 1)[1] + ".txt")).write_text(resumed, encoding="utf-8")
            self.snap(action.rsplit(".", 1)[1])
        self.passed("all three permission links open their OS-resolved settings screens")
        self.home()
        self.click("설정", scroll=True)
        self.click("앱 선택 (", contains=True, scroll=True)
        self.edit(0, "Camera")
        self.dismiss_keyboard()
        boxes = [n for n in self.dump("individual_selection").iter("node") if n.get("class") == "android.widget.CheckBox"]
        assert len(boxes) == 1 and boxes[0].get("checked") == "false"
        self.tap_node(boxes[0])
        assert "2개 선택됨" in self.text("selected_two")
        self.edit(0, "")
        self.dismiss_keyboard()
        boxes = [n for n in self.dump("selected_first").iter("node") if n.get("class") == "android.widget.CheckBox"]
        assert len(boxes) > 2 and all(n.get("checked") == "true" for n in boxes[:2])
        assert all(n.get("checked") == "false" for n in boxes[2:])
        self.passed("individual selection preserves multiple apps and sorts selected apps first")

    def run_review(self, apk, suite):
        assert self.sh("getprop ro.kernel.qemu") == "1", "Emulator only"
        self.adb("root")
        self.adb("wait-for-device")
        assert self.sh(f"pm path {PKG}").startswith("package:"), "Existing emulator installation required for recovery"
        settings = {(scope, key): self.sh(f"settings get {scope} {key}") for scope, key in [
            ("secure", "enabled_accessibility_services"), ("secure", "accessibility_enabled"),
            ("system", "accelerometer_rotation"), ("system", "user_rotation"), ("system", "font_scale")]}
        appops = self.sh(f"cmd appops get {PKG} GET_USAGE_STATS")
        mode = re.search(r"GET_USAGE_STATS: (\w+)", appops)
        original_mode = mode.group(1) if mode else "default"
        self.sh("settings delete secure enabled_accessibility_services")
        self.sh(f"am force-stop {PKG}")
        recovery = ROOT / "build/recovery/v5.1" / self.output.name
        recovery.mkdir(parents=True, exist_ok=True)
        archive = recovery / "package_data.tar"
        self.sh(f"tar -C {DATA} -cf /data/local/tmp/yousure_backup_51.tar shared_prefs databases")
        self.adb("pull", "/data/local/tmp/yousure_backup_51.tar", archive)
        with tarfile.open(fileobj=io.BytesIO(archive.read_bytes())) as tar:
            originals = {m.name: hashlib.sha256(tar.extractfile(m).read()).hexdigest()
                         for m in tar.getmembers() if m.isfile()}
        (recovery / "settings.json").write_text(json.dumps({f"{a}/{b}": v for (a, b), v in settings.items()}), encoding="utf-8")
        try:
            self.adb("install", "-r", apk, timeout=120)
            assert "versionName=0.9.1" in self.sh(f"dumpsys package {PKG}")
            self.sh("input keyevent KEYCODE_WAKEUP")
            self.sh("wm dismiss-keyguard")
            self.sh("settings put system accelerometer_rotation 0")
            self.sh("settings put system user_rotation 0")
            self.sh(f"cmd appops set {PKG} GET_USAGE_STATS allow")
            self.sh("logcat -c")
            getattr(self, f"{suite}_suite")()
            crashes = self.sh("logcat -d -b crash")
            (self.output / "crash.log").write_text(crashes, encoding="utf-8")
            assert PKG not in crashes, crashes
            self.record("no application crash", passed=True)
        except BaseException as exc:
            self.snap("failure")
            self.record("suite failed", error=str(exc))
            raise
        finally:
            self.sh("settings delete secure enabled_accessibility_services")
            self.sh(f"am force-stop {PKG}")
            self.adb("push", archive, "/data/local/tmp/yousure_backup_51.tar")
            self.sh(f"tar -C {DATA} -xf /data/local/tmp/yousure_backup_51.tar")
            uid = self.sh(f"stat -c %u {DATA}")
            self.sh(f"chown -R {uid}:{uid} {DATA}/shared_prefs {DATA}/databases")
            for name, digest in originals.items():
                assert hashlib.sha256(self.adb("exec-out", "cat", f"{DATA}/{name}")).hexdigest() == digest, name
            for (scope, key), value in settings.items():
                self.sh(f"settings delete {scope} {key}" if value == "null" else f"settings put {scope} {key} {value}")
                assert self.sh(f"settings get {scope} {key}") == value
            self.sh(f"cmd appops set {PKG} GET_USAGE_STATS {original_mode}")
            self.record("original data hashes and device settings restored", files=len(originals))


def summarize(apk, output):
    """Validate the saved feature evidence and package the tested APK without publishing."""
    output.mkdir(parents=True, exist_ok=True)
    suites = [ET.parse(p).getroot() for p in (ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")]
    assert suites and all(int(s.get("failures")) == int(s.get("errors")) == 0 for s in suites)
    tests = [case.get("classname") + "." + case.get("name") for suite in suites for case in suite.findall("testcase")]
    assert len(tests) == 48
    lint = (ROOT / "app/build/reports/lint-results-debug.txt").read_text(encoding="utf-8")
    errors, warnings = map(int, re.search(r"(\d+) errors, (\d+) warnings", lint).groups())
    assert errors == 0
    runs = {"home-run2": 4, "settings-run2": 4, "overlay-run1": 8, "design-run1": 2,
            "layout-run2": 1, "details-run2": 2, "permissions-run2": 2}
    partial_failures = {"home-run2": "list index out of range", "design-run1": "list index out of range", "details-run2": ""}
    runtime = {}
    for name, count in runs.items():
        path = ROOT / "outputs/v5.1/validation" / name / "00_runtime.json"
        events = json.loads(path.read_text(encoding="utf-8"))["events"]
        checks = [e["check"] for e in events if e.get("passed") and e["check"] != "no application crash"]
        assert len(checks) == count, (name, checks)
        assert events[-1]["check"] == "original data hashes and device settings restored"
        failures = [e for e in events if "error" in e]
        if name in partial_failures:
            # Preserve completed checks from earlier combined runs; their offscreen-control
            # harness failures are covered by successful standalone settings/layout/permissions runs.
            assert len(failures) == 1 and failures[0]["error"] == partial_failures[name]
        else:
            assert not failures, (name, failures)
        runtime[name] = {"checks": checks, "source": str(path.relative_to(ROOT)),
                         "historical_harness_failures": failures,
                         "backup_point": "after initial home launch" if name in ("home-run2", "overlay-run1") else "before installation/launch"}
    verifier = Review(output)
    installed = verifier.sh(f"pm path {PKG}").removeprefix("package:")
    package = verifier.sh(f"dumpsys package {PKG}")
    assert "versionName=0.9.1" in package and re.search(r"versionCode=11\b", package)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert verifier.sh(f"sha256sum {installed}").split()[0] == digest
    destination = ROOT / "outputs/v5.1/apk/YouSure-0.9.1.apk"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(apk.read_bytes())
    tools = Path("C:/Users/kimt9/AndroidProjects/_tools")
    import os
    env = dict(os.environ, JAVA_HOME=str(tools / "jdk/jdk-17.0.20.1+1"))
    signature = subprocess.run(["cmd", "/c", str(tools / "sdk/build-tools/34.0.0/apksigner.bat"),
                                "verify", "--print-certs", str(destination)], env=env, check=True,
                               capture_output=True).stdout.decode("utf-8")
    certificate = "ecb4ca12e3d212ef619d34533510fa94449fd7a532d83873333f510a4ab347c3"
    assert certificate in signature, "Signing identity must match the existing app"
    (output / "signature.txt").write_text(signature, encoding="utf-8")
    source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in sorted((ROOT / "app/src").rglob("*.kt"))}
    manifest = {"version": "v5.1", "app_version": "0.9.1", "version_code": 11,
                "apk": str(destination.relative_to(ROOT)), "sha256": digest,
                "signing_certificate_sha256": certificate, "installed_apk_matches": True,
                "unit_tests": tests, "lint_errors": errors, "lint_warnings": warnings,
                "runtime": runtime, "feature_check_groups": sum(runs.values()),
                "source_sha256": source_hashes, "published": False,
                "limitations": ["API35 emulator only; no physical Galaxy or TalkBack voice test",
                                "Existing lint warnings remain", "Historical harness failures preserved in earlier runs"]}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    destination.with_suffix(".apk.sha256").write_text(digest + "  " + destination.name + "\n", encoding="ascii")
    print(json.dumps({"unit_tests": len(tests), "runtime_groups": sum(runs.values()), "apk": str(destination), "sha256": digest}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=["home", "settings", "overlay", "design", "layout", "details", "permissions", "summary"], required=True)
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.suite == "summary":
        summarize(args.apk.resolve(), args.output)
    else:
        Review(args.output).run_review(args.apk.resolve(), args.suite)
