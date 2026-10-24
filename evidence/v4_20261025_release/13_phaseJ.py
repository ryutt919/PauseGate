"""j. 숨쉬기(물결) 한 사이클 동안 패널 가려짐 정도. 일반 개입, 숲 테마, 2행 패널."""
import sys, time, json
import numpy as np
from PIL import Image
sys.path.insert(0, '.'); sys.stdout.reconfigure(encoding='utf-8')
import importlib; L = importlib.import_module('02_lib')
L.TARGET = "com.android.settings"
L.set_prefs({"enabled": ("boolean", "true"), "breathing": ("boolean", "true"), "delaySec": ("int", "3"), "graceSec": ("int", "5"),
             "themeIdx": ("int", "0"), "graceUntil_" + L.TARGET: ("long", "0")},
            remove=["limit_" + L.TARGET, "cycle_used_" + L.TARGET, "cycle_until_" + L.TARGET])
L.wake(); L.reconnect(); L.sh("input keyevent KEYCODE_HOME"); time.sleep(1)
L.target(); time.sleep(1.5)
rows = []
for i in range(22):
    p = L.snap(f"J_숨쉬기_{i+1:02d}")
    im = np.array(Image.open(p).convert('RGB')).astype(int)
    col = im[:, 540, :]
    water = np.where((col[:, 1] > 150) & (col[:, 0] < 120))[0]
    top = int(water.min()) if len(water) else None
    rows.append({"file": p.name, "kst": L.shots[-1]["kst"], "water_top_y": top})
    print(rows[-1], flush=True)
    time.sleep(0.2)
json.dump(rows, open("13_phaseJ_물결위치.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
L.sh("input keyevent KEYCODE_HOME")
