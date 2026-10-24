"""개입 화면 스크린샷에서 요약 패널 외곽(테두리) 상자와 문구 위치를 측정한다. 사용: python 04_measure.py 파일.png [y0 y1]"""
import sys, json
import numpy as np
from PIL import Image
DP = 420 / 160

def measure(path, y0=700, y1=1500):
    im = np.array(Image.open(path).convert("RGB")).astype(int)
    h, w, _ = im.shape
    bg = im[y0, 3]  # 왼쪽 가장자리 배경색
    reg = im[y0:y1, :, :]
    diff = np.abs(reg - bg).sum(axis=2) > 12
    # 행별로 비배경 픽셀 수 -> 연속 구간(텍스트/패널) 찾기
    rows = diff.sum(axis=1)
    bands, start = [], None
    for i, v in enumerate(rows):
        if v > 0 and start is None: start = i
        if v == 0 and start is not None:
            bands.append((start + y0, i - 1 + y0)); start = None
    if start is not None: bands.append((start + y0, y1 - 1))
    out = []
    for a, b in bands:
        cols = np.where(diff[a - y0:b - y0 + 1].any(axis=0))[0]
        out.append({"y": [a, b], "x": [int(cols.min()), int(cols.max())],
                    "w_px": int(cols.max() - cols.min() + 1), "h_px": b - a + 1,
                    "w_dp": round((cols.max() - cols.min() + 1) / DP, 1), "h_dp": round((b - a + 1) / DP, 1)})
    return {"file": str(path), "bg": bg.tolist(), "bands": out}

if __name__ == "__main__":
    a = sys.argv[1]; y0 = int(sys.argv[2]) if len(sys.argv) > 2 else 700; y1 = int(sys.argv[3]) if len(sys.argv) > 3 else 1500
    print(json.dumps(measure(a, y0, y1), ensure_ascii=False, indent=1))
