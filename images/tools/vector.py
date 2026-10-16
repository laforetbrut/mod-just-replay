"""Badges and small icons drawn in vector and rendered by VectorCraft: smooth shapes, gradients, a
gloss, real type, and the Tabler icon set (assets/tabler, MIT license), rendered at twice their
shown size so they stay sharp on high density screens (the page shows them at half width).

    python vector.py sheet theme.json out.png      # every icon of the set, as tiles, to choose from

badge()         the badge of a link or a fact, in the shape (pill, plate, banner) and colors of the
                theme. Brand links (Discord, GitHub, YouTube, Patreon, PayPal, Stripe) get their brand
                mark; the others a pictogram of the set.
feature_icon()  the icon next to a feature name: a rounded tile in the theme's gradient with a white
                pictogram, or with the mod's own texture on it (texture_tile), so every icon of the
                page belongs to one family.

Falls back to the pixel drawing of kit.py when VectorCraft is missing or CRAFT=off.

Author: vyrriox
"""
import glob
import os
import re
import sys
import tempfile

from PIL import Image, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import craft  # noqa: E402
from theme import to_hex  # noqa: E402

SCALE = 2                       # rendered at twice the size it is shown
TABLER = next((p for p in (os.path.join(HERE, "tabler"), os.path.join(os.path.dirname(HERE), "assets", "tabler"))
               if os.path.isdir(p)), None)

# The pixel icon names of the toolkit, and the badge kinds, mapped to the Tabler set.
ICONS = {
    "bag": "shopping-bag", "bolt": "bolt", "book": "book", "brush": "brush", "camera": "camera", "chat": "message-circle",
    "check": "check", "chest": "box", "clock": "clock", "cross": "x", "cube": "cube", "download": "download",
    "drop": "droplet", "eye": "eye", "flag": "flag", "flame": "flame", "food": "meat", "gear": "settings", "gem": "diamond",
    "gift": "gift", "globe": "world", "hammer": "hammer", "heart": "heart", "home": "home", "info": "info-circle",
    "key": "key", "keycap": "keyboard", "leaf": "leaf", "lock": "lock", "moon": "moon", "note": "music", "paw": "paw",
    "pencil": "pencil", "pick": "pick", "pin": "map-pin", "play": "player-play", "plus": "plus", "potion": "flask",
    "puzzle": "puzzle", "question": "help-circle", "refresh": "refresh", "rocket": "rocket", "server": "server",
    "shield": "shield", "shirt": "shirt", "skull": "skull", "smile": "mood-smile", "sparkle": "sparkles", "star": "star",
    "sun": "sun", "sword": "sword", "upload": "upload", "users": "users", "wand": "wand", "warning": "alert-triangle",
}
BRANDS = {"discord": "brand-discord", "github": "brand-github", "source": "brand-github", "youtube": "brand-youtube",
          "patreon": "brand-patreon", "paypal": "brand-paypal", "stripe": "brand-stripe", "website": "world",
          "issues": "bug", "support": "lifebuoy", "wiki": "book-2", "docs": "book-2", "license": "license",
          "minecraft": "brand-minecraft", "java": "coffee", "loaders": "stack-2", "side": "server", "partners": "users-group",
          "donate": "heart-handshake", "modrinth": "world-download", "curseforge": "download"}
# Pictograms that read better filled than drawn as a line.
FILLED = {"heart", "star", "bolt", "flame", "droplet", "diamond", "shield", "gift", "key", "map-pin", "player-play",
          "brand-discord", "brand-github", "brand-youtube", "brand-patreon", "brand-paypal", "info-circle", "alert-triangle",
          "help-circle", "flag", "puzzle", "moon", "sun", "lock", "bug", "flask", "home", "message-circle"}

_fonts = {}


def available():
    return TABLER is not None and craft.available("vectorcraft")


def font_file(family="Inter", style="SemiBold"):
    """(path, family name as installed) of a static font file of that family and style."""
    key = (family, style)
    if key in _fonts:
        return _fonts[key]
    dirs = [os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts"),
            os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Fonts"),
            os.path.expanduser("~/Library/Fonts"), "/Library/Fonts", "/usr/share/fonts", os.path.expanduser("~/.fonts")]
    found = None
    for d in dirs:
        for path in glob.glob(os.path.join(d, "**", "*.[ot]tf"), recursive=True):
            try:
                fam, sty = ImageFont.truetype(path, 10).getname()
            except OSError:
                continue
            if sty.replace(" ", "").lower() == style.replace(" ", "").lower() and fam.lower() in (family.lower(), family.lower() + " 18pt"):
                found = (path, fam)
                if fam.lower() == family.lower():
                    break
        if found:
            break
    if not found:
        fallback = os.path.join(dirs[0], "seguisb.ttf")
        found = (fallback, "Segoe UI") if os.path.exists(fallback) else (None, family)
    _fonts[key] = found
    return found


