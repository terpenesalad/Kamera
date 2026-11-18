#!/usr/bin/env python3
"""Prints text diagnostics for the emulator run: screen maps and viewfinder-versus-photo colour."""
import glob
import os
import sys

import numpy as np
from PIL import Image

out, width, height, density = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
RAMP = " .:-=+*#%@"


def luma_map(path, columns=54):
    image = Image.open(path).convert("L")
    rows = int(columns * image.height / image.width * 0.5)
    cells = np.asarray(image.resize((columns, rows), Image.BOX), dtype=np.float32) / 256
    return "\n".join("".join(RAMP[int(v * len(RAMP))] for v in row) for row in cells)


def frame_box(shot):
    """Finds the picture area of a viewfinder screenshot: the largest bright band of rows."""
    dp = density / 160
    top, bottom = int(60 * dp + 40), int(height - 360 * dp)
    area = shot[top:bottom]
    active = area.mean(axis=2) > 12
    rows = np.where(active.mean(axis=1) > 0.5)[0]
    cols = np.where(active.mean(axis=0) > 0.5)[0]
    if len(rows) < 50 or len(cols) < 50:
        return None
    return cols[0], rows[0] + top, cols[-1] + 1, rows[-1] + top + 1


problems = 0
for name in ["00-first-launch", "10-film-cabinet", "20-video-mode", "30-gallery", "31-viewer", "32-darkroom", "33-after-save"]:
    path = os.path.join(out, name + ".png")
    if os.path.exists(path):
        print(f"--- {name}\n{luma_map(path)}")

video = os.path.join(out, "20-video-mode.png")
if os.path.exists(video):
    dp = density / 160
    pixel = np.asarray(Image.open(video).convert("RGB"))[int(height - 78 * dp), width // 2]
    print("shutter colour in video mode:", pixel.tolist())

print("\nviewfinder against final photo (mean sRGB, 0-255)")
for photo_path in sorted(glob.glob(os.path.join(out, "photo-*.jpg"))):
    stock = os.path.basename(photo_path)[6:-4]
    shot_path = os.path.join(out, f"vf-{stock}.png")
    if not os.path.exists(shot_path):
        continue
    shot = np.asarray(Image.open(shot_path).convert("RGB"), dtype=np.float32)
    box = frame_box(shot)
    if box is None:
        print(f"{stock}: viewfinder looks black")
        problems += 1
        continue
    left, top, right, bottom = box
    view = shot[top:bottom, left:right]
    photo = Image.open(photo_path).convert("RGB")
    if photo.width > photo.height:
        photo = photo.rotate(90, expand=True)
    small = (32, 48)
    a = np.asarray(Image.fromarray(view.astype(np.uint8)).resize(small, Image.BOX), dtype=np.float32)
    b = np.asarray(photo.resize(small, Image.BOX), dtype=np.float32)
    delta = np.abs(a.mean(axis=(0, 1)) - b.mean(axis=(0, 1)))
    print(
        f"{stock:14s} frame {right - left}x{bottom - top} photo {photo.width}x{photo.height} "
        f"view {np.round(a.mean(axis=(0, 1)), 1).tolist()} photo {np.round(b.mean(axis=(0, 1)), 1).tolist()} "
        f"delta {np.round(delta, 1).tolist()}"
    )
sys.exit(1 if problems else 0)
