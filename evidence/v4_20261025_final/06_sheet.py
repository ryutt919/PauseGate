"""판독용: 해당 패턴 스크린샷의 일부 영역을 잘라 한 장에 이어붙인다. 사용: python 06_sheet.py 출력.png 'glob' x0 y0 x1 y1 [열 수]"""
import sys, glob, json
from PIL import Image, ImageDraw, ImageFont
out, pat, x0, y0, x1, y1 = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:7])
cols = int(sys.argv[7]) if len(sys.argv) > 7 else 2
shots = {}
try:
    for f in glob.glob('*.json'):
        pass
except Exception: pass
fs = sorted(glob.glob(pat))
font = ImageFont.truetype("C:/Windows/Fonts/malgun.ttf", 28)
w, h = x1 - x0, y1 - y0
rows = (len(fs) + cols - 1) // cols
sheet = Image.new("RGB", (w * cols, (h + 40) * rows), "black")
d = ImageDraw.Draw(sheet)
for i, f in enumerate(fs):
    im = Image.open(f).convert("RGB").crop((x0, y0, x1, y1))
    cx, cy = (i % cols) * w, (i // cols) * (h + 40)
    d.text((cx + 6, cy + 4), f, fill="yellow", font=font)
    sheet.paste(im, (cx, cy + 40))
sheet.save(out)
print(out, sheet.size)
