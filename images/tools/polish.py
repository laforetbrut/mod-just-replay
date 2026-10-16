"""Finishing of the small pictures of the page with the ArtCraft suite: title ribbons, language
labels and the divider get a light that sweeps across them (EffectCraft), badges get a crisp bevel
and a soft sheen, the icons of the built-in set a one pixel bevel (PhotoCraft). titles.py calls it on its own; every effect keeps the hard edges and
the transparency that CurseForge's dark page needs, and the pixel art stays pixel art.

    python polish.py check     # what will be used on this computer

When an app is missing or CRAFT=off, the pictures are written as before, without finishing.

Author: vyrriox
"""
import os
import shutil
import sys
import tempfile

from PIL import Image

import craft
import fx

TITLE_FPS = 12.5        # titles play at 80 ms a frame

# Bevel and sheen for badges: inner effects only, so the outline of the badge does not move.
BADGE_ACTIONS = [
    ["layer.layerStyle.bevelEmboss", {"style": "inner", "technique": "chiselHard", "depth": 110, "size": 2, "soften": 0,
                                      "angle": 120, "altitude": 42}],
    ["layer.layerStyle.gradientOverlay", {"from": "#ffffff", "to": "#000000", "style": "linear", "angle": 90,
                                          "opacity": 18, "blend": "Overlay"}],
]


# A one pixel bevel for the drawn icons of the built-in set.
ICON_ACTIONS = [
    ["layer.layerStyle.bevelEmboss", {"style": "inner", "technique": "chiselHard", "depth": 90, "size": 1, "soften": 0,
                                      "angle": 120, "altitude": 45}],
]


def _light(theme):
    for key in ("accent_light", "white"):
        value = getattr(theme, key, None)
        if value is not None:
            return value
    return (255, 255, 255)


def title_frames(frames, theme):
    """A light sweeps once across the ribbon (its own bounding box), early in the loop."""
    box = frames[0].getchannel("A").getbbox() if frames else None
    if not box:
        return frames
    return fx.sweep(frames, TITLE_FPS, box=box, angle=-30, width=max(24, (box[2] - box[0]) // 14), intensity=55,
                    color=_light(theme), share=0.55)


def divider_frames(frames, theme):
    box = frames[0].getchannel("A").getbbox() if frames else None
    if not box:
        return frames
    return fx.sweep(frames, TITLE_FPS, box=box, angle=0, width=30, intensity=45, color=_light(theme), share=0.7)


def badges(images):
    """{file: RGBA image} -> the same, beveled, in one PhotoCraft batch."""
    return _batch(images, BADGE_ACTIONS, "badge")


def icons(images):
    """{name: RGBA icon of the built-in set} -> the same with a one pixel bevel."""
    return _batch(images, ICON_ACTIONS, "icon")


def _batch(images, actions, what):
    if not images or not craft.available("photocraft"):
        return images
    work = tempfile.mkdtemp(prefix="pagepolish_", dir=os.environ.get("PAGE_TEMP") or None)
    try:
        src, dst = os.path.join(work, "in"), os.path.join(work, "out")
        os.makedirs(src)
        os.makedirs(dst)
        names = {}
        for i, (file, img) in enumerate(images.items()):
            name = f"b{i:03d}.png"
            names[file] = name
            img.save(os.path.join(src, name))
        craft.pc_batch(actions, src, dst)
        out = {}
        for file, name in names.items():
            path = os.path.join(dst, name)
            if os.path.exists(path):
                polished = Image.open(path).convert("RGBA")
                # Keep the drawn outline exactly: the alpha of the original wins.
                polished.putalpha(images[file].getchannel("A"))
                out[file] = polished
            else:
                out[file] = images[file]
        return out
    except craft.CraftError as error:
        print(f"note: {what} finishing skipped: {error}")
        return images
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2 or sys.argv[1] != "check":
        print(__doc__)
        return 1
    import vector
    vec = vector.available()
    sweep = " + light sweep (EffectCraft)" if craft.available("effectcraft") else ""
    print("titles, labels, divider: " + ("vector (VectorCraft)" if vec else "pixel art") + sweep)
    print("badges: " + ("vector (VectorCraft)" if vec else "pixel art" + (" + bevel and sheen (PhotoCraft)" if craft.available("photocraft") else "")))
    print("feature icons: " + ("vector tiles (VectorCraft)" if vec else "pixel art" + (" + one pixel bevel (PhotoCraft)" if craft.available("photocraft") else "")))
    print("banners: " + ("finishing and branded hero available (EffectCraft)" if craft.available("effectcraft") else "as drawn"))
    print("(title_style = \"pixel\" and icon_style = \"plain\" in [page] keep the pixel look)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
