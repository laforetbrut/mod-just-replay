"""The theme of a mod page: a handful of colors, an ornament and a shape, shared by every image
and by the text of the page.

A theme file only needs an accent color; the rest is derived. Usage:

    python theme.py extract <image or folder>... [--out theme.json] [--swatch swatch.png]
    python theme.py new --accent "#b3261e" [--ornament skull] [--shape banner] [--out theme.json] [--swatch swatch.png]
    python theme.py show theme.json [--swatch swatch.png]

`extract` reads the colors that the mod itself uses (its logo, its GUI and item textures) and
proposes a theme. Look at the swatch, then edit theme.json by hand: the proposal is a start, the
choice is a design decision. `new` starts a theme from a color chosen by hand, for a mod that has
no art of its own (take the color it uses in game: its chat messages, its boss bar, its particles).

Author: vyrriox
"""
import argparse
import colorsys
import json
import os
import sys

CURSEFORGE_BG = (13, 13, 13)
CURSEFORGE_TEXT = (153, 153, 153)

DEFAULTS = {
    "name": "mod",
    "accent": "#f77fb2",
    "ornament": "star",       # star, heart, sparkle, gem, gear, leaf, flame, bolt, drop, cube, or the path of a small image
    "shape": "pill",          # pill (round ends), plate (cut corners), banner (forked ends)
}
# Keys that can be given, in the order they are documented.
KEYS = ["name", "accent", "accent_dark", "accent_light", "paper", "ink", "alt", "alt_dark", "alt_paper", "good", "gold", "red",
        "page_accent", "page_light", "ornament", "shape"]


def rgba(value):
    """'#rrggbb', '#rgb' or a tuple -> (r, g, b, 255)."""
    if isinstance(value, (tuple, list)):
        return tuple(int(v) for v in value[:3]) + (255,)
    v = value.strip().lstrip("#")
    if len(v) == 3:
        v = "".join(c * 2 for c in v)
    return (int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16), 255)


def to_hex(color):
    return "#%02x%02x%02x" % tuple(int(c) for c in color[:3])


def _hls(color):
    return colorsys.rgb_to_hls(color[0] / 255, color[1] / 255, color[2] / 255)


def _from_hls(h, lightness, s):
    r, g, b = colorsys.hls_to_rgb(h % 1.0, min(1, max(0, lightness)), min(1, max(0, s)))
    return (int(round(r * 255)), int(round(g * 255)), int(round(b * 255)), 255)


def shade(color, lightness=None, saturation=None, hue_shift=0.0):
    """The same hue at another lightness (0..1) or saturation (0..1), optionally with the hue moved."""
    h, li, s = _hls(color)
    return _from_hls(h + hue_shift, li if lightness is None else lightness, s if saturation is None else saturation)


def luminance(color):
    def channel(c):
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(c) for c in color[:3])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    """WCAG contrast ratio between two colors (1 to 21)."""
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def readable_on(color, background, minimum=4.5):
    """The color, lightened or darkened step by step until it can be read on the background."""
    h, li, s = _hls(color)
    step = 0.02 if luminance(background) < 0.5 else -0.02
    out = color
    for _ in range(50):
        if contrast(out, background) >= minimum:
            break
        li += step
        out = _from_hls(h, li, s)
    return out


def _second(accent):
    """A second color that sets the accent off: a soft blue for warm accents, otherwise the hue moved
    towards violet. It colors the language labels and some icons; override it when the mod has its own."""
    h, li, s = _hls(accent)
    if h < 0.2 or h > 0.97:
        return _from_hls(h + 0.5, 0.78, 0.5)
    shift = max(-0.19, min(0.19, 0.72 - h))
    if abs(shift) < 0.08:
        shift = 0.16
    return _from_hls(h + shift, 0.80, min(1.0, s + 0.1))


