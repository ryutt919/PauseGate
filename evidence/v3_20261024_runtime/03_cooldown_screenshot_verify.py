"""쿨타임 줄 실행 검증, 2차 시도. 개입 화면은 TYPE_ACCESSIBILITY_OVERLAY 라 uiautomator dump 에 나오지 않으므로
(시도1 참고) 스크린샷을 시각과 함께 연속 촬영하고, 쿨타임 줄/버튼 영역을 잘라 띠 이미지로 만들어 판독한다.
앱 홈(MainActivity)은 일반 창이라 uiautomator dump 의 text 로 확인한다. 소스 코드는 수정하지 않는다.
"""
import importlib.util
import json
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("base", Path(sys.argv[1]))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
base.OUT = OUT
FONT = ImageFont.truetype("C:/Windows/Fonts/malgun.ttf", 34)
shots = []


def emu_now():
    return float(base.sh("date +%s.%N"))


def shot(tag, until_ms, crop=True):
    """스크린샷 저장. 촬영 직전 에뮬레이터 시계로 기대 남은 초를 함께 기록한다."""
    exp = until_ms / 1000 - emu_now()
    path = OUT / f"{len(shots) + 1:02d}_{tag}.png"
    path.write_bytes(base.adb("exec-out", "screencap", "-p"))
    shots.append({"file": path.name, "tag": tag, "kst": base.now_kst(), "expected_remaining_s": round(exp, 1)})
    print(json.dumps(shots[-1], ensure_ascii=False), flush=True)
    return path


def strip(prefix, name, regions_y=((150, 330), (880, 1080))):
    """해당 접두 스크린샷들의 버튼 영역과 문구 영역을 세로로 이어붙인 판독용 이미지를 만든다."""
    rows = [s for s in shots if s["tag"].startswith(prefix)]
    h = sum(b - a for a, b in regions_y) // 2 + 70
    sheet = Image.new("RGB", (1080, h * len(rows)), "black")
    d = ImageDraw.Draw(sheet)
    for i, s in enumerate(rows):
        im = Image.open(OUT / s["file"]).convert("RGB")
        y = i * h
        d.text((10, y + 4), f'{s["file"]}  {s["kst"]}  기대 {s["expected_remaining_s"]}s', fill="yellow", font=FONT)
        yy = y + 60
        for a, b in regions_y:
            part = im.crop((0, a, 1080, b)).resize((1080, (b - a) // 2)) if False else im.crop((0, a, 1080, b))
            part = part.resize((540, (b - a) // 2))
            sheet.paste(part, (0 if a < 500 else 540, y + 60))
    sheet.save(OUT / name)


def main():
    b = base
    assert b.sh("getprop ro.kernel.qemu") == "1" and "1080x2400" in b.sh("wm size")
    b.sh(f"run-as {b.PKG} id")
    b.configure()
    b.target()
    b.log("대상 앱 실사용 시작, 쿨타임 진입 대기")
    while True:
        used, until = b.state()
        if until > b.emu_ms():
            break
        time.sleep(1)
    t_cd = time.monotonic()
    b.log("쿨타임 시작 감지", used_ms=used, until_ms=until, remaining_s=(until / 1000 - emu_now()))

    for i in range(3):               # a: 개입 화면 직후
        shot(f"a_개입직후_{i + 1}", until)
        time.sleep(1.4)
    while time.monotonic() - t_cd < 10:
        time.sleep(0.3)
    for i in range(3):               # b: 6초 대기 종료 후
        shot(f"b_대기완료후_{i + 1}", until)
        time.sleep(1.4)

    orig_accel = b.sh("settings get system accelerometer_rotation")
    try:                              # e: 가로 회전
        b.sh("settings put system accelerometer_rotation 0")
        b.sh("settings put system user_rotation 1")
        time.sleep(3)
        disp = b.sh("dumpsys window displays | grep -m1 -o 'cur=[0-9x]*'")
        shot("e_가로_1", until)
        time.sleep(2)
        shot("e_가로_2", until)
        b.log("가로 회전", display=disp)
    finally:
        b.sh("settings put system user_rotation 0")
        b.sh("settings put system accelerometer_rotation " + orig_accel)
        time.sleep(3)
    b.log("세로 복귀", display=b.sh("dumpsys window displays | grep -m1 -o 'cur=[0-9x]*'"))
    shot("e_세로복귀", until)

    b.adb("shell", f"am start -n {b.PKG}/.MainActivity")   # c: 앱 홈, uiautomator dump text
    time.sleep(2)
    home = []
    for i in range(3):
        d = b.dump(f"c_앱홈_dump{i + 1}")
        d["expected_remaining_s"] = round(until / 1000 - emu_now(), 1)
        home.append(d)
        b.log("c 앱 홈", kst=d["kst"], lines=d["cooldown_lines"], expected_remaining_s=d["expected_remaining_s"])
        if i < 2:
            time.sleep(3)
    shot("c_앱홈", until)

    b.target()                        # d: 재진입 후 만료까지
    end = t_cd + (until / 1000 - (emu_now() - (time.monotonic() - t_cd))) if False else None
    while True:
        rem = until / 1000 - emu_now()
        if rem < -12:
            break
        shot("d_만료관측", until)
        time.sleep(1.0 if -6 < rem < 6 else 5.0)
    st = b.state()
    b.log("d 만료 후 상태", cycle_used_cycle_until=st)
    for pre, name in (("a_", "A_개입직후_판독띠.png"), ("b_", "B_대기완료후_판독띠.png"),
                      ("d_", "D_만료관측_판독띠.png")):
        strip(pre, name)
    (OUT / "03_스크린샷_목록.json").write_text(json.dumps({"shots": shots, "home": home}, ensure_ascii=False, indent=2),
                                          encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