def text_width(text, size, family, style, tracking=0.0):
    path, _ = font_file(family, style)
    if not path:
        return len(text) * size * 0.62
    font = ImageFont.truetype(path, round(size * 4))
    return font.getlength(text) / 4 + tracking * max(0, len(text) - 1)


def pictogram(name, color, box, filled=None, stroke=2.0):
    """SVG markup of a Tabler icon drawn in `color`, fitted into box (x, y, size)."""
    if not TABLER:
        return ""
    want_filled = filled if filled is not None else name in FILLED
    path = os.path.join(TABLER, "filled", name + ".svg")
    if not (want_filled and os.path.exists(path)):
        path = os.path.join(TABLER, "outline", name + ".svg")
        want_filled = False
    if not os.path.exists(path):
        path = os.path.join(TABLER, "outline", "cube.svg")
    with open(path, encoding="utf-8") as handle:
        svg = handle.read()
    inner = svg[svg.index(">", svg.index("<svg")) + 1:svg.rindex("</svg>")]
    inner = re.sub(r'<path stroke="none" d="M0 0h24v24H0z" fill="none"\s*/>', "", inner)
    x, y, size = box
    k = size / 24
    paint = (f'fill="{color}" stroke="none"' if want_filled else
             f'fill="none" stroke="{color}" stroke-width="{stroke}" stroke-linecap="round" stroke-linejoin="round"')
    inner = inner.replace("currentColor", color)
    return f'<g transform="translate({x:.2f} {y:.2f}) scale({k:.4f})" {paint}>{inner}</g>'


def icon_name(name, kind=None):
    if kind and kind in BRANDS:
        return BRANDS[kind]
    return ICONS.get(name, name if name and TABLER and os.path.exists(os.path.join(TABLER, "outline", f"{name}.svg")) else "cube")


def _hex(color):
    return to_hex(color) if not isinstance(color, str) else color


def _render(svg, width_px):
    tmp = tempfile.mkdtemp(prefix="pagevec_")
    src, out = os.path.join(tmp, "v.svg"), os.path.join(tmp, "v.png")
    with open(src, "w", encoding="utf-8") as handle:
        handle.write(svg)
    w = float(re.search(r'width="([\d.]+)"', svg).group(1))
    craft.vc_convert(src, out, scale=width_px / w)
    img = Image.open(out).convert("RGBA")
    img.info["dpi"] = (72 * SCALE, 72 * SCALE)
    return img


def _shape_path(shape, x, y, w, h, r):
    """Outline of a badge: pill (round ends), plate (cut corners) or banner (forked ends)."""
    if shape == "plate":
        c = h * 0.28
        return f"M{x + c} {y}H{x + w - c}L{x + w} {y + c}V{y + h - c}L{x + w - c} {y + h}H{x + c}L{x} {y + h - c}V{y + c}Z"
    if shape == "banner":
        n = h * 0.32
        return f"M{x} {y}H{x + w}L{x + w - n} {y + h / 2}L{x + w} {y + h}H{x}L{x + n} {y + h / 2}Z"
    return (f"M{x + r} {y}H{x + w - r}A{r} {r} 0 0 1 {x + w} {y + r}V{y + h - r}A{r} {r} 0 0 1 {x + w - r} {y + h}"
            f"H{x + r}A{r} {r} 0 0 1 {x} {y + h - r}V{y + r}A{r} {r} 0 0 1 {x + r} {y}Z")