class Theme:
    """Colors as (r, g, b, 255) tuples, plus `ornament` and `shape`."""

    def __init__(self, data=None):
        self.given = dict(data or {})
        data = dict(DEFAULTS, **(data or {}))
        self.name = data["name"]
        self.ornament = data["ornament"]
        self.shape = data["shape"]
        accent = rgba(data["accent"])
        h, li, s = _hls(accent)
        derived = {
            "accent": accent,
            "accent_dark": shade(accent, max(0.18, li - 0.2), min(s, 0.72)),       # darker and calmer: a saturated accent stays loud otherwise
            "accent_light": shade(accent, min(0.90, li + 0.14)),
            "paper": shade(accent, 0.975, min(1.0, s)),
            "ink": shade(accent, 0.26, min(s, 0.42)),
            "alt": _second(accent),
            "good": (126, 224, 195, 255),
            "gold": (247, 196, 72, 255),
            "red": (235, 72, 96, 255),
            "page_light": (229, 229, 229, 255),
        }
        for key, value in derived.items():
            setattr(self, key, rgba(data[key]) if key in data else value)
        # White labels sit on the dark shades (badges, language labels): derived ones are dark enough for them.
        if "accent_dark" not in data:
            self.accent_dark = readable_on(self.accent_dark, (255, 255, 255, 255), 3.5)
        self.alt_dark = rgba(data["alt_dark"]) if "alt_dark" in data else readable_on(
            shade(self.alt, max(0.2, _hls(self.alt)[1] - 0.14)), (255, 255, 255, 255), 3.5)
        self.alt_paper = rgba(data["alt_paper"]) if "alt_paper" in data else shade(self.alt, 0.945)
        # The color of names and links on the page itself: it must be readable on CurseForge's dark background.
        self.page_accent = rgba(data["page_accent"]) if "page_accent" in data else readable_on(self.accent, CURSEFORGE_BG)
        self.white = (255, 255, 255, 255)
        self.silver = (214, 222, 235, 255)
        self.brown = (160, 104, 70, 255)

    @staticmethod
    def load(path):
        if not path:
            return Theme()
        with open(path, encoding="utf-8") as f:
            theme = Theme(json.load(f))
        # An ornament given as a file is looked for next to the theme, then from the repository root, then from here.
        if "." in theme.ornament or "/" in theme.ornament:
            folder = os.path.dirname(os.path.abspath(path))
            for base in (folder, os.path.dirname(os.path.dirname(folder)), os.getcwd()):
                candidate = os.path.join(base, theme.ornament)
                if os.path.exists(candidate):
                    theme.ornament = candidate
                    break
        return theme

    def to_dict(self):
        out = {"name": self.name}
        for key in KEYS[1:-2]:
            out[key] = to_hex(getattr(self, key))
        out["ornament"] = self.given.get("ornament", self.ornament)        # as written in the file, not the resolved path
        out["shape"] = self.shape
        return out

    def save(self, path):
        """Writes what was chosen, not what is derived from it: change the accent in the file and every
        other color follows. `show` prints the full set."""
        out = {"name": self.name, "accent": to_hex(self.accent)}
        for key in KEYS[2:-2]:
            if key in self.given:
                out[key] = to_hex(getattr(self, key))
        out["ornament"] = self.given.get("ornament", self.ornament)
        out["shape"] = self.shape
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(out, f, indent=2)
            f.write("\n")

    def hex(self, key):
        return to_hex(getattr(self, key))

    def warnings(self):
        out = []
        if contrast(self.ink, self.paper) < 7:
            out.append(f"ink on paper has a contrast of {contrast(self.ink, self.paper):.1f}: titles will be hard to read (aim for 7 or more)")
        if contrast(self.page_accent, CURSEFORGE_BG) < 4.5:
            out.append(f"page_accent on the CurseForge background has a contrast of {contrast(self.page_accent, CURSEFORGE_BG):.1f} (need 4.5)")
        if contrast(self.accent, self.paper) < 1.6:
            out.append("accent and paper are too close: borders will not show")
        if luminance(self.paper) < 0.5:
            out.append("paper is dark: titles, icons and the veil of the banners are drawn for a light paper with a dark ink. For a dark mood "
                       "keep a light paper (bone, parchment) and let the banners carry the darkness (backdrop 'glow' or 'tiles')")
        if contrast(self.ink, self.alt_paper) < 5:
            out.append(f"ink on alt_paper has a contrast of {contrast(self.ink, self.alt_paper):.1f}: the language labels will be hard to read")
        if luminance(self.alt_dark) >= luminance(self.alt):
            out.append("alt_dark is not darker than alt: the fact badges and the language labels lose their shade")
        for key in ("accent_dark", "alt_dark"):
            if contrast((255, 255, 255), getattr(self, key)) < 3:
                out.append(f"white on {key} has a contrast of {contrast((255, 255, 255), getattr(self, key)):.1f}: the labels of the badges "
                           f"drawn on it will be hard to read; darken {key}")
        if os.path.isfile(str(self.ornament)):
            from PIL import Image
            img = Image.open(self.ornament).convert("RGBA")
            import numpy as np
            arr = np.asarray(img).reshape(-1, 4)
            pixels = [tuple(int(v) for v in p[:3]) for p in arr if p[3] > 128]
            # The outline of a sticker is dark on purpose: judge the drawing inside it.
            inside = [p for p in pixels if luminance(p) > 0.04]
            pixels = inside or pixels
            if pixels:
                mean = tuple(sum(p[k] for p in pixels) / len(pixels) for k in range(3))
                if contrast(mean, CURSEFORGE_BG) < 3:
                    out.append("the ornament is dark: it stands next to the titles on CurseForge's near black background and will not show. "
                               "Pick a lighter texture of the mod, or give it a light outline (iso.outline)")
            if img.width != img.height or img.width not in (16, 32):
                out.append(f"the ornament is {img.width} x {img.height}: it is drawn 16 pixels wide next to the titles, so a 16 pixel square "
                           "texture reads best. Reduce a larger picture first (iso.fit, then iso.flat)")
        return out


