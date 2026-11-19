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
    # Top and bottom thirds separately, so a grad on the wrong side would show up.
    top = np.abs(a[:16].mean(axis=(0, 1)) - b[:16].mean(axis=(0, 1))).max()
    bottom = np.abs(a[-16:].mean(axis=(0, 1)) - b[-16:].mean(axis=(0, 1))).max()
    print(f"{stock:14s} top-third delta {top:.1f}  bottom-third delta {bottom:.1f}  top/bottom brightness view {a[:16].mean():.0f}/{a[-16:].mean():.0f} photo {b[:16].mean():.0f}/{b[-16:].mean():.0f}")
    if top > 25 or bottom > 25:
        problems += 1
    print(
        f"{stock:14s} frame {right - left}x{bottom - top} photo {photo.width}x{photo.height} "
        f"view {np.round(a.mean(axis=(0, 1)), 1).tolist()} photo {np.round(b.mean(axis=(0, 1)), 1).tolist()} "
        f"delta {np.round(delta, 1).tolist()}"
    )


def jpeg_problems(path):
    data = open(path, "rb").read()
    found = []
    position = 2
    while position + 4 <= len(data) and data[position] == 0xFF:
        marker = data[position + 1]
        if marker == 0xDA:
            break
        length = int.from_bytes(data[position + 2:position + 4], "big")
        payload = data[position + 4:position + 2 + length]
        if marker == 0xE1 or marker == 0xFE or b"Exif" in payload or b"GPS" in payload or b"xmp" in payload.lower():
            found.append(hex(marker))
        position += 2 + length
    return found


def mp4_problems(path):
    data = open(path, "rb").read()
    found = []

    def walk(start, end):
        position = start
        while position + 8 <= end:
            size = int.from_bytes(data[position:position + 4], "big")
            kind = data[position + 4:position + 8].decode("latin1")
            head = 8
            if size == 1:
                size = int.from_bytes(data[position + 8:position + 16], "big")
                head = 16
            elif size == 0:
                size = end - position
            if size < head:
                return
            body = position + head
            if kind in ("moov", "trak", "mdia"):
                walk(body, position + size)
            elif kind in ("udta", "meta"):
                found.append(kind)
            elif kind in ("mvhd", "tkhd", "mdhd"):
                length = 16 if data[body] == 1 else 8
                if any(data[body + 4:body + 4 + length]):
                    found.append(kind + " date")
            position += size

    walk(0, len(data))
    return found


print("\nprivacy of saved files")
for path in sorted(glob.glob(os.path.join(out, "photo-*.jpg"))):
    issues = jpeg_problems(path)
    print(os.path.basename(path), "clean" if not issues else f"METADATA {issues}")
    problems += len(issues)
clip = os.path.join(out, "clip.mp4")
if os.path.exists(clip):
    issues = mp4_problems(clip)
    print("clip.mp4", "clean" if not issues else f"METADATA {issues}")
    problems += len(issues)

print("\ndarkness on a phone set to light mode (mean brightness, 0-255)")
checks = [("00-first-launch", 0.0, 0.07, 0.15, 0.85, 45), ("10-film-cabinet", 0.62, 0.95, 0.05, 0.95, 80), ("35-delete-dialog", 0.42, 0.56, 0.2, 0.8, 45)]
for name, y0, y1, x0, x1, limit in checks:
    path = os.path.join(out, name + ".png")
    if not os.path.exists(path):
        print(name, "missing")
        problems += 1
        continue
    pixels = np.asarray(Image.open(path).convert("L"), dtype=np.float32)
    h, w = pixels.shape
    value = float(pixels[int(h * y0):int(h * y1), int(w * x0):int(w * x1)].mean())
    print(f"{name:18s} {value:6.1f} (limit {limit})")
    if value > limit:
        problems += 1

sys.exit(1 if problems else 0)