def badge(label, message, theme, icon=None, alt=False, kind=None, height=30, family="Inter"):
    """A badge as an RGBA image, `height` pixels tall as shown (rendered at SCALE times that)."""
    dark = _hex(theme.alt_dark if alt else theme.accent_dark)
    mid = _hex(theme.alt if alt else theme.accent)
    paper = _hex(theme.alt_paper if alt else theme.paper)
    ink = _hex(theme.ink)
    h = height * SCALE
    shade = 3 * SCALE
    size = h * 0.37
    track = size * 0.06
    _, fam_label = font_file(family, "SemiBold")
    _, fam_msg = font_file(family, "Bold")
    shape = getattr(theme, "shape", "pill")
    pad = h * (0.42 if shape != "banner" else 0.62)
    ic = h * 0.5
    gap = h * 0.22
    label_w = text_width(label, size, family, "SemiBold", track) if label else 0
    msg_w = text_width(message, size, family, "Bold", track) if message else 0
    left = pad + ic + (gap + label_w if label else 0) + (gap if message else pad)
    w = left + (msg_w + pad if message else 0)
    r = h / 2 if shape == "pill" else h * 0.16
    outline = _shape_path(shape, 1, 1, w - 2, h - 2, r - 1)
    uid = f"b{abs(hash((label, message, alt))) % 10**8}"
    parts = [
        f'<defs><linearGradient id="{uid}g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{mid}"/>'
        f'<stop offset="1" stop-color="{dark}"/></linearGradient>'
        f'<linearGradient id="{uid}s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff" stop-opacity="0.32"/>'
        f'<stop offset="1" stop-color="#ffffff" stop-opacity="0"/></linearGradient>'
        f'<clipPath id="{uid}c"><path d="{outline}"/></clipPath></defs>',
        # A soft drop under the badge, then the body.
        f'<path d="{_shape_path(shape, 1, 1 + shade, w - 2, h - 2, r - 1)}" fill="#000000" opacity="0.45"/>',
        f'<g clip-path="url(#{uid}c)">',
        f'<rect x="0" y="0" width="{w}" height="{h}" fill="{paper}"/>',
        f'<rect x="0" y="0" width="{left if message else w}" height="{h}" fill="url(#{uid}g)"/>',
        f'<rect x="0" y="0" width="{w}" height="{h * 0.5}" fill="url(#{uid}s)"/>',
        f'<rect x="0" y="{h - SCALE * 1.5}" width="{w}" height="{SCALE * 1.5}" fill="#000000" opacity="0.18"/>',
        "</g>",
        f'<path d="{outline}" fill="none" stroke="{dark}" stroke-width="{SCALE}"/>',
        pictogram(icon_name(icon, kind), "#ffffff", (pad, (h - ic) / 2, ic), stroke=2.2),
    ]
    base = h / 2 + size * 0.36
    if label:
        parts.append(f'<text x="{pad + ic + gap:.2f}" y="{base:.2f}" font-family="{fam_label}" font-weight="600" '
                     f'font-size="{size:.2f}" letter-spacing="{track:.2f}" fill="#ffffff">{_esc(label)}</text>')
    if message:
        parts.append(f'<text x="{left + gap * 0.0 + (0 if label else 0):.2f}" y="{base:.2f}" font-family="{fam_msg}" '
                     f'font-weight="700" font-size="{size:.2f}" letter-spacing="{track:.2f}" fill="{ink}" '
                     f'transform="translate({gap:.2f} 0)">{_esc(message)}</text>')
    total_h = h + shade + 2
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w + 2:.0f}" height="{total_h:.0f}" '
           f'viewBox="0 0 {w + 2:.0f} {total_h:.0f}">{"".join(parts)}</svg>')
    return _render(svg, round(w + 2))


def _tile(theme, size, inner):
    s = size * SCALE
    r = s * 0.24
    a, d = _hex(theme.accent), _hex(theme.accent_dark)
    light = _hex(theme.accent_light)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{s}" height="{s}" viewBox="0 0 {s} {s}">'
            f'<defs><linearGradient id="tg" x1="0" y1="0" x2="0.4" y2="1"><stop offset="0" stop-color="{light}"/>'
            f'<stop offset="0.45" stop-color="{a}"/><stop offset="1" stop-color="{d}"/></linearGradient>'
            f'<linearGradient id="tl" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff" stop-opacity="0.35"/>'
            f'<stop offset="1" stop-color="#ffffff" stop-opacity="0"/></linearGradient></defs>'
            f'<rect x="{s * 0.04}" y="{s * 0.07}" width="{s * 0.92}" height="{s * 0.92}" rx="{r}" fill="#000000" opacity="0.4"/>'
            f'<rect x="{s * 0.04}" y="{s * 0.03}" width="{s * 0.92}" height="{s * 0.92}" rx="{r}" fill="url(#tg)" '
            f'stroke="{d}" stroke-width="{SCALE}"/>'
            f'<rect x="{s * 0.1}" y="{s * 0.07}" width="{s * 0.8}" height="{s * 0.42}" rx="{r * 0.8}" fill="url(#tl)"/>'
            f"{inner}</svg>")


