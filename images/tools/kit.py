"""Drawing, animation and encoding helpers for the images of a mod page: animated banners made
from game footage, section titles, and everything they share.

The low level part (easing, sprites, footage, encoders) is general. The scenes at the end
(`hero_banner`, `clip_scene`, `chat_scene`, `tour_scene`, `swap_scene`, `still_scene`,
`showcase_scene`) cover the banners most mods need; each takes an `overlay(canvas, i, n)` callback
for what is specific to a mod: a card, a counter, a label. `backdrop`, `picture` and `iso_block`
make banners out of the mod's own textures when there is no footage; iso.py draws whole builds and
JSON models. Read make_banners.py for
worked examples.

Requires Pillow, numpy and ffmpeg.

Author: vyrriox
"""
import copy
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import icons as icon_lib  # noqa: E402
import pixelfont as font  # noqa: E402
from theme import Theme, contrast  # noqa: E402,F401

FPS = 20
W = 800                     # every banner and title is 800 pixels wide: the width of the CurseForge column
MAX_BYTES = 2_000_000       # CurseForge refuses an uploaded image of 2 MB or more


# ------------------------------------------------------------------ easing

def clamp(t, lo=0.0, hi=1.0):
    return lo if t < lo else hi if t > hi else t


def lerp(a, b, t):
    return a + (b - a) * t


def ease_out(t):
    t = clamp(t)
    return 1 - (1 - t) ** 3


def ease_in(t):
    t = clamp(t)
    return t ** 3


def ease_in_out(t):
    t = clamp(t)
    return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def ease_out_back(t, overshoot=1.70158):
    t = clamp(t)
    c3 = overshoot + 1
    return 1 + c3 * (t - 1) ** 3 + overshoot * (t - 1) ** 2


def span(t, start, end):
    """Progress 0..1 of t between start and end (in the same unit as t)."""
    if end <= start:
        return 1.0 if t >= end else 0.0
    return clamp((t - start) / (end - start))


def hop(t, at, height=3, length=0.24):
    """Vertical offset of a sprite that hops once per loop, around the moment `at` (t and at in 0..1)."""
    k = (t - at) % 1.0
    return -int(round(math.sin(k / length * math.pi) * height)) if k < length else 0


# ------------------------------------------------------------------ sprites

_icon_cache = {}


def icon(name, theme, scale=1):
    """An icon 16 * scale pixels wide: a name of the built-in set (icons.py) or the path of an image,
    such as one of the mod's own item textures. Pixel art is scaled without smoothing."""
    key = (name, theme.name, theme.hex("accent"), scale)
    if key in _icon_cache:
        return _icon_cache[key]
    if os.path.exists(str(name)):
        img = Image.open(name).convert("RGBA")
        if img.height > img.width:          # animated texture: frames stacked vertically, keep the first
            img = img.crop((0, 0, img.width, img.width))
        size = 16 * scale
        if img.size != (size, size):
            exact = size % img.width == 0 and img.width == img.height
            img = img.resize((size, size), Image.NEAREST if exact or img.width <= 32 else Image.LANCZOS)
    else:
        img = icon_lib.icon(name, theme, scale)
    _icon_cache[key] = img
    return img


def ornament(theme, scale=1):
    """The small sprite that decorates titles and floats in banners: the theme's ornament."""
    return icon(theme.ornament, theme, scale)