# ------------------------------------------------------------------ extraction

def _images(paths):
    exts = (".png", ".jpg", ".jpeg", ".gif", ".webp")
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for name in sorted(files):
                    if name.lower().endswith(exts):
                        yield os.path.join(root, name)
        elif os.path.exists(p):
            yield p
        else:
            print(f"not found: {p}", file=sys.stderr)


def palette(paths, count=10):
    """The main colors of the images: a list of ((r, g, b), share, vividness), most vivid first."""
    from PIL import Image
    import numpy as np
    chunks = []
    for path in _images(paths):
        try:
            img = Image.open(path).convert("RGBA")
        except OSError:
            continue
        img.thumbnail((96, 96), Image.NEAREST)
        arr = np.asarray(img).reshape(-1, 4)
        arr = arr[arr[:, 3] > 200][:, :3]
        if len(arr):
            # Every image weighs the same, whatever its size.
            idx = np.linspace(0, len(arr) - 1, min(len(arr), 1500)).astype(int)
            chunks.append(arr[idx])
    if not chunks:
        return []
    pixels = np.concatenate(chunks)
    strip = Image.fromarray(pixels.reshape(1, -1, 3).astype("uint8"), "RGB")
    quant = strip.quantize(colors=count, method=Image.MEDIANCUT)
    pal = quant.getpalette()[:count * 3]
    counts = sorted(quant.getcolors(), reverse=True)
    total = sum(c for c, _ in counts)
    out = []
    for c, index in counts:
        color = tuple(pal[index * 3:index * 3 + 3])
        h, li, s = _hls(color)
        # Vivid, mid-light colors make good accents; greys and near black or white do not.
        vivid = s * (1 - abs(li - 0.58) * 1.6)
        out.append((color, c / total, vivid))
    return sorted(out, key=lambda t: t[2] * (0.35 + t[1]), reverse=True)


def propose(paths):
    """A theme built from the colors of the images, and the palette it came from."""
    pal = palette(paths)
    data = {}
    if pal:
        accent = pal[0][0]
        h0 = _hls(accent)[0]
        data["accent"] = to_hex(accent)
        for color, _, vivid in pal[1:]:
            dh = abs(_hls(color)[0] - h0)
            if vivid > 0.12 and min(dh, 1 - dh) > 0.10:
                data["alt"] = to_hex(shade(color, min(0.82, max(0.62, _hls(color)[1]))))
                break
        else:
            # No second hue: a mod in one family of colors (yellow and browns) takes as its second
            # color the one that differs most in lightness, rather than a hue it never uses.
            l0 = _hls(accent)[1]
            for color, share, vivid in sorted(pal[1:], key=lambda t: -abs(_hls(t[0])[1] - l0)):
                h, li, s = _hls(color)
                # Near white and near black are the background of a texture, not a color of the mod.
                if s > 0.15 and 0.15 < li < 0.8 and abs(li - l0) > 0.2:
                    data["alt"] = to_hex(shade(color, min(0.82, max(0.62, li + 0.25))))
                    data["alt_dark"] = to_hex(shade(color, min(li, 0.42)))
                    break
    return Theme(data), pal