def feature_icon(name, theme, size=32):
    """A tile with a white pictogram: the icon of a feature, `size` px as shown."""
    s = size * SCALE
    pic = icon_name(name)
    inner = (pictogram(pic, "#000000", (s * 0.24, s * 0.25, s * 0.52), stroke=2.4).replace("<g ", '<g opacity="0.35" ', 1)
             + pictogram(pic, "#ffffff", (s * 0.24, s * 0.21, s * 0.52), stroke=2.4))
    return _render(_tile(theme, size, inner), s)


def texture_tile(img, theme, size=32):
    """The mod's own texture (pixel art kept sharp) on the same tile as the pictograms."""
    s = size * SCALE
    tile = _render(_tile(theme, size, ""), s)
    art = img.convert("RGBA")
    box = art.getchannel("A").getbbox()
    if box:
        art = art.crop(box)
    inner = int(s * 0.62)
    k = max(1, inner // max(art.size))
    if max(art.size) * k > inner or max(art.size) > 32:
        art.thumbnail((inner, inner), Image.LANCZOS)
    else:
        art = art.resize((art.width * k, art.height * k), Image.NEAREST)
    shadow = Image.new("RGBA", art.size, (0, 0, 0, 0))
    shadow.putalpha(art.getchannel("A").point(lambda v: v * 0.4))
    x, y = (s - art.width) // 2, (s - art.height) // 2 - SCALE
    tile.alpha_composite(shadow, (x + SCALE, y + 2 * SCALE))
    tile.alpha_composite(art, (x, y))
    return tile


PAGE_BG = (13, 13, 13)          # CurseForge's dark page, measured (theme.CURSEFORGE_BG)
FRAMES = 24                     # 80 ms each, like the pixel titles


def matte(img, bg=PAGE_BG, cut=6):
    """Soft edges blended into the page background, then hard alpha: a GIF shows a pixel or not, so
    an anti-aliased edge is kept smooth by mixing it with the color it will sit on."""
    rgba = img.convert("RGBA")
    base = Image.new("RGBA", rgba.size, bg + (255,))
    mixed = Image.alpha_composite(base, rgba)
    mixed.putalpha(rgba.getchannel("A").point(lambda a: 255 if a >= cut else 0))
    return mixed


def _hop(t, at, height=3, length=0.18):
    import math
    k = (t - at) % 1.0
    return -int(round(math.sin(k / length * math.pi) * height)) if k < length else 0


def _small_icon(icon, theme, size=30):
    if icon and os.path.exists(str(icon)):
        img = Image.open(icon).convert("RGBA")
    else:
        img = feature_icon(icon or "star", theme)
    return img.resize((size, size), Image.LANCZOS)


def ribbon(text, theme, icon=None, alt=False, width=800, family="Inter"):
    """Frames of a section title (or a language label with alt): the text in a real font on a plate
    in the theme's shape, a tile icon on each side, lines that fade out towards the edges, the
    theme's ornament at both ends. Icons and ornaments hop in turn, like the pixel titles."""
    w, h = width, 60
    paper = _hex(theme.alt_paper if alt else theme.paper)
    border = _hex(theme.alt if alt else theme.accent)
    dark = _hex(theme.alt_dark if alt else theme.accent_dark)
    ink = _hex(theme.ink)
    size, track = 21, 1.6
    _, fam = font_file(family, "ExtraBold")
    shape = getattr(theme, "shape", "pill")
    ic = 30 if icon else 0
    inset = 22 if shape != "banner" else 34
    tw = text_width(text, size, family, "ExtraBold", track)
    pw = inset + (ic + 12 if ic else 0) * 2 + tw + inset
    if pw > w - 150:
        raise ValueError(f"title too long for a {w} pixel ribbon: {text!r} ({pw:.0f} pixels). Shorten it or drop the icon.")
    px, top, body = (w - pw) / 2, 6, 44
    r = body / 2 if shape == "pill" else 8
    y = top + body / 2
    uid = f"r{abs(hash((text, alt))) % 10**8}"
    outline = _shape_path(shape, px, top, pw, body, r)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
           f'<defs><linearGradient id="{uid}l" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{border}" stop-opacity="0"/>'
           f'<stop offset="1" stop-color="{border}"/></linearGradient>'
           f'<linearGradient id="{uid}r" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{border}"/>'
           f'<stop offset="1" stop-color="{border}" stop-opacity="0"/></linearGradient>'
           f'<linearGradient id="{uid}p" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/>'
           f'<stop offset="0.5" stop-color="{paper}"/><stop offset="1" stop-color="{paper}"/></linearGradient></defs>'
           f'<rect x="46" y="{y - 1.5}" width="{px - 60}" height="3" rx="1.5" fill="url(#{uid}l)"/>'
           f'<rect x="{px + pw + 14}" y="{y - 1.5}" width="{w - 46 - px - pw - 14}" height="3" rx="1.5" fill="url(#{uid}r)"/>'
           f'<path d="{_shape_path(shape, px, top + 4, pw, body, r)}" fill="{dark}"/>'
           f'<path d="{outline}" fill="url(#{uid}p)" stroke="{border}" stroke-width="2.5"/>'
           f'<path d="{_shape_path(shape, px + 4, top + 4, pw - 8, body - 8, max(1, r - 4))}" fill="none" '
           f'stroke="{border}" stroke-opacity="0.35" stroke-width="1"/>'
           f'<text x="{w / 2:.2f}" y="{y + size * 0.36:.2f}" text-anchor="middle" font-family="{fam}" font-weight="800" '
           f'font-size="{size}" letter-spacing="{track}" fill="{ink}">{_esc(text)}</text></svg>')
    base = _render(svg, w)
    base.info.pop("dpi", None)
    icon_img = _small_icon(icon, theme, ic) if ic else None
    orn = _ornament(theme)
    out = []
    for i in range(FRAMES):
        t = i / FRAMES
        canvas = base.copy()
        if icon_img:
            canvas.alpha_composite(icon_img, (int(px + inset), int(top + (body - ic) / 2 + _hop(t, 0.04))))
            canvas.alpha_composite(icon_img, (int(px + pw - inset - ic), int(top + (body - ic) / 2 + _hop(t, 0.54))))
        if orn:
            canvas.alpha_composite(orn, (30 - orn.width // 2, int(y - orn.height / 2 + _hop(t, 0.29))))
            canvas.alpha_composite(orn, (w - 30 - orn.width // 2, int(y - orn.height / 2 + _hop(t, 0.79))))
        out.append(matte(canvas))
    return out


def _ornament(theme, size=16):
    name = getattr(theme, "ornament", None)
    if name and os.path.exists(str(name)):
        img = Image.open(name).convert("RGBA")
        if img.height > img.width:
            img = img.crop((0, 0, img.width, img.width))
        exact = img.width <= size and size % img.width == 0
        return img.resize((size, size), Image.NEAREST if exact else Image.LANCZOS)
    try:
        return feature_icon(ICONS.get(name, "sparkle") if name else "sparkle", theme).resize((size, size), Image.LANCZOS)
    except Exception:
        return None


def divider(theme, width=800, family="Inter"):
    """Frames of the line that closes a part of the page: two fading lines and three ornaments."""
    w, h = width, 44
    border = _hex(theme.accent)
    y = h / 2
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
           f'<defs><linearGradient id="dl" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{border}" stop-opacity="0"/>'
           f'<stop offset="1" stop-color="{border}"/></linearGradient>'
           f'<linearGradient id="dr" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{border}"/>'
           f'<stop offset="1" stop-color="{border}" stop-opacity="0"/></linearGradient></defs>'
           f'<rect x="120" y="{y - 1.5}" width="{w / 2 - 172}" height="3" rx="1.5" fill="url(#dl)"/>'
           f'<rect x="{w / 2 + 52}" y="{y - 1.5}" width="{w / 2 - 172}" height="3" rx="1.5" fill="url(#dr)"/></svg>')
    base = _render(svg, w)
    big, small = _ornament(theme, 32), _ornament(theme, 16)
    out = []
    for i in range(FRAMES):
        t = i / FRAMES
        canvas = base.copy()
        for img, x, at, lift in ((small, w // 2 - 32, 0.05, 2), (big, w // 2, 0.20, 0), (small, w // 2 + 32, 0.35, 2)):
            if img:
                canvas.alpha_composite(img, (x - img.width // 2, int(y - img.height / 2 + lift + _hop(t, at, 4 if img is big else 3))))
        out.append(matte(canvas))
    return out


def _esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def sheet(theme, out):
    names = sorted(ICONS)
    cols = 12
    cell = 32 * SCALE + 24
    img = Image.new("RGBA", (cols * cell, ((len(names) + cols - 1) // cols) * (cell + 18)), (13, 13, 13, 255))
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    for i, n in enumerate(names):
        x, y = (i % cols) * cell, (i // cols) * (cell + 18)
        img.alpha_composite(feature_icon(n, theme), (x + 12, y + 8))
        d.text((x + 12, y + cell - 6), n, fill=(170, 170, 170))
    img.save(out)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) == 4 and sys.argv[1] == "sheet":
        from theme import Theme
        sheet(Theme.load(sys.argv[2]), sys.argv[3])
        print(sys.argv[3])
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
