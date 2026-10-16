"""Frames of a GIF side by side, to look at an animation without playing it.

    python gifsheet.py banner.gif sheet.png [count | i,j,k... | #i] [--cols 2] [--width 0] [--bg dark|light] [--crop x0,y0,x1,y1 --zoom 4]

Look at every banner this way before it ships: the first and last frames (does it loop?), the
moment something appears, the text of every overlay. Frames are shown at their own size, so pixel
text stays readable; --width reduces them for a long clip. For a title with a see-through
background, --bg puts it on the dark background of CurseForge (default) or on white. --crop and
--zoom enlarge one part of every frame without smoothing, to judge a 16 pixel icon or a badge.

Author: vyrriox
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageSequence


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("gif")
    parser.add_argument("out")
    parser.add_argument("pick", nargs="?", default="12")
    parser.add_argument("--cols", type=int, default=2)
    parser.add_argument("--width", type=int, default=0, help="width of each frame on the sheet; 0 keeps the size of the GIF")
    parser.add_argument("--bg", choices=("dark", "light"), default="dark")
    parser.add_argument("--crop", help="x0,y0,x1,y1: only this part of each frame")
    parser.add_argument("--zoom", type=int, default=1, help="enlarge each pixel this many times")
    args = parser.parse_args()
    gif = Image.open(args.gif)
    bg = (13, 13, 13, 255) if args.bg == "dark" else (255, 255, 255, 255)
    frames, durations = [], []
    for f in ImageSequence.Iterator(gif):
        canvas = Image.new("RGBA", gif.size, bg)
        canvas.alpha_composite(f.convert("RGBA"))
        picture = canvas.convert("RGB")
        if args.crop:
            picture = picture.crop(tuple(int(v) for v in args.crop.split(",")))
        if args.zoom > 1:
            picture = picture.resize((picture.width * args.zoom, picture.height * args.zoom), Image.NEAREST)
        frames.append(picture)
        durations.append(f.info.get("duration", 0))
    n = len(frames)
    if args.pick.startswith("#"):
        idx = [int(args.pick[1:])]                       # one frame, by its number (from 0)
    elif "," in args.pick:
        idx = [int(v) for v in args.pick.split(",") if v.strip()]
    else:
        count = max(1, int(args.pick))
        idx = sorted({int(round(k * (n - 1) / max(1, count - 1))) for k in range(count)})
    dropped = [i for i in idx if not 0 <= i < n]
    if dropped:
        print(f"note: the banner has {n} frames, numbered 0 to {n - 1}: {', '.join(map(str, dropped))} left out")
    idx = [i for i in idx if 0 <= i < n]
    tw = args.width or frames[0].width
    th = int(frames[0].height * tw / frames[0].width)
    rows = (len(idx) + args.cols - 1) // args.cols
    sheet = Image.new("RGB", (args.cols * tw, rows * (th + 16)), (40, 40, 44))
    d = ImageDraw.Draw(sheet)
    for k, i in enumerate(idx):
        x, y = (k % args.cols) * tw, (k // args.cols) * (th + 16)
        f = frames[i] if tw == frames[i].width else frames[i].resize((tw, th), Image.LANCZOS)
        sheet.paste(f, (x, y + 16))
        d.text((x + 4, y + 2), f"frame {i} (0 to {n - 1})", fill=(255, 255, 255))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    sheet.save(args.out)
    print(f"{args.out}: {n} frames, {sum(durations) / 1000:.1f} s, {gif.size[0]}x{gif.size[1]}")


if __name__ == "__main__":
    main()