def swatch(theme, path, pal=None):
    """A picture of the theme: its colors, the palette it came from, and a sample title on dark and light."""
    from PIL import Image, ImageDraw
    w, h = 820, 300 if pal else 236
    img = Image.new("RGB", (w, h), (32, 32, 36))
    d = ImageDraw.Draw(img)
    x = 10
    for key in KEYS[1:-2]:
        color = getattr(theme, key)
        d.rectangle([x, 10, x + 56, 66], fill=color[:3])
        d.text((x, 70), key, fill=(230, 230, 230))
        d.text((x, 82), to_hex(color), fill=(170, 170, 170))
        x += 62
        if x > w - 60:
            break
    # Sample: the name of a feature as it shows on CurseForge, and a title pill.
    d.rectangle([10, 104, 400, 226], fill=CURSEFORGE_BG)
    d.rectangle([410, 104, 810, 226], fill=(255, 255, 255))
    def title(box, fill, inset=0):
        x0, y0, x1, y1 = box[0] + inset, box[1] + inset, box[2] - inset, box[3] - inset
        if theme.shape == "plate":
            c = 7
            d.polygon([(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c), (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c)], fill=fill)
        elif theme.shape == "banner":
            n = (y1 - y0) // 3
            d.polygon([(x0, y0), (x1, y0), (x1 - n, (y0 + y1) // 2), (x1, y1), (x0, y1), (x0 + n, (y0 + y1) // 2)], fill=fill)
        else:
            d.rounded_rectangle([x0, y0, x1, y1], radius=(y1 - y0) // 2, fill=fill)

    for ox, bg in ((10, CURSEFORGE_BG), (410, (255, 255, 255))):
        title((ox + 40, 124, ox + 340, 168), theme.accent_dark[:3])
        title((ox + 40, 120, ox + 340, 164), theme.accent[:3])
        title((ox + 40, 120, ox + 340, 164), theme.paper[:3], inset=3)
        d.text((ox + 150, 136), "SECTION TITLE", fill=theme.ink[:3])
        d.text((ox + 40, 184), "Feature name", fill=theme.page_accent[:3] if bg == CURSEFORGE_BG else theme.accent_dark[:3])
        d.text((ox + 40, 200), "A sentence of description, in the color of the page.", fill=CURSEFORGE_TEXT if bg == CURSEFORGE_BG else (60, 60, 60))
    if pal:
        x = 10
        d.text((10, 236), "colors found in the images (most promising first):", fill=(200, 200, 200))
        for color, share, vivid in pal:
            d.rectangle([x, 252, x + 56, 280], fill=color)
            d.text((x, 284), to_hex(color), fill=(170, 170, 170))
            x += 62
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    img.save(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    e = sub.add_parser("extract", help="propose a theme from the mod's own images")
    e.add_argument("paths", nargs="+")
    e.add_argument("--out", default="theme.json")
    e.add_argument("--swatch")
    e.add_argument("--name")
    n = sub.add_parser("new", help="start a theme from a color chosen by hand")
    n.add_argument("--accent", required=True)
    n.add_argument("--alt")
    n.add_argument("--ornament", default="star")
    n.add_argument("--shape", default="pill", choices=("pill", "plate", "banner"))
    n.add_argument("--out", default="theme.json")
    n.add_argument("--swatch")
    n.add_argument("--name")
    s = sub.add_parser("show", help="print a theme with every derived color, and check its contrasts")
    s.add_argument("theme")
    s.add_argument("--swatch")
    args = parser.parse_args()
    if args.command == "new":
        data = {"accent": args.accent, "ornament": args.ornament, "shape": args.shape}
        if args.alt:
            data["alt"] = args.alt
        if args.name:
            data["name"] = args.name
        theme = Theme(data)
        theme.save(args.out)
        print(f"theme written to {args.out}: accent {theme.hex('accent')}, alt {theme.hex('alt')}, ornament {theme.ornament}, shape {theme.shape}")
        if args.swatch:
            swatch(theme, args.swatch)
    elif args.command == "extract":
        theme, pal = propose(args.paths)
        if not pal:
            print("error: no image found in " + ", ".join(args.paths) + ". Nothing was written. For a mod without art, choose the accent by hand "
                  "from what it shows in game: python theme.py new --accent \"#rrggbb\" --ornament <icon> --shape <pill|plate|banner>", file=sys.stderr)
            return 1
        if args.name:
            theme.name = args.name
        theme.save(args.out)
        for color, share, vivid in pal:
            print(f"  {to_hex(color)}  share {share:5.1%}  vividness {vivid:.2f}")
        print(f"theme written to {args.out}: accent {theme.hex('accent')}, alt {theme.hex('alt')}")
        print(f"  ornament '{theme.ornament}' and shape '{theme.shape}' are placeholders: choose them for this mod (one of its own item textures "
              "makes the best ornament), and change the accent if another color of the list is more the mod's own.")
        if args.swatch:
            swatch(theme, args.swatch, pal)
    else:
        theme = Theme.load(args.theme)
        print(json.dumps(theme.to_dict(), indent=2))
        if args.swatch:
            swatch(theme, args.swatch)
    for line in theme.warnings():
        print("warning:", line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
