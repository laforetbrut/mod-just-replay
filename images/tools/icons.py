"""Pixel art icons in the colors of the theme, for the page of a mod that has no texture of its own
for a feature (a setting, a language, a server rule...).

Prefer the mod's own item and block textures when one fits: they are the identity of the mod. This
set fills the gaps, and it also provides the ornaments of the titles.

    python icons.py sheet theme.json sheet.png      # every icon, to pick from
    from icons import icon; icon("gear", theme, scale=2)

Shapes are drawn four times too large, reduced to 16 x 16 with a hard edge, then outlined like
stickers. Small details are placed pixel by pixel afterwards.

Author: vyrriox
"""
import math
import os
import sys

from PIL import Image, ImageDraw

S = 4  # drawing scale
N = 16 * S


class Icon:
    def __init__(self):
        self.big = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.big)
        self.small = None

    # ---- shapes, in 16 pixel coordinates (floats allowed)
    def ellipse(self, x0, y0, x1, y1, color):
        self.d.ellipse([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], fill=color)
        return self

    def circle(self, cx, cy, r, color):
        return self.ellipse(cx - r, cy - r, cx + r, cy + r, color)

    def rect(self, x0, y0, x1, y1, color, radius=0):
        if radius:
            self.d.rounded_rectangle([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], radius=radius * S, fill=color)
        else:
            self.d.rectangle([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], fill=color)
        return self

    def poly(self, points, color):
        self.d.polygon([(x * S, y * S) for x, y in points], fill=color)
        return self

    def line(self, points, color, width=1.5):
        self.d.line([(x * S, y * S) for x, y in points], fill=color, width=int(width * S), joint="curve")
        for x, y in (points[0], points[-1]):
            r = width * S / 2
            self.d.ellipse([x * S - r, y * S - r, x * S + r, y * S + r], fill=color)
        return self

    def arc(self, cx, cy, r, start, end, color, width=1.5):
        self.d.arc([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], start, end, fill=color, width=int(width * S))
        return self

    def heart(self, cx, cy, size, color):
        r = size / 4
        self.circle(cx - r, cy - r * 0.4, r * 1.05, color)
        self.circle(cx + r, cy - r * 0.4, r * 1.05, color)
        self.poly([(cx - size / 2, cy - r * 0.1), (cx + size / 2, cy - r * 0.1), (cx, cy + size * 0.55)], color)
        return self

    def star(self, cx, cy, r, color, points=5, inner=0.45, rotation=-90):
        pts = []
        for i in range(points * 2):
            rr = r if i % 2 == 0 else r * inner
            a = math.radians(rotation + i * 180 / points)
            pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
        return self.poly(pts, color)

    def hole(self, x0, y0, x1, y1):
        """Clears an ellipse, to cut a shape out of what is already drawn."""
        self.d.ellipse([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], fill=(0, 0, 0, 0))
        return self

    # ---- finishing
    def finish(self, outline):
        img = self.big.convert("RGBa").resize((16, 16), Image.BOX).convert("RGBA")
        px = img.load()
        for y in range(16):
            for x in range(16):
                r, g, b, a = px[x, y]
                px[x, y] = (r, g, b, 255) if a >= 100 else (0, 0, 0, 0)
        if outline:
            solid = {(x, y) for y in range(16) for x in range(16) if px[x, y][3]}
            for y in range(16):
                for x in range(16):
                    if (x, y) not in solid and any((x + dx, y + dy) in solid for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        px[x, y] = outline
        self.small = img
        return self

    def pixels(self, color, *coords):
        px = self.small.load()
        for x, y in coords:
            if 0 <= x < 16 and 0 <= y < 16:
                px[x, y] = color
        return self


# ------------------------------------------------------------------ the set
# Every function takes the theme and returns a finished Icon. A = accent, B = alt, the rest is fixed
# so that a lock stays gold and a leaf stays green whatever the theme.

GREEN = (140, 214, 120, 255)
GREEN_DARK = (88, 168, 92, 255)
SKY = (143, 211, 255, 255)
PEACH = (255, 179, 138, 255)
FACE = (255, 226, 170, 255)
BROWN_DARK = (112, 70, 46, 255)


def heart(t):
    return Icon().heart(8, 8.5, 13, t.accent).finish(t.ink).pixels(t.white, (4, 5), (4, 6), (5, 5))


def star(t):
    return Icon().star(8, 8.6, 7.2, t.gold).finish(t.ink).pixels(t.white, (7, 5), (7, 6))


def sparkle(t):
    return Icon().star(8, 8, 7, t.gold, points=4, inner=0.28, rotation=-90).finish(t.ink).pixels(t.white, (8, 7), (8, 8))


def gem(t):
    i = Icon().poly([(4.5, 2.5), (11.5, 2.5), (14.5, 6.5), (8, 14.5), (1.5, 6.5)], t.accent)
    i.poly([(4.5, 2.5), (11.5, 2.5), (10, 6.5), (6, 6.5)], t.accent_light).poly([(10, 6.5), (14.5, 6.5), (8, 14.5)], t.accent_dark)
    return i.finish(t.ink).pixels(t.white, (5, 4), (6, 4))


def gear(t):
    i = Icon().star(8, 8, 7, t.alt, points=8, inner=0.72, rotation=0).circle(8, 8, 4.6, t.alt)
    return i.finish(t.ink).pixels(t.paper, (7, 6), (8, 6), (6, 7), (7, 7), (8, 7), (9, 7), (6, 8), (7, 8), (8, 8), (9, 8), (7, 9), (8, 9))


def leaf(t):
    i = Icon().poly([(13.5, 2.5), (11.5, 11), (5.5, 12.5), (3.5, 8.5), (7, 3.5)], GREEN).line([(3, 13.5), (9, 7.5)], GREEN_DARK, 1.0)
    return i.finish(t.ink).pixels(t.white, (10, 4), (11, 4))


def flame(t):
    i = Icon().poly([(8, 1.5), (11.5, 6), (12.5, 10), (10.5, 14), (5.5, 14), (3.5, 10), (5.5, 6.5), (6.5, 8)], PEACH)
    i.poly([(8, 7), (10, 10.5), (9, 13.5), (7, 13.5), (6, 10.5)], t.gold)
    return i.finish(t.ink)


def bolt(t):
    return Icon().poly([(9.5, 1), (3, 9), (7.5, 9), (6, 15), (13, 6.5), (8.5, 6.5), (11, 1)], t.gold).finish(t.ink)


def drop(t):
    return Icon().circle(8, 10, 5, SKY).poly([(3.4, 9.2), (12.6, 9.2), (8, 1)], SKY).finish(t.ink).pixels(t.white, (6, 8), (6, 9), (5, 10))


def cube(t):
    i = Icon().poly([(8, 1.5), (14.5, 4.5), (8, 7.5), (1.5, 4.5)], t.accent_light)
    i.poly([(1.5, 4.5), (8, 7.5), (8, 14.5), (1.5, 11.5)], t.accent).poly([(14.5, 4.5), (8, 7.5), (8, 14.5), (14.5, 11.5)], t.accent_dark)
    return i.finish(t.ink)


def chat(t):
    i = Icon().rect(1.5, 2, 14.5, 11.5, t.alt, 3).poly([(4, 11), (8, 11), (4, 14.5)], t.alt)
    return i.finish(t.ink).pixels(t.ink, (8, 4), (8, 5), (8, 6), (8, 7), (8, 9))


def hammer(t):
    return Icon().line([(3, 14), (12, 5)], t.brown, 2).poly([(9, 1.5), (14.5, 7), (12.5, 9), (7, 3.5)], t.silver).finish(t.ink)


def pick(t):
    i = Icon().line([(3, 14), (11, 6)], t.brown, 1.8).arc(6.5, 9.5, 7.5, 245, 385, t.silver, 2.2)
    return i.finish(t.ink)


def sword(t):
    i = Icon().line([(12.5, 2.5), (6, 9)], t.silver, 2.2).line([(4, 8), (8, 12)], t.gold, 1.6).line([(5, 11), (2.5, 13.5)], t.brown, 1.8)
    return i.finish(t.ink).pixels(t.white, (11, 3), (10, 4))


def shield(t):
    i = Icon().poly([(8, 1.5), (13.5, 3.5), (13.5, 8), (8, 14.5), (2.5, 8), (2.5, 3.5)], t.alt)
    return i.finish(t.ink).pixels(t.paper, (8, 5), (8, 6), (8, 7), (8, 8), (8, 9), (6, 7), (7, 7), (9, 7), (10, 7))


def bag(t):
    i = Icon().arc(8, 5.5, 3, 180, 360, t.brown, 1.4).rect(2.5, 5.5, 13.5, 14.5, t.accent, 2).rect(5, 9, 11, 12.5, t.accent_light, 1)
    return i.finish(t.ink).pixels(t.gold, (7, 8), (8, 8))


def chest(t):
    i = Icon().rect(1.5, 4, 14.5, 14, t.brown, 1).rect(1.5, 4, 14.5, 7.5, BROWN_DARK, 1)
    return i.finish(t.ink).pixels(t.gold, (7, 7), (8, 7), (7, 8), (8, 8), (7, 9), (8, 9)).pixels(t.ink, (7, 8))


def home(t):
    i = Icon().poly([(1.5, 8), (8, 1.5), (14.5, 8)], t.accent).rect(3.5, 7, 12.5, 14.5, t.paper).rect(6.5, 10, 9.5, 14.5, t.brown)
    return i.finish(t.ink)


def pin(t):
    i = Icon().circle(8, 6.5, 5.2, t.accent).poly([(3.6, 8), (12.4, 8), (8, 15)], t.accent)
    return i.finish(t.ink).pixels(t.paper, (7, 5), (8, 5), (7, 6), (8, 6), (6, 6), (9, 6), (7, 7), (8, 7))


def globe(t):
    i = Icon().circle(8, 8, 6.6, SKY)
    return i.finish(t.ink).pixels(GREEN_DARK, (5, 4), (6, 4), (4, 5), (5, 5), (6, 5), (7, 5), (5, 6), (6, 6), (6, 7), (10, 7), (11, 7), (9, 8), (10, 8),
                                  (11, 8), (12, 8), (10, 9), (11, 9), (10, 10), (5, 10), (6, 10), (6, 11))


def users(t):
    i = Icon().circle(5.5, 5.5, 2.8, FACE).ellipse(1.5, 8.5, 9.5, 15.5, t.alt).circle(11, 6.5, 2.4, FACE).ellipse(7.5, 9.5, 14.5, 15.5, t.accent)
    return i.finish(t.ink)


def server(t):
    i = Icon().rect(2, 2, 14, 6, t.alt, 1).rect(2, 7, 14, 11, t.alt, 1).rect(2, 12, 14, 14.5, t.alt_dark, 1)
    return i.finish(t.ink).pixels(t.good, (4, 4), (4, 9)).pixels(t.ink, (7, 4), (8, 4), (9, 4), (10, 4), (11, 4), (7, 9), (8, 9), (9, 9), (10, 9), (11, 9))


def lock(t):
    i = Icon().arc(8, 6.5, 3.6, 180, 360, t.silver, 1.8).line([(4.4, 6.5), (4.4, 8)], t.silver, 1.8).line([(11.6, 6.5), (11.6, 8)], t.silver, 1.8)
    i.rect(2.5, 7.5, 13.5, 14.5, t.gold, 1)
    return i.finish(t.ink).pixels(BROWN_DARK, (8, 10), (8, 11))


def key(t):
    i = Icon().circle(5, 5, 3.4, t.gold).line([(7, 7), (13, 13)], t.gold, 1.8).line([(11, 11), (12.5, 9.5)], t.gold, 1.4)
    return i.finish(t.ink).pixels(t.ink, (4, 4), (5, 4), (4, 5))


def book(t):
    i = Icon().rect(2.5, 2, 13.5, 14, t.accent, 1).rect(4.5, 2, 13.5, 12, t.paper)
    return i.finish(t.ink).pixels(t.ink, (6, 5), (7, 5), (8, 5), (9, 5), (10, 5), (11, 5), (6, 7), (7, 7), (8, 7), (9, 7), (6, 9), (7, 9), (8, 9), (9, 9), (10, 9))


def check(t):
    return Icon().line([(3, 8.5), (6.5, 12), (13, 4)], t.good, 2.6).finish(t.ink)


def cross(t):
    return Icon().line([(4, 4), (12, 12)], t.red, 2.4).line([(12, 4), (4, 12)], t.red, 2.4).finish(t.ink)


def plus(t):
    return Icon().rect(6.5, 2.5, 9.5, 13.5, t.good, 1).rect(2.5, 6.5, 13.5, 9.5, t.good, 1).finish(t.ink)


def question(t):
    i = Icon().circle(8, 8, 6.6, t.alt)
    return i.finish(t.ink).pixels(t.white, (6, 5), (7, 4), (8, 4), (9, 4), (10, 5), (10, 6), (9, 7), (8, 8), (8, 9), (8, 11))


def info(t):
    i = Icon().circle(8, 8, 6.6, SKY)
    return i.finish(t.ink).pixels(t.white, (7, 4), (8, 4), (7, 6), (8, 6), (7, 7), (8, 7), (7, 8), (8, 8), (7, 9), (8, 9), (7, 10), (8, 10), (6, 11), (7, 11), (8, 11), (9, 11))


def warning(t):
    i = Icon().poly([(8, 1.5), (14.8, 14), (1.2, 14)], t.gold)
    return i.finish(t.ink).pixels(t.ink, (8, 6), (8, 7), (8, 8), (8, 9), (8, 11))


def clock(t):
    i = Icon().circle(8, 8, 6.6, t.paper)
    return i.finish(t.ink).pixels(t.ink, (8, 4), (8, 5), (8, 6), (8, 7), (8, 8), (9, 8), (10, 8), (11, 9)).pixels(t.accent, (8, 2), (13, 8), (8, 13), (2, 8))


def eye(t):
    i = Icon().ellipse(1.5, 4.5, 14.5, 11.5, t.white).circle(8, 8, 2.6, t.alt)
    return i.finish(t.ink).pixels(t.ink, (8, 8), (7, 8), (8, 7), (7, 7)).pixels(t.white, (9, 7))


def brush(t):
    i = Icon().line([(13, 3), (7.5, 8.5)], t.brown, 1.8).poly([(7.5, 7.5), (9, 9), (5.5, 13.5), (2.5, 13.5), (3.5, 10.5)], t.accent)
    return i.finish(t.ink)


def shirt(t):
    i = Icon().poly([(5, 2), (11, 2), (14.5, 5), (12.5, 7.5), (11.5, 6.5), (11.5, 14), (4.5, 14), (4.5, 6.5), (3.5, 7.5), (1.5, 5)], t.accent)
    return i.finish(t.ink).pixels(t.paper, (7, 3), (8, 3), (7, 4), (8, 4))


def note(t):
    i = Icon().line([(6.5, 12), (6.5, 3.5), (12.5, 2.5), (12.5, 10.5)], t.alt_dark, 1.4).circle(5, 12, 2.2, t.alt).circle(11, 10.5, 2.2, t.alt)
    return i.finish(t.ink)


def download(t):
    i = Icon().poly([(8, 11.5), (13, 6), (10, 6), (10, 1.5), (6, 1.5), (6, 6), (3, 6)], t.good).rect(2, 12.5, 14, 14.5, t.alt)
    return i.finish(t.ink)


def upload(t):
    i = Icon().poly([(8, 1.5), (13, 7), (10, 7), (10, 11), (6, 11), (6, 7), (3, 7)], t.good).rect(2, 12.5, 14, 14.5, t.alt)
    return i.finish(t.ink)


def play(t):
    return Icon().poly([(4.5, 2.5), (4.5, 13.5), (13, 8)], t.accent).finish(t.ink)


def refresh(t):
    i = Icon().arc(8, 8, 5, 40, 320, SKY, 2.2).poly([(11, 1.5), (14.5, 5.5), (10, 6.5)], SKY)
    return i.finish(t.ink)


def pencil(t):
    i = Icon().poly([(3, 13), (3.4, 10), (11, 2.4), (13.6, 5), (6, 12.6)], t.gold).poly([(11, 2.4), (13.6, 5), (14.6, 4), (12, 1.4)], t.accent)
    return i.finish(t.ink).pixels(t.ink, (3, 13), (4, 12))


def wand(t):
    i = Icon().line([(3, 13.5), (10, 6.5)], t.brown, 1.8).star(11.5, 4.5, 3.6, t.gold, points=4, inner=0.35, rotation=-90)
    return i.finish(t.ink)


def potion(t):
    i = Icon().rect(6.5, 1.5, 9.5, 5.5, t.silver).circle(8, 10, 4.8, t.accent)
    return i.finish(t.ink).pixels(t.brown, (7, 1), (8, 1)).pixels(t.white, (6, 8), (6, 9), (5, 10)).pixels(t.accent_light, (9, 11), (10, 10))


def keycap(t):
    i = Icon().rect(2, 3, 14, 14, t.alt_dark, 2).rect(3, 3, 13, 12, t.alt_paper, 2)
    return i.finish(t.ink).pixels(t.ink, (6, 5), (6, 6), (6, 7), (6, 8), (6, 9), (7, 7), (8, 6), (9, 5), (8, 8), (9, 9))


def puzzle(t):
    i = Icon().rect(2.5, 6, 12, 14.5, t.alt).circle(7.2, 4.6, 2.3, t.alt).hole(10.2, 8.4, 14.2, 12.4)
    return i.finish(t.ink).pixels(t.alt_paper, (4, 8), (5, 8), (4, 9))


def rocket(t):
    i = Icon().poly([(12.5, 1.5), (14.5, 3.5), (11, 10), (6, 5)], t.silver).poly([(6, 5), (3, 6.5), (5.5, 8.5)], t.accent).poly([(11, 10), (9.5, 13), (7.5, 10.5)], t.accent)
    i.poly([(5.5, 9), (7, 10.5), (3, 13)], t.gold)
    return i.finish(t.ink).pixels(SKY, (11, 4), (11, 5), (10, 5))


def gift(t):
    i = Icon().rect(2.5, 6, 13.5, 14.5, t.accent, 1).rect(1.5, 4.5, 14.5, 7.5, t.accent_light, 1).rect(7, 4.5, 9, 14.5, t.gold)
    return i.finish(t.ink).pixels(t.gold, (6, 3), (5, 2), (9, 3), (10, 2), (7, 3), (8, 3))


def food(t):
    i = Icon().ellipse(1.5, 1.5, 11, 11, (196, 120, 76, 255)).line([(9, 9), (13, 13)], t.white, 2).circle(13.6, 12.2, 1.4, t.white).circle(12.2, 13.6, 1.4, t.white)
    return i.finish(t.ink).pixels((226, 160, 110, 255), (4, 4), (5, 4), (4, 5))


def smile(t):
    i = Icon().circle(8, 8.5, 6.6, FACE)
    return i.finish(t.ink).pixels(t.ink, (4, 7), (5, 6), (6, 7), (9, 7), (10, 6), (11, 7), (6, 10), (7, 11), (8, 11), (9, 10)).pixels((255, 150, 170, 255), (3, 9), (4, 9), (11, 9), (12, 9))


def moon(t):
    i = Icon().circle(8, 8, 6.4, t.gold).hole(5.2, 0.6, 16.2, 11.6)
    return i.finish(t.ink)


def sun(t):
    i = Icon().star(8, 8, 7.4, t.gold, points=8, inner=0.6, rotation=0).circle(8, 8, 4, PEACH)
    return i.finish(t.ink)


def paw(t):
    i = Icon().ellipse(4.5, 8, 11.5, 14, t.accent)
    for cx, cy in ((3.5, 6.5), (6.5, 3.5), (9.5, 3.5), (12.5, 6.5)):
        i.circle(cx, cy, 1.7, t.accent)
    return i.finish(t.ink)


def skull(t):
    i = Icon().circle(8, 7, 5.6, t.paper).rect(5, 9.5, 11, 14, t.paper, 1)
    return i.finish(t.ink).pixels(t.ink, (5, 6), (6, 6), (5, 7), (6, 7), (9, 6), (10, 6), (9, 7), (10, 7), (8, 9), (6, 12), (8, 12), (10, 12))


def flag(t):
    i = Icon().line([(3.5, 2), (3.5, 14.5)], t.brown, 1.4).poly([(4.5, 2.5), (13.5, 4.5), (4.5, 9)], t.accent)
    return i.finish(t.ink)


def camera(t):
    i = Icon().rect(1.5, 4.5, 14.5, 13.5, t.alt, 2).rect(5, 2.5, 9, 5, t.alt_dark, 1).circle(8, 9, 3, t.paper)
    return i.finish(t.ink).pixels(t.ink, (8, 9), (7, 9), (8, 8))


ICONS = {f.__name__: f for f in (
    heart, star, sparkle, gem, gear, leaf, flame, bolt, drop, cube, chat, hammer, pick, sword, shield, bag, chest, home, pin, globe, users,
    server, lock, key, book, check, cross, plus, question, info, warning, clock, eye, brush, shirt, note, download, upload, play, refresh,
    pencil, wand, potion, keycap, puzzle, rocket, gift, food, smile, moon, sun, paw, skull, flag, camera)}

_cache = {}


def icon(name, theme, scale=1):
    """An icon of the set as an RGBA image, 16 * scale pixels wide."""
    key = (name, theme.name, theme.hex("accent"), scale)
    if key not in _cache:
        if name not in ICONS:
            raise KeyError(f"no icon named {name!r}; the set has: {', '.join(sorted(ICONS))}")
        img = ICONS[name](theme).small
        if scale != 1:
            img = img.resize((16 * scale, 16 * scale), Image.NEAREST)
        _cache[key] = img
    return _cache[key]


def sheet(theme, path, scale=3):
    """Every icon with its name, on the dark background of CurseForge and on white."""
    names = sorted(ICONS)
    cols = 11
    cw, ch = 16 * scale + 44, 16 * scale + 22
    rows = (len(names) + cols - 1) // cols
    img = Image.new("RGB", (cols * cw, rows * ch * 2), (13, 13, 13))
    img.paste((255, 255, 255), (0, rows * ch, cols * cw, rows * ch * 2))
    d = ImageDraw.Draw(img)
    for half, text in ((0, (220, 220, 220)), (1, (40, 40, 40))):
        for k, name in enumerate(names):
            x, y = (k % cols) * cw, (k // cols) * ch + half * rows * ch
            ic = icon(name, theme, scale)
            img.paste(ic, (x + 22, y + 2), ic)
            d.text((x + 2, y + 16 * scale + 6), name, fill=text)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "sheet":
        sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
        from theme import Theme
        sheet(Theme.load(sys.argv[2]), sys.argv[3])
        print(f"{len(ICONS)} icons -> {sys.argv[3]}")
    else:
        print(__doc__)
