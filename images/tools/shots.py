"""Prepares screenshots for the CurseForge gallery from captured frames: numbered, named for what they
show, and under 2 MB each (the limit of CurseForge for an uploaded image).

    python shots.py images/screenshots "speech-bubble=run-capture/screenshots/talk_en_0060.png" "menu-home=.../m_en_home.png" ...
    python shots.py images/screenshots --crop 0,0,1600,900 "name=frame.png"

A source can also be a frame of a banner, for a mod that was not recorded: "name=images/blocks.gif#40".

Files are written as 01-<name>.png, 02-<name>.png... in the order given. A picture that weighs 2 MB or
more as PNG is saved as JPEG instead, at the highest quality that fits. Existing numbers in the
folder are continued, not overwritten.

Author: vyrriox
"""
import argparse
import io
import os
import re
import sys

from PIL import Image

LIMIT = 2_000_000


def save(img, path_without_ext):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    if buf.tell() < LIMIT:
        with open(path_without_ext + ".png", "wb") as f:
            f.write(buf.getvalue())
        return path_without_ext + ".png", buf.tell()
    for quality in (95, 92, 88, 84, 80):
        buf = io.BytesIO()
        img.convert("RGB").save(buf, "JPEG", quality=quality, optimize=True, subsampling=0)
        if buf.tell() < LIMIT:
            break
    with open(path_without_ext + ".jpg", "wb") as f:
        f.write(buf.getvalue())
    return path_without_ext + ".jpg", buf.tell()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out")
    parser.add_argument("shots", nargs="+", help='"name=source image"')
    parser.add_argument("--crop", help="x0,y0,x1,y1 applied to every source")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    taken = [int(m.group(1)) for f in os.listdir(args.out) if (m := re.match(r"(\d+)-", f))]
    number = max(taken, default=0)
    crop = tuple(int(v) for v in args.crop.split(",")) if args.crop else None
    for item in args.shots:
        name, _, source = item.partition("=")
        source, _, frame = source.partition("#")
        if not source or not os.path.exists(source):
            print(f"error: {item}: source not found", file=sys.stderr)
            return 1
        img = Image.open(source)
        if frame:
            img.seek(min(int(frame), getattr(img, "n_frames", 1) - 1))
        img = img.convert("RGB")
        if crop:
            img = img.crop(crop)
        number += 1
        path, size = save(img, os.path.join(args.out, f"{number:02d}-{name}"))
        print(f"{os.path.basename(path)}: {img.width}x{img.height}, {size / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
