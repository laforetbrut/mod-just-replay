"""Shows where a GIF changes from frame to frame: the brighter, the more often. A banner is heavy
when things change that nobody needs to see moving.

    python heat.py banner.gif heat.png

Read the picture before lowering the quality of a banner that is over 2 MB. Usual causes, and what
to do about them:
  - leaves or crops waving in the moving area        tighten the boxes; freeze the wind before recording
  - the whole view drifting slowly                   the sun moved: freeze the daylight cycle and record again
  - noise everywhere in the moving area              raise the threshold of kit.stabilize

Author: vyrriox
"""
import os
import sys

import numpy as np
from PIL import Image, ImageSequence


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    gif = Image.open(sys.argv[1])
    frames = [np.asarray(f.convert("RGB"), dtype=np.int16) for f in ImageSequence.Iterator(gif)]
    acc = np.zeros(frames[0].shape[:2], dtype=np.float32)
    per = []
    for a, b in zip(frames, frames[1:]):
        d = np.abs(b - a).max(axis=2) > 0
        acc += d
        per.append(int(d.sum()))
    acc /= max(1, len(frames) - 1)
    os.makedirs(os.path.dirname(os.path.abspath(sys.argv[2])), exist_ok=True)
    Image.fromarray((np.clip(acc * 2.5, 0, 1) * 255).astype(np.uint8), "L").save(sys.argv[2])
    h, w = acc.shape
    total = float(acc.sum()) or 1.0
    cols = [acc[:, x0:x0 + w // 16].sum() / total * 100 for x0 in range(0, w - w // 16 + 1, w // 16)]
    rows = [acc[y0:y0 + h // 8, :].sum() / total * 100 for y0 in range(0, h - h // 8 + 1, h // 8)]
    print(f"{len(frames)} frames; pixels that change per frame: {int(np.mean(per))} on average, {max(per)} at most, of {w * h}")
    print("share of the changes, left to right (16 columns): " + " ".join(f"{c:.0f}" for c in cols))
    print("share of the changes, top to bottom (8 rows):     " + " ".join(f"{c:.0f}" for c in rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
