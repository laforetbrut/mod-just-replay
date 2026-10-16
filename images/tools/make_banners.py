"""Builds the animated banners of the project page (images/*.gif).

    python images/tools/make_banners.py [names...]

The footage comes from a clip made with the mod itself (a Drop the Beam festival filmed in a replay,
exported at 1080p), cut into stills at 1600 x 900 in run-capture/screenshots:

    still_NNN.png             one frame at second NNN of the clip
    hero_0001..0068.png       3.4 s of the clip from 0:50, 20 frames per second
    scene-editor-*.png        the editor open on each tab over the same replay (GUI scale 2)

Look at every banner with gifsheet.py before it ships, and at heat.py when one is too heavy.

Author: vyrriox
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import fx  # noqa: E402
import kit  # noqa: E402
from theme import Theme  # noqa: E402

SRC = os.path.join(ROOT, "run-capture", "screenshots")
OUT = os.path.dirname(HERE)             # images/, the folder this toolkit sits in
THEME = Theme.load(os.path.join(HERE, "theme.json"))
BRAND_LOGO = os.path.join(ROOT, "Branding", "logo", "png", "just-replay-horizontal-reverse-1024.png")

# Every banner is finished in EffectCraft before it is encoded: a light grade and vignette, nothing that
# moves on its own, so the footage stays the subject and the files stay light.
kit.LOOK = fx.look(THEME, vibrance=12, vignette=22)


def src(name):
    return os.path.join(SRC, name)


def out(name):
    return os.path.join(OUT, name)


# ------------------------------------------------------------------ the banner on top of the page

def hero():
    # The brand logo of Branding/ on the festival hall seen from above (the tagline is in the page intro).
    frames = fx.branded_hero(THEME, src("still_050.png"), BRAND_LOGO, tagline=None,
                             particles=8, particle_type="cube", darken=68)
    kit.finish([kit.frame_border(f, THEME) for f in frames], out("hero.gif"), look=False)


# ------------------------------------------------------------------ the editor, tab by tab

def editor():
    # Only the side panel changes from one tab to the next: the world and the timeline stay still.
    tabs = ["cameras", "actors", "effects", "audio", "overlays"]
    stills = [src(f"scene-editor-advanced-{t}.png") for t in tabs]
    kit.swap_scene(out("editor.gif"), THEME, stills, crop=(0, 0, 1600, 900), size=(800, 450), box=(0, 22, 228, 312), hold=26)


# ------------------------------------------------------------------ what comes out of it

def montage():
    # Shots of the finished clip, one after the other, the way the editor cuts them on the beat.
    shots = [src(f"still_{t:03d}.png") for t in (72, 28, 100, 34, 108, 112)]
    kit.swap_scene(out("montage.gif"), THEME, shots, crop=kit.crop_for((800, 320), center=(0.5, 0.5)), size=(800, 320), hold=22)


BANNERS = {"hero": hero, "editor": editor, "montage": montage}

if __name__ == "__main__":
    names = sys.argv[1:] or list(BANNERS)
    for name in names:
        BANNERS[name]()
