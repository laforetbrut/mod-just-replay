"""Writes the small icons shown next to the feature names of a page (images/icons/<name>.png, 32 pixels).

    python export_icons.py page.toml [--prune]

Each icon name used by an item of page.toml is looked up in its [icons] table:

    [icons]
    toilet = "src/main/resources/assets/mymod/textures/item/toilet.png"   # a texture of the mod (path from the repository root)
    atlas = "src/main/resources/assets/mymod/textures/gui/icons.png#32,0,16,16"   # a part of an image: x, y, width, height
    settings = "builtin:gear"                                             # an icon of the built-in set, under another name
    emblem = "Branding/logo/svg/mymod-icon-color.svg"                     # a vector drawing, rendered sharp by VectorCraft

A name that is not in the table is taken from the built-in set (icons.py). The mod's own textures
come first: they are what a player will recognise in game. The empty border of a texture is cut,
so a small drawing in a large square still fills its icon, and a large picture (a rendered model)
is reduced to flat colors with hard edges, to sit well next to pixel art. --prune removes the
icons of the folder that the page no longer uses.

With VectorCraft (the default `icon_style = "tile"` of [page]), every icon is one family, drawn in
vector at twice its shown size (64 pixels, shown at 32): a rounded tile in the theme's gradient
with a white pictogram of the Tabler set for a built-in name, or with the mod's texture on it
(kept sharp) for a picture. An SVG (the brand mark, a drawn icon) is rendered by VectorCraft as it
is. `icon_style = "plain"` keeps the textures bare and the built-in icons in pixels.

Author: vyrriox
"""
import argparse
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import craft  # noqa: E402
import icons as icon_lib  # noqa: E402
import polish  # noqa: E402
import vector  # noqa: E402
from build_page import Page, SpecError  # noqa: E402

SIZE = 32


def from_svg(path, size=SIZE * vector.SCALE):
    """A vector drawing rendered by VectorCraft so its longer side is exactly `size` pixels."""
    import re
    import tempfile
    with open(path, encoding="utf-8", errors="replace") as handle:
        head = handle.read(4000)
    view = re.search(r'viewBox="[^"]*?([\d.]+)\s+([\d.]+)"', head)
    width = float(view.group(1)) if view else float(re.search(r'width="([\d.]+)', head).group(1))
    height = float(view.group(2)) if view else float(re.search(r'height="([\d.]+)', head).group(1))
    tmp = os.path.join(tempfile.mkdtemp(prefix="pageicon_"), "icon.png")
    craft.vc_convert(path, tmp, scale=size / max(width, height))
    img = Image.open(tmp).convert("RGBA")
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(img, ((size - img.width) // 2, (size - img.height) // 2))
    return out


def load(source, root):
    """The picture a source names, cropped to its box or to the first frame of an animated texture."""
    path, _, box = source.partition("#")
    path = path if os.path.isabs(path) else os.path.join(root, path)
    img = Image.open(path).convert("RGBA")
    if box:
        x, y, w, h = (int(v) for v in box.split(","))
        img = img.crop((x, y, x + w, y + h))
    elif img.height > img.width and img.height % img.width == 0:
        img = img.crop((0, 0, img.width, img.width))      # animated texture: first frame
    return img


def from_file(source, root):
    path = source.partition("#")[0]
    path = path if os.path.isabs(path) else os.path.join(root, path)
    if path.lower().endswith(".svg") and craft.available("vectorcraft"):
        return from_svg(path)
    img = load(source, root)
    drawn = img.getchannel("A").getbbox()
    if drawn:
        img = img.crop(drawn)
    if max(img.size) > SIZE:
        img.thumbnail((SIZE, SIZE), Image.LANCZOS)
        # A reduced picture has soft edges and hundreds of colors: make it flat, like the pixel art around it.
        alpha = img.getchannel("A").point(lambda a: 255 if a >= 128 else 0)
        img = img.convert("RGB").quantize(colors=48, method=Image.MEDIANCUT).convert("RGB")
        img.putalpha(alpha)
    else:
        k = max(1, SIZE // max(img.size))
        img = img.resize((img.width * k, img.height * k), Image.NEAREST)
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(img, ((SIZE - img.width) // 2, (SIZE - img.height) // 2))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("spec")
    parser.add_argument("--prune", action="store_true", help="remove the icons of the folder that the page no longer uses")
    args = parser.parse_args()
    try:
        page = Page(args.spec)
    except SpecError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    root = os.path.normpath(os.path.join(page.dir, page.spec.get("page", {}).get("root", ".")))
    table = page.spec.get("icons", {})
    folder = os.path.join(page.images_dir, "icons")
    os.makedirs(folder, exist_ok=True)
    tiles = page.spec.get("page", {}).get("icon_style", "tile") == "tile" and vector.available()
    built, own, failed, builtin = 0, 0, [], {}
    for name in page.icons():
        source = table.get(name, "builtin:" + name)
        target = os.path.join(folder, name + ".png")
        try:
            if source.startswith("builtin:"):
                if tiles:
                    vector.feature_icon(source[len("builtin:"):], page.theme).save(target)
                else:
                    builtin[name] = icon_lib.icon(source[len("builtin:"):], page.theme, 2)
                built += 1
            else:
                vector_file = source.lower().partition("#")[0].endswith(".svg")
                if tiles and not vector_file:
                    vector.texture_tile(load(source, root), page.theme).save(target)
                else:
                    from_file(source, root).save(target)
                own += 1
        except (KeyError, OSError, craft.CraftError) as e:
            failed.append(f"{name}: {e}")
    for name, img in polish.icons(builtin).items():
        img.save(os.path.join(folder, name + ".png"))
    print(f"icons: {own} from pictures (the mod's textures or drawn ones), {built} from the built-in set, in {folder}")
    stale = sorted(f for f in os.listdir(folder) if f.endswith(".png") and f[:-4] not in page.icons())
    if stale and args.prune:
        for name in stale:
            os.remove(os.path.join(folder, name))
        print("removed, no longer used by the page: " + ", ".join(stale))
    elif stale:
        print("no longer used by the page: " + ", ".join(stale) + " (--prune removes them)")
    for line in failed:
        print("error:", line)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
