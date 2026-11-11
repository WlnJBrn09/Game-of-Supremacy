"""Tiles tour screenshots into contact sheets for quick review: contact_sheet.py DIR OUT_PREFIX [per_sheet]."""
import glob, os, sys
from PIL import Image, ImageDraw

files = sorted(glob.glob(os.path.join(sys.argv[1], "*.png")))
per = int(sys.argv[3]) if len(sys.argv) > 3 else 6
for n, start in enumerate(range(0, len(files), per)):
    ims = [Image.open(f).convert("RGB") for f in files[start:start + per]]
    w = 900
    hs = [int(i.height * w / i.width) for i in ims]
    rows = (len(ims) + 1) // 2
    sheet = Image.new("RGB", (w * 2 + 10, sum(max(hs[k:k + 2]) + 30 for k in range(0, len(ims), 2))), (40, 40, 40))
    y = 0
    draw = ImageDraw.Draw(sheet)
    for k in range(0, len(ims), 2):
        for j in range(2):
            if k + j < len(ims):
                sheet.paste(ims[k + j].resize((w, hs[k + j]), Image.LANCZOS), (j * (w + 10), y + 26))
                draw.text((j * (w + 10) + 4, y + 6), os.path.basename(files[start + k + j]), fill=(255, 255, 255))
        y += max(hs[k:k + 2]) + 30
    sheet.save(f"{sys.argv[2]}-{n}.png")
    print(f"{sys.argv[2]}-{n}.png")