def picture(source, theme, size=96):
    """A sprite ready to stand in a banner, about `size` pixels tall: an image as it is, the path of a
    texture (pixel art is enlarged by a whole factor, so its pixels stay square), the name of a
    built-in icon, or a tuple of one to three block textures (top, side, front) drawn as a block."""
    if isinstance(source, Image.Image):
        return source.convert("RGBA")
    if isinstance(source, (tuple, list)):
        return iso_block(*source, size=max(32, size // 16 * 16))
    if os.path.exists(str(source)):
        img = Image.open(source).convert("RGBA")
        if img.height > img.width and img.height % img.width == 0:      # animated texture: keep the first frame
            img = img.crop((0, 0, img.width, img.width))
        if max(img.size) <= size:
            k = max(1, size // max(img.size))
            return img.resize((img.width * k, img.height * k), Image.NEAREST)
        img.thumbnail((size, size), Image.LANCZOS)
        return img
    return icon_lib.icon(source, theme, max(1, size // 16))


def iso_block(top, side=None, front=None, size=96):
    """A block seen from above a corner, the way the game draws blocks in the inventory, from its
    textures (paths or images): the top face, the left face and the right face. One texture is enough
    for a block that looks the same all around. The result is size x size, see-through around the block."""
    def face(source):
        img = source if isinstance(source, Image.Image) else Image.open(source)
        img = img.convert("RGBA")
        if img.height > img.width and img.height % img.width == 0:
            img = img.crop((0, 0, img.width, img.width))
        return img

    def shade(img, k):
        r, g, b, a = img.split()
        return Image.merge("RGBA", (r.point(lambda v: int(v * k)), g.point(lambda v: int(v * k)), b.point(lambda v: int(v * k)), a))

    top = face(top)
    left = face(side) if side is not None else top
    right = face(front) if front is not None else left
    s = float(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # For each face: the affine map from a pixel of the result to a pixel of the texture, and its outline.
    faces = [
        (top, 1.0, lambda t: (t / s, 2 * t / s, -t / 2, -t / s, 2 * t / s, t / 2),
         [(s / 2, 0), (s, s / 4), (s / 2, s / 2), (0, s / 4)]),
        (left, 0.78, lambda t: (2 * t / s, 0, 0, -t / s, 2 * t / s, -t / 2),
         [(0, s / 4), (s / 2, s / 2), (s / 2, s), (0, 3 * s / 4)]),
        (right, 0.58, lambda t: (2 * t / s, 0, -t, t / s, 2 * t / s, -3 * t / 2),
         [(s / 2, s / 2), (s, s / 4), (s, 3 * s / 4), (s / 2, s)]),
    ]
    for img, light, coeffs, outline in faces:
        # The texture gets a border of its own edge pixels: where two faces meet, a sample that falls just
        # outside the texture takes the edge color instead of leaving a see-through dot in the seam.
        padded = Image.fromarray(np.pad(np.asarray(shade(img, light)), ((1, 1), (1, 1), (0, 0)), mode="edge"), "RGBA")
        a, b, c, d, e, f = coeffs(float(img.width))
        warped = padded.transform((size, size), Image.AFFINE, (a, b, c + 1, d, e, f + 1), resample=Image.NEAREST)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).polygon(outline, fill=255, outline=255)
        out.paste(warped, (0, 0), ImageChops.multiply(mask, warped.getchannel("A")))
    return out


def sprite(rows, colors, scale=1, outline=None):
    """A sprite written with one letter per pixel ('.' is empty), optionally outlined like a sticker."""
    img = Image.new("RGBA", (len(rows[0]), len(rows)), (0, 0, 0, 0))
    px = img.load()
    solid = set()
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c in colors:
                px[x, y] = colors[c]
                solid.add((x, y))
    if outline:
        for y in range(img.height):
            for x in range(img.width):
                if (x, y) not in solid and any((x + dx, y + dy) in solid for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    px[x, y] = outline
    return img.resize((img.width * scale, img.height * scale), Image.NEAREST) if scale != 1 else img


def with_alpha(img, alpha):
    """A copy of the image with its alpha multiplied (0..1)."""
    if alpha >= 0.999:
        return img
    out = img.copy()
    out.putalpha(out.getchannel("A").point(lambda a: int(a * clamp(alpha))))
    return out


def paste(dst, src, x, y, alpha=1.0, scale=1.0, anchor="tl", smooth=False):
    """Alpha composites src on dst. anchor: tl (top left) or c (centre); scale resizes around the anchor."""
    if alpha <= 0.003:
        return
    if scale != 1.0:
        w = max(1, int(round(src.width * scale)))
        h = max(1, int(round(src.height * scale)))
        src = src.resize((w, h), Image.LANCZOS if smooth else Image.NEAREST)
    if anchor == "c":
        x -= src.width / 2
        y -= src.height / 2
    src = with_alpha(src, alpha)
    x, y = int(round(x)), int(round(y))
    sx0, sy0 = max(0, -x), max(0, -y)
    sx1, sy1 = min(src.width, dst.width - x), min(src.height, dst.height - y)
    if sx1 <= sx0 or sy1 <= sy0:
        return
    dst.alpha_composite(src.crop((sx0, sy0, sx1, sy1)), (x + sx0, y + sy0))


def cover(img, w, h, fx=0.5, fy=0.5):
    """Scales and crops the image to fill w x h, keeping the point (fx, fy) of the source in view."""
    s = max(w / img.width, h / img.height)
    nw, nh = int(math.ceil(img.width * s)), int(math.ceil(img.height * s))
    img = img.resize((nw, nh), Image.LANCZOS)
    x = int(round((nw - w) * fx))
    y = int(round((nh - h) * fy))
    return img.crop((x, y, x + w, y + h))


def rounded(w, h, radius, fill, border=None, border_width=2):
    """A pixel sharp rounded rectangle (no smoothing)."""
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if border:
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=border)
        d.rounded_rectangle([border_width, border_width, w - 1 - border_width, h - 1 - border_width],
                            radius=max(0, radius - border_width), fill=fill)
    else:
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=fill)
    return img


def _outline(kind, w, h, inset=0):
    """Points of the shape of a title, shrunk by `inset` pixels on every side."""
    i = inset
    if kind == "plate":
        c = max(2, 7 - inset)
        return [(i + c, i), (w - 1 - i - c, i), (w - 1 - i, i + c), (w - 1 - i, h - 1 - i - c), (w - 1 - i - c, h - 1 - i),
                (i + c, h - 1 - i), (i, h - 1 - i - c), (i, i + c)]
    if kind == "banner":
        n = h // 3
        return [(i, i), (w - 1 - i, i), (w - 1 - i - n, h // 2), (w - 1 - i, h - 1 - i), (i, h - 1 - i), (i + n, h // 2)]
    raise ValueError(kind)


def shape(theme, w, h, fill, border=None, border_width=3, kind=None):
    """The body of a title in the shape of the theme: pill (round ends), plate (cut corners) or banner (forked ends)."""
    kind = kind or theme.shape
    if kind == "pill":
        return rounded(w, h, h // 2, fill, border, border_width)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if border:
        d.polygon(_outline(kind, w, h), fill=border)
        d.polygon(_outline(kind, w, h, border_width), fill=fill)
    else:
        d.polygon(_outline(kind, w, h), fill=fill)
    return img


def shape_inset(theme, kind=None):
    """How far the content must stay from the left and right ends of a shape."""
    return {"pill": 18, "plate": 16, "banner": 30}[kind or theme.shape]


def panel(w, h, theme, radius=10, fill=None, border=None, border_width=2, shadow=True, shadow_offset=3):
    """A card: light body, accent border, soft shadow under it. For overlays on footage."""
    out = Image.new("RGBA", (w + shadow_offset, h + shadow_offset), (0, 0, 0, 0))
    if shadow:
        out.alpha_composite(rounded(w, h, radius, theme.ink[:3] + (70,)), (shadow_offset, shadow_offset))
    out.alpha_composite(rounded(w, h, radius, fill or theme.paper, border or theme.accent, border_width), (0, 0))
    return out


def gradient(w, h, left, right, vertical=False):
    """A linear gradient between two RGBA colors."""
    n = h if vertical else w
    ramp = np.linspace(0, 1, n, dtype=np.float32)
    a = np.array(left, dtype=np.float32)
    b = np.array(right, dtype=np.float32)
    line = (a[None, :] * (1 - ramp[:, None]) + b[None, :] * ramp[:, None]).astype(np.uint8)
    arr = np.repeat(line[:, None, :], w, axis=1) if vertical else np.repeat(line[None, :, :], h, axis=0)
    return Image.fromarray(arr, "RGBA")


def _mix(a, b, t):
    return tuple(int(round(a[k] + (b[k] - a[k]) * t)) for k in range(3))


def backdrop(theme, size=(W, 320), kind="glow", focus=(0.5, 0.55), seed=7):
    """A background in the colors of the theme, for a banner made without footage (hero_banner,
    still_scene and showcase_scene take it as their plate). Flat, banded colors: it stays pixel art
    and weighs almost nothing in a GIF.

    glow    dark, the accent glowing behind the subject: textures and icons stand out on it
    paper   light, the color of the title ribbons, brighter behind the subject
    tiles   a wall of dim blocks, like a menu background of the game
    focus is where the subject stands, as fractions of the size."""
    w, h = size
    cell = 8
    bw, bh = w // cell + 1, h // cell + 1
    yy, xx = np.mgrid[0:bh, 0:bw].astype(np.float32)
    d = np.sqrt(((xx - bw * focus[0]) / (bw * 0.55)) ** 2 + ((yy - bh * focus[1]) / (bh * 0.8)) ** 2)
    level = np.round(np.clip(1 - d, 0, 1) ** 1.4 * 6) / 6          # six flat bands of light
    night = _mix(theme.ink, (0, 0, 0), 0.45)
    if kind == "glow":
        low, high, reach = night, _mix(theme.accent_dark, theme.accent, 0.35), 0.62
    elif kind == "paper":
        low, high, reach = _mix(theme.paper, theme.accent, 0.16), theme.white[:3], 0.9
    elif kind == "tiles":
        low, high, reach = _mix(night, theme.accent_dark, 0.18), _mix(night, theme.accent_dark, 0.55), 0.8
    else:
        raise ValueError(f"backdrop kind '{kind}' (glow, paper, tiles)")
    low_a, high_a = np.array(low, dtype=np.float32), np.array(high, dtype=np.float32)
    arr = low_a[None, None, :] + (high_a - low_a)[None, None, :] * (level * reach)[:, :, None]
    img = Image.fromarray(arr.astype(np.uint8), "RGB").resize((bw * cell, bh * cell), Image.NEAREST).crop((0, 0, w, h))
    d = ImageDraw.Draw(img, "RGBA")         # on an RGB image, a fill with alpha is blended
    if kind == "tiles":
        rng = random.Random(seed)
        tile = 32
        for ty in range(0, h, tile):
            for tx in range(0, w, tile):
                tone = rng.choice((0, 0, 10, 18, 26))
                d.rectangle([tx, ty, tx + tile - 1, ty + tile - 1], fill=(0, 0, 0, tone))
                d.line([tx, ty, tx + tile - 1, ty], fill=(255, 255, 255, 16))
                d.line([tx, ty, tx, ty + tile - 1], fill=(255, 255, 255, 16))
                d.line([tx, ty + tile - 1, tx + tile - 1, ty + tile - 1], fill=(0, 0, 0, 60))
                d.line([tx + tile - 1, ty, tx + tile - 1, ty + tile - 1], fill=(0, 0, 0, 60))
    else:
        # A faint checker, sixteen pixels wide like a block face.
        tint = (255, 255, 255, 7) if kind == "glow" else (0, 0, 0, 6)
        for ty in range(0, h, 16):
            for tx in range((ty // 16 % 2) * 16, w, 32):
                d.rectangle([tx, ty, tx + 15, ty + 15], fill=tint)
    return img.convert("RGBA")


def text_image(text, scale, color, shadow=None, spacing=1, outline=None):
    """The text on its own transparent image (accent and descender rows included). shadow draws a
    second copy one pixel down and right; outline surrounds every letter, which keeps a text readable
    over footage or over a busy background."""
    pad = scale if outline else 0
    w = font.width(text, scale, spacing) + scale + 2 * pad
    h = font.LINE_ROWS * scale + scale + 2 * pad
    img = Image.new("RGBA", (max(1, w), h), (0, 0, 0, 0))
    if outline:
        for dx in (-pad, 0, pad):
            for dy in (-pad, 0, pad):
                if dx or dy:
                    font.draw(img, text, pad + dx, pad + font.ACCENT_ROWS * scale + dy, scale, outline, None, spacing)
    font.draw(img, text, pad, pad + font.ACCENT_ROWS * scale, scale, color, shadow, spacing)
    return img


def label(text, theme, scale=2, icon_name=None, pad=8, fill=None, border=None, color=None):
    """A small chip: optional icon, then the text. For captions on footage."""
    tw = font.width(text, scale)
    th = font.height(scale)
    ic = icon(icon_name, theme, 1 if scale < 3 else 2) if icon_name else None
    w = pad * 2 + tw + (ic.width + 6 if ic else 0)
    h = max(th, ic.height if ic else 0) + pad * 2 - 2
    out = panel(w, h, theme, radius=h // 2, fill=fill, border=border)
    x = pad
    if ic:
        out.alpha_composite(ic, (x, (h - ic.height) // 2))
        x += ic.width + 6
    font.draw(out, text, x, (h - th) // 2, scale, color or theme.ink)
    return out


def bar(w, h, ratio, color, track=None, border=None, theme=None):
    """A progress bar: rounded track, coloured fill. With a theme the track takes its paper and ink;
    without one it is a neutral grey."""
    if theme is not None:
        track = track or _mix(theme.paper, theme.ink, 0.14) + (255,)
        border = border or _mix(theme.paper, theme.ink, 0.34) + (255,)
    track, border = track or (226, 226, 232, 255), border or (190, 190, 200, 255)
    img = rounded(w, h, h // 2, track, border, 1)
    fw = int(round((w - 4) * clamp(ratio)))
    if fw > 0:
        img.alpha_composite(rounded(max(fw, h - 4), h - 4, (h - 4) // 2, color), (2, 2))
    return img


BOSS_COLORS = {"pink": (236, 0, 184), "blue": (0, 183, 236), "red": (236, 52, 0), "green": (29, 236, 0), "yellow": (233, 236, 0),
               "purple": (123, 0, 236), "white": (236, 236, 236)}


def boss_bar(width, ratio, color="red", label=None, notches=0, scale=2):
    """A boss bar the way the game draws one, at the top of the screen: the name above, a thin bar
    with a dark track, optional notches. color is one of the game's seven (pink, blue, red, green,
    yellow, purple, white) or any RGB. For a mod that shows its progress with a boss bar."""
    rgb = BOSS_COLORS.get(color, color) if isinstance(color, str) else tuple(color[:3])
    bar_h = 5 * scale
    top = (font.height(scale) + 3 * scale) if label else 0
    out = Image.new("RGBA", (width, top + bar_h), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    dark = tuple(int(c * 0.35) for c in rgb) + (255,)
    d.rectangle([0, top, width - 1, top + bar_h - 1], fill=dark)
    fill = int(round(width * clamp(ratio)))
    if fill > 0:
        d.rectangle([0, top, fill - 1, top + bar_h - 1], fill=rgb + (255,))
        d.rectangle([0, top, fill - 1, top + scale - 1], fill=tuple(min(255, int(c * 1.25 + 30)) for c in rgb) + (255,))
    for k in range(1, notches):
        x = int(width * k / notches)
        d.rectangle([x, top + scale, x + scale - 1, top + bar_h - 1], fill=(0, 0, 0, 120))
    if label:
        lw = font.width(label, scale)
        font.draw(out, label, (width - lw) // 2, 0, scale, (255, 255, 255, 255), (60, 60, 60, 255))
    return out


def counter(text, theme, icon_name=None, width=None, scale=3):
    """A card that shows a number (a score, a count): an icon, then the text. Give it a fixed width
    when the number changes during a banner, so the card does not jump as digits come and go."""
    ic = icon(icon_name, theme, 2) if icon_name else None
    th = font.height(scale)
    h = max(th, ic.height if ic else 0) + 22
    inner = (ic.width + 12 if ic else 0) + font.width(text, scale)
    w = width or inner + 36
    card = panel(w, h, theme)
    x = (w - inner) // 2
    if ic:
        card.alpha_composite(ic, (x, (h - ic.height) // 2))
        x += ic.width + 12
    font.draw(card, text, x, (h - th) // 2, scale, theme.ink)
    return card


POINTER = [
    "X..........",
    "XX.........",
    "XoX........",
    "XooX.......",
    "XoooX......",
    "XooooX.....",
    "XoooooX....",
    "XooooooX...",
    "XoooooooX..",
    "XooooXXXXX.",
    "XooXooX....",
    "XoX.XooX...",
    "XX..XooX...",
    "X....XooX..",
    ".....XooX..",
    "......XX...",
]


def pointer(theme, scale=2):
    """A mouse pointer in pixel art. Its tip is the top left corner of the image."""
    return sprite(POINTER, {"X": theme.ink, "o": theme.white}, scale)


def click_ring(canvas, x, y, age, color, life=6):
    """A ring that grows and fades where something was just clicked. age: frames since the click."""
    if not 0 <= age < life:
        return
    r = 8 + age * 4
    ring = Image.new("RGBA", (r * 2 + 4, r * 2 + 4), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse([1, 1, r * 2 + 2, r * 2 + 2], outline=color[:3] + (int(230 * (1 - age / life)),), width=3)
    paste(canvas, ring, x, y, anchor="c")


class Floaters:
    """Sprites that drift and fade on a loop of `period` frames, so the animation repeats without a jump."""

    def __init__(self, sprites, count, box, period, seed=1, rise=60, sway=8):
        rng = random.Random(seed)
        self.period = period
        self.items = [{
            "sprite": rng.choice(sprites),
            "x": rng.uniform(box[0], box[2]),
            "y": rng.uniform(box[1], box[3]),
            "phase": rng.random(),
            "rise": rise * rng.uniform(0.7, 1.3),
            "sway": sway * rng.uniform(0.5, 1.2),
            "swayPhase": rng.uniform(0, math.tau),
            "loops": rng.choice((1, 1, 2)),
        } for _ in range(count)]

    def draw(self, dst, frame, alpha=1.0):
        for it in self.items:
            t = (frame / self.period * it["loops"] + it["phase"]) % 1.0
            a = min(1.0, t / 0.18, (1 - t) / 0.3) * alpha
            x = it["x"] + math.sin(t * math.tau + it["swayPhase"]) * it["sway"]
            paste(dst, it["sprite"], x, it["y"] - it["rise"] * t, alpha=a, anchor="c")


def twinkle(dst, spr, x, y, frame, period, phase=0.0, alpha=1.0):
    """A sparkle that grows and fades on a loop."""
    t = (frame / period + phase) % 1.0
    k = math.sin(t * math.pi) ** 2
    paste(dst, spr, x, y, alpha=k * alpha, scale=0.5 + 0.5 * k, anchor="c")


def wipe_line(canvas, x, theme, top=30, bottom=None, frame=0):
    """The bright line of a wipe between two states, with sparkles on it."""
    bottom = bottom if bottom is not None else canvas.height - 30
    paste(canvas, Image.new("RGBA", (6, max(1, bottom - top)), (255, 255, 255, 150)), int(x) - 3, top)      # clipped at the edges
    big, small = icon("sparkle", theme, 2), icon("sparkle", theme, 1)
    count = 4
    for j in range(count):
        y = top + (bottom - top) * (j + 0.5) / count + (frame * 7 + j * 13) % 17 - 8
        paste(canvas, big if j % 2 == 0 else small, x + (4 if j % 2 else -6), y, anchor="c")


# ------------------------------------------------------------------ footage

def load(path, size=None, crop=None):
    """Loads a captured frame. crop is (x0, y0, x1, y1) in source pixels, applied before the resize."""
    img = path if isinstance(path, Image.Image) else Image.open(path)
    img = img.convert("RGB")
    if crop:
        img = img.crop(crop)
    if size and img.size != tuple(size):
        img = img.resize(size, Image.LANCZOS)
    return img


def clip(folder, name, first, last, step=1):
    """Paths of the frames <name>_<number>.png that exist, in order (the capture tool numbers them from 1)."""
    paths = [os.path.join(folder, f"{name}_{i:04d}.png") for i in range(first, last + 1, step)]
    return [p for p in paths if os.path.exists(p)]


def footage(paths, crop, size):
    return [load(p, size=size, crop=crop) for p in paths]


def crop_for(size, source=(1600, 900), center=(0.5, 0.5), zoom=1.0):
    """A crop box of the source with the proportions of `size`, centred on a point given as fractions of the source.
    zoom 1.0 takes the largest box that fits; 1.5 a box one and a half times smaller."""
    ratio = size[0] / size[1]
    w = min(source[0], source[1] * ratio) / zoom
    h = w / ratio
    x0 = clamp(center[0] * source[0] - w / 2, 0, source[0] - w)
    y0 = clamp(center[1] * source[1] - h / 2, 0, source[1] - h)
    return (int(round(x0)), int(round(y0)), int(round(x0 + w)), int(round(y0 + h)))


def feather_mask(size, box, feather):
    """White inside box, fading to black over `feather` pixels inside its edges. A box may reach past the
    frame: the mask then stays white up to that edge."""
    mask = Image.new("L", size, 0)
    inner = (box[0] + feather, box[1] + feather, box[2] - feather, box[3] - feather)
    ImageDraw.Draw(mask).rectangle(inner, fill=255)
    return mask.filter(ImageFilter.GaussianBlur(feather / 2.0)) if feather > 0 else mask


def composite_region(frames, plate, boxes, feather=14):
    """Keeps the footage inside the boxes (one box or several) and the still plate everywhere else.
    This is what keeps a banner small: outside the boxes nothing changes from frame to frame."""
    if isinstance(boxes[0], (int, float)):
        boxes = [boxes]
    mask = None
    for box in boxes:
        m = feather_mask(plate.size, box, feather)
        mask = m if mask is None else ImageChops.lighter(mask, m)
    return [Image.composite(f, plate, mask) for f in frames]


def stabilize(frames, threshold=7):
    """Keeps a pixel as it was in the previous frame when it barely changed: the noise of the renderer
    disappears, static areas become identical from frame to frame, and the GIF gets much smaller."""
    out = []
    prev = None
    for f in frames:
        arr = np.asarray(f.convert("RGB"), dtype=np.int16)
        if prev is None:
            cur = arr
        else:
            keep = np.abs(arr - prev).max(axis=2) < threshold
            cur = np.where(keep[:, :, None], prev, arr)
        prev = cur
        out.append(Image.fromarray(cur.astype(np.uint8), "RGB"))
    return out


def loop_tail(frames, base, count):
    """Eases the last frames into the base image, so the clip loops without a jump."""
    count = min(count, len(frames))
    for j in range(count):
        t = (j + 1) / (count + 1)
        frames[-count + j] = Image.blend(frames[-count + j], base, t)
    return frames


def first_change(frames, box, threshold=40, share=0.012):
    """Index of the first frame that differs from the first one inside box (something appears or starts to move)."""
    base = np.asarray(frames[0].crop(box), dtype=np.int16)
    for i, f in enumerate(frames):
        d = np.abs(np.asarray(f.crop(box), dtype=np.int16) - base).max(axis=2) > threshold
        if d.mean() > share:
            return i
    return 0


# ------------------------------------------------------------------ output

def write_gif(frames, path, fps=FPS, colors=256, dither="bayer:bayer_scale=5"):
    """Encodes opaque frames with one palette for the whole clip (ffmpeg palettegen and paletteuse)."""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is not on the PATH: it is needed to encode the banners")
    tmp = tempfile.mkdtemp(prefix="pagebanner_", dir=os.environ.get("PAGE_TEMP") or None)     # PAGE_TEMP: a folder of your choice
    try:
        for i, f in enumerate(frames):
            f.convert("RGB").save(os.path.join(tmp, f"f_{i:04d}.png"), compress_level=1)
        vf = (f"[0:v]split[a][b];[a]palettegen=max_colors={colors}:stats_mode=full[p];"
              f"[b][p]paletteuse=dither={dither}:diff_mode=rectangle")
        cmd = ["ffmpeg", "-y", "-v", "error", "-framerate", str(fps), "-i", os.path.join(tmp, "f_%04d.png"),
               "-filter_complex", vf, "-loop", "0", path]
        subprocess.run(cmd, check=True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return os.path.getsize(path)


def write_sprite_gif(frames, path, duration=80, threshold=128):
    """Encodes pixel art drawn on RGBA frames as a GIF with a see-through background: one palette for
    all the frames, hard edges (a pixel is either shown or not), every frame drawn on a clean slate."""
    arrays = [np.asarray(f.convert("RGBA")) for f in frames]
    index = {}
    packed = []
    for a in arrays:
        rgb = (a[:, :, 0].astype(np.uint32) << 16) | (a[:, :, 1].astype(np.uint32) << 8) | a[:, :, 2].astype(np.uint32)
        packed.append(rgb)
        for value in np.unique(rgb[a[:, :, 3] >= threshold]):
            index.setdefault(int(value), len(index) + 1)
    if len(index) > 255:
        # An icon with soft edges or a rendered picture brought more colors than a GIF holds: reduce
        # every frame to one shared palette of flat colors, then encode as usual.
        strip = Image.fromarray(np.array(sorted(index), dtype=np.uint32).view(np.uint8).reshape(1, -1, 4)[:, :, [2, 1, 0]].copy(), "RGB")
        reduced = strip.quantize(colors=255, method=Image.MEDIANCUT)
        frames = [Image.merge("RGBA", (*f.convert("RGB").quantize(palette=reduced, dither=Image.Dither.NONE).convert("RGB").split(),
                                       f.convert("RGBA").getchannel("A"))) for f in frames]
        return write_sprite_gif(frames, path, duration, threshold)
    palette = [0, 0, 0]
    for value in index:
        palette += [value >> 16, (value >> 8) & 255, value & 255]
    images = []
    for a, rgb in zip(arrays, packed):
        values, inverse = np.unique(rgb, return_inverse=True)
        lookup = np.array([index.get(int(v), 0) for v in values], dtype=np.uint8)
        pixels = lookup[inverse].reshape(rgb.shape)
        pixels[a[:, :, 3] < threshold] = 0
        img = Image.fromarray(pixels, "P")
        img.putpalette(palette)
        images.append(img)
    images[0].save(path, save_all=True, append_images=images[1:], duration=duration, loop=0, transparency=0, disposal=2,
                   optimize=False)
    return os.path.getsize(path)


def frame_border(img, theme, width=3):
    """The frame around every banner: an accent line, then a thin white one."""
    d = ImageDraw.Draw(img)
    w, h = img.size
    for i in range(width):
        d.rectangle([i, i, w - 1 - i, h - 1 - i], outline=theme.accent_dark)
    d.rectangle([width, width, w - 1 - width, h - 1 - width], outline=theme.white)
    return img


def contact_sheet(frames, path, count=12, cols=4, thumb=400):
    """A few frames of a clip side by side, to look at a banner without playing it."""
    n = len(frames)
    idx = sorted({int(round(k * (n - 1) / max(1, count - 1))) for k in range(count)})
    th = int(frames[0].height * thumb / frames[0].width)
    rows = (len(idx) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * thumb, rows * (th + 16)), (20, 20, 24))
    d = ImageDraw.Draw(sheet)
    for k, i in enumerate(idx):
        x, y = (k % cols) * thumb, (k // cols) * (th + 16)
        sheet.paste(frames[i].convert("RGB").resize((thumb, th), Image.LANCZOS), (x, y + 16))
        d.text((x + 4, y + 2), f"{i + 1}/{n}", fill=(255, 255, 255))
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    sheet.save(path)


def finish(frames, path, fps=FPS, sheet=True, **kwargs):
    """Writes the banner, prints its weight, and saves a contact sheet next to it when BANNER_SHEETS names a folder."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    size = write_gif(frames, path, fps=fps, **kwargs)
    name = os.path.basename(path)
    flag = "" if size < MAX_BYTES else "   <-- TOO HEAVY: 2 MB is the limit, shorten the clip or tighten the moving area"
    print(f"{name}: {len(frames)} frames, {frames[0].width}x{frames[0].height}, {len(frames) / fps:.1f} s, {size / 1e6:.2f} MB{flag}")
    sheets = os.environ.get("BANNER_SHEETS")
    if sheet and sheets:
        os.makedirs(sheets, exist_ok=True)
        contact_sheet(frames, os.path.join(sheets, os.path.splitext(name)[0] + ".png"))
    return size


# ------------------------------------------------------------------ titles

TITLE_FRAMES = 24       # 80 ms each


def ribbon(text, theme, icon_name=None, alt=False, width=W):
    """Frames of a section title on a see-through background: the text on the shape of the theme,
    between two icons, with a line, a sparkle and an ornament on each side. Icons and ornaments hop in turn."""
    fill = theme.alt_paper if alt else theme.paper
    border = theme.alt if alt else theme.accent
    shade_ = theme.alt_dark if alt else theme.accent_dark
    w, h = width, 60
    top, body = 6, 44
    ic = icon(icon_name, theme, 2) if icon_name else None
    side = (ic.width + 12) if ic else 0
    inset = shape_inset(theme)
    tw = font.width(text, 3)
    pw = inset + side + tw + side + inset
    if pw > w - 150:
        raise ValueError(f"title too long for a {w} pixel ribbon: {text!r} ({pw} pixels). Shorten it or drop the icon.")
    px = (w - pw) // 2
    plate = Image.new("RGBA", (pw, body + 4), (0, 0, 0, 0))
    plate.alpha_composite(shape(theme, pw, body, shade_), (0, 4))
    plate.alpha_composite(shape(theme, pw, body, fill, border, 3), (0, 0))
    font.draw(plate, text, inset + side, (body - font.height(3)) // 2, 3, theme.ink)
    orn = ornament(theme, 1)
    spark = icon("sparkle", theme, 1)
    y = top + body // 2
    lines = ((46, px - 14), (px + pw + 14, w - 46))
    out = []
    for i in range(TITLE_FRAMES):
        t = i / TITLE_FRAMES
        canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(canvas)
        for k, (x0, x1) in enumerate(lines):
            d.rectangle([x0, y - 1, x1, y + 1], fill=border)
            if x1 - x0 >= 64 and (t + 0.5 * k) % 1.0 < 0.42:
                paste(canvas, spark, (x0 + x1) // 2, y, anchor="c")
        canvas.alpha_composite(plate, (px, top))
        if ic:
            canvas.alpha_composite(ic, (px + inset, top + 6 + hop(t, 0.04)))
            canvas.alpha_composite(ic, (px + pw - inset - ic.width, top + 6 + hop(t, 0.54)))
        paste(canvas, orn, 30, y + hop(t, 0.29), anchor="c")
        paste(canvas, orn, w - 30, y + hop(t, 0.79), anchor="c")
        out.append(canvas)
    return out


def divider(theme, width=W):
    """Frames of a line with three ornaments that hop one after the other, to close a part of the page."""
    w, h = width, 44
    big, small = ornament(theme, 2), ornament(theme, 1)
    y = h // 2
    out = []
    for i in range(TITLE_FRAMES):
        t = i / TITLE_FRAMES
        canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(canvas)
        d.rectangle([120, y - 1, w // 2 - 52, y + 1], fill=theme.accent)
        d.rectangle([w // 2 + 52, y - 1, w - 120, y + 1], fill=theme.accent)
        paste(canvas, small, w // 2 - 32, y + 2 + hop(t, 0.05), anchor="c")
        paste(canvas, big, w // 2, y + hop(t, 0.20, height=4), anchor="c")
        paste(canvas, small, w // 2 + 32, y + 2 + hop(t, 0.35), anchor="c")
        out.append(canvas)
    return out


BADGE_HEIGHT = 30
BADGE_SHADE = 3


def badge(label, message, theme, icon_name=None, alt=False):
    """A badge in the shape and the colors of the theme, on a see-through background: an icon and a
    label on a dark field, then a message on paper. `alt` uses the second color of the theme, to tell
    a row of facts from a row of links. Without a message the badge is a button: the icon and the
    label on the dark field. Without a label the icon alone stands before the message."""
    dark = theme.alt_dark if alt else theme.accent_dark
    paper = theme.alt_paper if alt else theme.paper
    h, scale, gap = BADGE_HEIGHT, 2, 7
    pad = {"pill": 11, "plate": 11, "banner": h // 3 + 7}[theme.shape]
    drawn_in = theme
    if icon_name and not os.path.exists(str(icon_name)) and contrast(theme.accent, dark) < 2.2:
        # A built-in icon is drawn in the accent: on a field of the same color it would vanish.
        drawn_in = copy.copy(theme)
        drawn_in.accent, drawn_in.name = theme.accent_light, theme.name + "-on-dark"
    ic = icon(icon_name, drawn_in, 1) if icon_name else None
    left = pad + (ic.width + (6 if label else 0) if ic else 0) + font.width(label, scale) + gap
    right = gap + font.width(message, scale) + pad if message else pad - gap
    w = left + right
    out = Image.new("RGBA", (w, h + BADGE_SHADE), (0, 0, 0, 0))
    out.alpha_composite(shape(theme, w, h, theme.ink[:3] + (255,)), (0, BADGE_SHADE))
    body = shape(theme, w, h, paper, dark, 2)
    field = shape(theme, w, h, dark)
    body.alpha_composite(field.crop((0, 0, left if message else w, h)), (0, 0))
    out.alpha_composite(body, (0, 0))
    x, y = pad, (h - font.height(scale)) // 2
    if ic:
        out.alpha_composite(ic, (x, (h - ic.height) // 2))
        x += ic.width + 6
    font.draw(out, label, x, y, scale, theme.white)
    if message:
        font.draw(out, message, left + gap, y, scale, theme.ink)
    return out


# ------------------------------------------------------------------ scenes

def _open(image, size, crop):
    return load(image, size=size, crop=crop)


def _emblem(logo, theme, size):
    """The logo of the mod on a small frame, or the ornament of the theme when there is no logo.
    logo is a path or an image (a texture, a picture drawn with iso.py)."""
    if logo is None or logo is False or (isinstance(logo, str) and not logo):
        return ornament(theme, size // 16)
    img = (logo if isinstance(logo, Image.Image) else Image.open(logo)).convert("RGBA")
    if img.width > img.height * 2.2:
        # A wordmark would be unreadable in a small square, and the banner already writes the name.
        print("note: the logo is a wide wordmark, the ornament of the theme is used as emblem instead")
        return ornament(theme, size // 16)
    inner = size - 12
    exact = img.width <= 64
    img.thumbnail((inner, inner), Image.NEAREST if exact else Image.LANCZOS)
    if exact:
        k = max(1, inner // max(img.size))
        img = img.resize((img.width * k, img.height * k), Image.NEAREST)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(rounded(size, size, 14, theme.paper, theme.accent_dark, 4))
    out.alpha_composite(img, ((size - img.width) // 2, (size - img.height) // 2))
    return out


def hero_banner(path, theme, plate, title, kicker=None, tagline=None, logo=None, frames=None, region=None, size=(W, 300),
                crop=None, side="left", pips=0, floaters=True, length=68, overlay=None, veil=True, drift_box=None, emblem=True,
                text_width=None):
    """The banner on top of the page: the name of the mod on a veil over a view of the game.

    plate   a still of the game, or any picture: a path or an image (a backdrop of the theme, a scene
    drawn with iso.py). frames is an optional clip (paths) whose `region` (box in banner pixels) keeps
    moving, typically the hero of the mod waving. kicker is the small line above the title (the first
    words of a long name), tagline a small line under it. logo is the mod's logo (path or image);
    without one the ornament of the theme is used. pips draws a row of ornaments that fill up one by
    one. floaters is True, False, or how many ornaments drift; drift_box is the box (banner pixels) they
    drift in, by default between the name and the subject. emblem=False leaves the emblem out and gives
    its room to the name; text_width is the share of the banner the text may take (0.56 on a side,
    0.94 in the center by default).

    side says where the text goes. "left" or "right": on a light veil on that side, with the subject
    of the view on the other. "center": across the whole banner, in light letters and without a veil,
    for a plate that has no subject to keep clear and is dark enough (a backdrop of the theme, a night
    view); a long name stays large there. veil=False drops the veil on a side too, for a dark plate
    whose own drawing must stay visible under the name: the letters are then light.

    The banner is shared by every language of the page: write the name on it, and a tagline only when
    it reads the same in each."""
    w, h = size
    base = _open(plate, size, crop)
    if frames:
        seq = composite_region(footage(frames, crop, size), base, region or (0, 0, w, h), feather=14)
        seq = stabilize(loop_tail(seq, base, 5), 7)
    else:
        seq = [base] * length
    n = len(seq)
    if side not in ("left", "right", "center"):
        raise ValueError(f"side '{side}' (left, right, center)")
    left, centered = side == "left", side == "center"
    bare = centered or not veil                 # no veil: the letters are light, for a dark plate
    veil_img = gradient(int(w * 0.6), h, theme.paper[:3] + (205,), theme.paper[:3] + (0,))
    if not left:
        veil_img = veil_img.transpose(Image.FLIP_LEFT_RIGHT)
    em = _emblem(logo, theme, 112) if emblem else Image.new("RGBA", (1, 1), (0, 0, 0, 0))
    gap = 18 if emblem else -1
    text_w = int(w * (text_width or (0.94 if centered else 0.56))) - 32 - (em.width + gap)
    scale = font.fit(title, text_w, largest=9, smallest=4)
    if font.width(title, scale) > text_w:
        print(f"warning: the title {title!r} is {font.width(title, scale)} pixels wide at its smallest size, for {text_w} available: "
              "put its first words in `kicker`, use side=\"center\", or shorten it")
    main, shade_, glint_color = (theme.paper, theme.ink, theme.accent[:3]) if bare else (theme.accent_dark, theme.white, (255, 255, 255))
    minor = theme.accent_light if bare else theme.ink
    big = text_image(title, scale, main, shadow=shade_)
    letters = Image.new("L", big.size, 0)
    letters.paste(255, (0, 0), text_image(title, scale, (255, 255, 255, 255)).getchannel("A"))
    small = text_image(kicker, 3, minor, shadow=shade_) if kicker else None
    tag = text_image(tagline, 2, minor, shadow=shade_) if tagline else None
    orn2, orn1 = ornament(theme, 2), ornament(theme, 1)
    block_h = (30 if small else 0) + font.height(scale) + (26 if tag else 0) + (40 if pips else 0)
    y = (h - block_h) // 2
    block_w = em.width + gap + max(big.width, small.width if small else 0, tag.width if tag else 0)
    x0 = (w - block_w) // 2 if centered else 32 if left else w - 32 - block_w
    tx = x0 + em.width + gap
    spark = icon("sparkle", theme, 1)
    area = (w * 0.04, h * 0.25, w * 0.96, h * 0.95) if centered else (w * 0.46, h * 0.2, w * 0.7, h * 0.9) if left else (w * 0.3, h * 0.2, w * 0.54, h * 0.9)
    area = drift_box or area
    # Ornaments drift between the name and the subject, not over it; drift_box puts them elsewhere (around a
    # character they belong with, for instance).
    drift = Floaters([orn1, orn2], 9 if floaters is True else int(floaters), area, n, seed=5, rise=70, sway=7) if floaters else None
    out = []
    for i in range(n):
        t = i / n
        canvas = seq[i].convert("RGBA")
        if centered:
            if drift:
                drift.draw(canvas, i, alpha=0.7)        # behind the letters
        elif veil:
            canvas.alpha_composite(veil_img, (0 if left else w - veil_img.width, 0))
        beat = max(math.exp(-((t - 0.06) / 0.035) ** 2), 0.7 * math.exp(-((t - 0.20) / 0.035) ** 2))
        if emblem:
            paste(canvas, em, x0 + em.width / 2, h / 2, scale=1 + 0.08 * beat, anchor="c", smooth=logo is not None)
        yy = y
        if small:
            canvas.alpha_composite(small, (tx, yy - font.ACCENT_ROWS * 3))
            yy += 30
        # A glint runs over the title once per loop.
        lit = big.copy()
        gx = int(lerp(-60, big.width + 60, span(t, 0.30, 0.62)))
        glint = Image.new("L", big.size, 0)
        ImageDraw.Draw(glint).polygon([(gx, 0), (gx + 26, 0), (gx + 6, big.height), (gx - 20, big.height)], fill=150)
        white = Image.new("RGBA", big.size, glint_color + (255,))
        white.putalpha(ImageChops.multiply(glint, letters))
        lit.alpha_composite(white)
        canvas.alpha_composite(lit, (tx, yy - font.ACCENT_ROWS * scale))
        yy += font.height(scale) + 12
        if tag:
            canvas.alpha_composite(tag, (tx + 2, yy - font.ACCENT_ROWS * 2))
            yy += 26
        for k in range(pips):
            start = 0.10 + k * 0.11
            p = span(t, start, start + 0.08)
            if p > 0:
                paste(canvas, orn2, tx + 20 + k * 38, yy + 16, alpha=1 - span(t, 0.94, 1.0), scale=ease_out_back(p, 2.6), anchor="c")
        if drift and not centered:
            drift.draw(canvas, i)
        for sx, sy, ph in ((tx - 8, y - 10, 0.0), (tx + big.width + 6, y + 30, 0.33), (x0 + 20, h - 44, 0.66)):
            twinkle(canvas, spark, sx, sy, i, n / 2, ph)
        if overlay:
            overlay(canvas, i, n)
        frame_border(canvas, theme)
        out.append(canvas.convert("RGB"))
    return finish(out, path)


def clip_scene(path, theme, frames, crop, size=(W, 320), plate=None, boxes=None, feather=14, threshold=7, tail=5,
               intro=0, outro=0, overlay=None):
    """A banner made of footage: the moving area (boxes, in banner pixels) over a still plate.

    plate is a still (path) or None for the first frame of the clip. intro and outro add frames of the
    plate before and after, for an overlay that starts or ends the scene. overlay(canvas, i, n) draws
    on each frame: i counts from 0 over the n frames of the whole banner."""
    shots = footage(frames, crop, size)
    base = _open(plate, size, crop) if plate else shots[0]
    action = composite_region(shots, base, boxes, feather) if boxes else shots
    if tail:
        action = loop_tail(action, base, tail)
    action = stabilize([base] + action, threshold)[1:]
    seq = [base] * intro + action + [base] * outro
    n = len(seq)
    out = []
    for i, f in enumerate(seq):
        canvas = f.convert("RGBA")
        if overlay:
            overlay(canvas, i, n)
        frame_border(canvas, theme)
        out.append(canvas.convert("RGB"))
    return finish(out, path)


def chat_line(text, typed, caret, width, theme, icon_name="chat"):
    """The line a player types, as a chip: icon, the text typed so far, a caret."""
    h = 46
    out = panel(width, h, theme, radius=h // 2, border_width=3, shadow_offset=4)
    out.alpha_composite(icon(icon_name, theme, 2), (12, (h - 32) // 2))
    x = font.draw(out, text[:typed], 52, (h - 21) // 2, 3, theme.ink)
    if caret:
        ImageDraw.Draw(out).rectangle([x + 4, 10, x + 6, h - 12], fill=theme.accent_dark)
    return out


def chat_scene(path, theme, prompt, frames, crop, size=(W, 360), plate=None, boxes=None, watch=None, lead=4, hold=10,
               overlay=None):
    """The player types a line in chat, then the game answers: for mods driven by chat messages or commands.

    The clip should start before the reaction. watch is a box (banner pixels) where the reaction shows:
    the clip is trimmed to start `lead` frames before the first change there."""
    w, h = size
    shots = footage(frames, crop, size)
    base = _open(plate, size, crop) if plate else shots[0]
    start = max(0, first_change(shots, watch) - lead) if watch else 0
    action = composite_region(shots[start:], base, boxes, 16) if boxes else shots[start:]
    action = stabilize([base] + loop_tail(action, base, 5), 7)[1:]
    pill_w = 52 + font.width(prompt, 3) + 30
    px, py = 22, h - 46 - 22
    slide, per_char, sent = 8, 2, 7
    typing = len(prompt) * per_char
    intro = slide + typing + hold + sent
    n = intro + len(action)
    out = []
    for i in range(n):
        canvas = (base if i < intro else action[i - intro]).convert("RGBA")
        if i < slide:
            k = ease_out_back(i / slide, 1.4)
            paste(canvas, chat_line(prompt, 0, True, pill_w, theme), px, py + (1 - k) * 60, alpha=min(1.0, i / 3))
        elif i < slide + typing + hold:
            typed = min(len(prompt), (i - slide) // per_char + 1)
            paste(canvas, chat_line(prompt, typed, (i // 5) % 2 == 0 or typed < len(prompt), pill_w, theme), px, py)
        elif i < intro:
            k = (i - slide - typing - hold + 1) / sent
            paste(canvas, chat_line(prompt, len(prompt), False, pill_w, theme), px, py - ease_in(k) * 26, alpha=1 - k)
        if overlay:
            overlay(canvas, i, n)
        frame_border(canvas, theme)
        out.append(canvas.convert("RGB"))
    return finish(out, path)


def tour_scene(path, theme, stills, targets, crop, size=(W, 480), keep=None, hold=26, move=9, overlay=None):
    """A pointer visits a screen of the mod: it clicks a target, the next still shows, and so on in a loop.

    stills are screenshots of the same screen in its successive states (tabs, pages, steps), targets
    the point (banner pixels) the pointer clicks to reach each still. keep is the box of the screen
    itself: outside it the first still is kept, so the blurred world behind does not flicker."""
    shots = [load(s, size=size, crop=crop) for s in stills]
    base = shots[0]
    if keep:
        mask = feather_mask(size, keep, 4)
        shots = [base] + [Image.composite(s, base, mask) for s in shots[1:]]
    cursor = pointer(theme, 2)
    count = len(shots)
    n = count * hold
    out = []
    for k in range(count):
        (x0, y0), (x1, y1) = targets[k], targets[(k + 1) % count]
        for i in range(hold):
            canvas = shots[k].convert("RGBA")
            if i < hold - move:
                cx, cy = x0, y0
            else:
                t = ease_in_out((i - (hold - move) + 1) / move)
                cx, cy = lerp(x0, x1, t) + math.sin(t * math.pi) * 14, lerp(y0, y1, t)
            click_ring(canvas, x0, y0, i, theme.accent_dark)
            if overlay:
                overlay(canvas, k * hold + i, n)
            canvas.alpha_composite(cursor, (int(cx) + 8, int(cy) + 6))
            frame_border(canvas, theme)
            out.append(canvas.convert("RGB"))
    return finish(out, path)


def swap_scene(path, theme, stills, crop, size=(W, 320), box=None, hold=20, wipe=7, overlay=None):
    """Variants shown one after the other in the same view (skins, colors, upgrades, before and after):
    a bright line sweeps across `box` (banner pixels) and reveals the next still. Without a box the
    whole picture changes. hold is how many frames a still stays, one number or one per still (the
    game for a moment, then the screen of the mod for longer).
    overlay(canvas, i, n, index, progress) gets the index of the variant on show and the progress of the wipe."""
    w, h = size
    shots = [load(s, size=size, crop=crop) for s in stills]
    base = shots[0]
    if box is None:
        box = (0, 0, w, h)
        looks = shots                       # the whole frame changes: nothing to blend into the first still
    else:
        edges = (box[0] - 40 if box[0] <= 0 else box[0], box[1] - 40 if box[1] <= 0 else box[1],
                 box[2] + 40 if box[2] >= w else box[2], box[3] + 40 if box[3] >= h else box[3])
        mask = feather_mask(size, edges, 10)
        looks = [Image.composite(s, base, mask) for s in shots]
        box = (max(0, box[0]), max(0, box[1]), min(w, box[2]), min(h, box[3]))
    count = len(looks)
    holds = list(hold) if isinstance(hold, (list, tuple)) else [hold] * count
    n = sum(holds) + count * wipe
    out = []
    done = 0
    for k in range(count):
        cur, nxt = looks[k], looks[(k + 1) % count]
        for i in range(holds[k] + wipe):
            line = None
            img = cur
            progress = 0.0
            if i >= holds[k]:
                progress = (i - holds[k] + 1) / wipe
                line = int(lerp(box[0], box[2], ease_in_out(progress)))
                img = cur.copy()
                img.paste(nxt.crop((box[0], 0, line, h)), (box[0], 0))
            canvas = img.convert("RGBA")
            if line is not None:
                wipe_line(canvas, line, theme, frame=i)
            if overlay:
                overlay(canvas, done + i, n, k if i < holds[k] else (k + 1) % count, progress)
            frame_border(canvas, theme)
            out.append(canvas.convert("RGB"))
        done += holds[k] + wipe
    return finish(out, path)


def still_scene(path, theme, plate, crop=None, size=(W, 320), length=60, overlay=None, floaters=True, drift_box=None):
    """A banner without footage: a still of the game, or of the mod's own art, brought to life by an
    overlay and a few drifting ornaments (floaters: True, False or how many; drift_box: where, in
    banner pixels). Use it when the game cannot be recorded."""
    w, h = size
    base = _open(plate, size, crop)
    box = drift_box or (w * 0.08, h * 0.25, w * 0.92, h * 0.9)
    drift = Floaters([ornament(theme, 1), ornament(theme, 2)], 8 if floaters is True else int(floaters), box, length, seed=3) if floaters else None
    out = []
    for i in range(length):
        canvas = base.convert("RGBA")
        if drift:
            drift.draw(canvas, i, alpha=0.9)
        if overlay:
            overlay(canvas, i, length)
        frame_border(canvas, theme)
        out.append(canvas.convert("RGB"))
    return finish(out, path)


def showcase_scene(path, theme, sprites, captions=None, size=(W, 320), kind="glow", sprite_size=None, length=72, overlay=None,
                   floaters=True):
    """A banner without any footage, made of the mod's own art, on a background of the theme: the
    sprites stand in a row and hop in turn under a passing sparkle. A sprite is the path of an item
    texture, a tuple of block textures (top, side, front: drawn as a block, one texture is enough), an
    image, or the name of a built-in icon. captions are short texts in capitals, one per sprite,
    shown on a chip under it. Three to six sprites read well.

    For a mod that cannot be recorded, or that shows nothing on screen (server side), this still tells
    what it adds with what a player will see in the inventory."""
    w, h = size
    base = backdrop(theme, size, kind)
    count = len(sprites)
    if sprite_size is None:
        room = min((w - 40) // count - 28, h - (110 if captions else 70))
        sprite_size = max(48, min(160, room) // 16 * 16)
    art = [picture(s, theme, sprite_size) for s in sprites]
    chips = [label(c, theme, 2) for c in captions] if captions else [None] * count
    widest = max([a.width for a in art] + [c.width for c in chips if c])
    if count * (widest + 12) > w - 40:
        raise ValueError(f"{count} sprites of {widest} pixels do not fit in {w}: fewer sprites, a smaller sprite_size or shorter captions")
    xs = [w * (k + 0.5) / count for k in range(count)]
    tall = max(a.height for a in art)
    floor = int(h * (0.46 if captions else 0.52) + tall / 2)
    spark = icon("sparkle", theme, 2)
    shadow = rounded(sprite_size * 2 // 3, 10, 5, (0, 0, 0, 70))
    drift = Floaters([ornament(theme, 1), ornament(theme, 2)], 7, (w * 0.05, h * 0.3, w * 0.95, h * 0.95), length, seed=11) if floaters else None
    out = []
    for i in range(length):
        t = i / length
        canvas = base.convert("RGBA")
        if drift:
            drift.draw(canvas, i, alpha=0.8)
        for k, img in enumerate(art):
            at = k / count
            lift = hop(t, at, height=16, length=min(0.3, 0.9 / count))
            turn = span((t - at) % 1.0, 0.0, min(0.3, 0.9 / count))
            lit = 0 < turn < 1
            paste(canvas, shadow, xs[k], floor + 6, alpha=0.6 if lit else 1.0, scale=0.8 if lit else 1.0, anchor="c")
            canvas.alpha_composite(img, (int(xs[k] - img.width / 2), floor - img.height + lift))
            if lit:
                paste(canvas, spark, xs[k] + img.width * 0.42, floor - img.height + lift + 6, alpha=math.sin(turn * math.pi),
                      scale=0.6 + 0.6 * math.sin(turn * math.pi), anchor="c")
            if chips[k]:
                paste(canvas, chips[k], xs[k], floor + 20 + chips[k].height / 2 + (-2 if lit else 0), anchor="c")
        if overlay:
            overlay(canvas, i, length)
        frame_border(canvas, theme)
        out.append(canvas.convert("RGB"))
    return finish(out, path)
