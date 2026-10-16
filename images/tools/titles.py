"""Builds the title images of a page: one animated ribbon per section and per language, the labels
of the languages, a divider, and the badges, on a see-through background, in the colors, the shape
and with the ornament of the theme.

With VectorCraft (vector.py) they are drawn in vector: real type, gradients, tile icons, brand marks
on the badges, and EffectCraft sweeps a light across each title (polish.py). `title_style = "pixel"`
in [page] keeps the pixel art drawing of kit.py, which is also what is drawn without the suite.

    python titles.py page.toml [--prune]             # everything the page needs, into its images folder
    python titles.py --theme theme.json --out images --title "title-start-en.gif=GETTING STARTED:rocket"

No footage is needed. Titles and badges are written in capitals.

Author: vyrriox
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import icons as icon_lib  # noqa: E402
import kit  # noqa: E402
import pixelfont as font  # noqa: E402
import polish  # noqa: E402
import vector  # noqa: E402
from theme import Theme  # noqa: E402


def resolve_icon(name, out):
    """The icon exported for the page (images/icons/<name>.png) when there is one, then a picture given
    by its path (from the images folder, its tools folder or the current folder), else a built-in icon."""
    if not name:
        return None
    exported = os.path.join(out, "icons", name + ".png")
    if os.path.exists(exported):
        return exported
    for base in (out, os.path.join(out, "tools"), os.getcwd()):
        if os.path.isfile(os.path.join(base, name)):
            return os.path.join(base, name)
    if name in icon_lib.ICONS:
        return name
    raise SystemExit(f"no icon '{name}': run export_icons.py first, or use a name of the built-in set ({', '.join(sorted(icon_lib.ICONS))})")


def check_glyphs(file, text):
    unknown = font.missing(text)
    if unknown:
        print(f"warning: {file}: the pixel font has no glyph for {' '.join(unknown)}; they will show as '?'")


def build(entries, theme, out, style="vector"):
    os.makedirs(out, exist_ok=True)
    total = 0
    drawn_in_vector = style != "pixel" and vector.available()
    for file, text, icon_name, alt in entries:
        text = text.upper()
        if drawn_in_vector:
            frames = vector.ribbon(text, theme, resolve_icon(icon_name, out), alt=alt)
        else:
            check_glyphs(file, text)
            frames = kit.ribbon(text, theme, resolve_icon(icon_name, out), alt=alt)
        total += kit.write_sprite_gif(polish.title_frames(frames, theme), os.path.join(out, file))
    line = vector.divider(theme) if drawn_in_vector else kit.divider(theme)
    total += kit.write_sprite_gif(polish.divider_frames(line, theme), os.path.join(out, "divider.gif"))
    print(f"titles: {len(entries) + 1} files in {out}, {total / 1e3:.0f} KB in all")


def build_badges(badges, theme, out, rows=None):
    """The badges of the page (links, facts, partners), drawn in the theme: badge-<name>.png.

    With VectorCraft they are drawn in vector (vector.py: real type, gradients, brand marks) at twice
    their shown size; the page shows them at half width. Without it, in the pixel style of kit.py."""
    total, widths, drawn, sharp = 0, {}, {}, {}
    for file, (label, message, icon_name, alt) in badges.items():
        kind = re.sub(r"^badge-(extra-)?|(-[a-z]{2})?\.png$", "", file)
        custom = icon_name and os.path.exists(str(resolve_icon(icon_name, out)))
        if vector.available() and not custom:
            try:
                sharp[file] = vector.badge(label, message, theme, icon_name, alt, kind=kind)
                continue
            except Exception as error:  # a font or a render that failed: the pixel badge stands in
                print(f"note: {file}: vector badge failed ({error}), drawn in pixels instead")
        check_glyphs(file, label + message)
        drawn[file] = kit.badge(label, message, theme, resolve_icon(icon_name, out), alt)
    for file, img in {**polish.badges(drawn), **sharp}.items():
        img.save(os.path.join(out, file), optimize=True, dpi=img.info.get("dpi", (72, 72)))
        total += os.path.getsize(os.path.join(out, file))
        widths[file] = img.width // (vector.SCALE if file in sharp else 1)
    if badges:
        print(f"badges: {len(badges)} files in {out}, {total / 1e3:.0f} KB in all")
    if widths and max(widths.values()) > kit.W:
        print(f"warning: a badge is {max(widths.values())} pixels wide, more than the page: shorten its text")
    for name, files in (rows or {}).items():
        width = sum(widths.get(f, 0) for f in files) + 5 * (len(files) - 1)
        lines = -(-width // (kit.W + 30))
        if lines > 1:
            print(f"  the row of {name} ({len(files)} badges, {width} pixels) wraps on {lines} lines of the {kit.W} pixel column"
                  + (": drop a badge or shorten one" if lines > 2 else ""))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("spec", nargs="?", help="page.toml")
    parser.add_argument("--theme")
    parser.add_argument("--out")
    parser.add_argument("--title", action="append", default=[], help='"file.gif=TEXT[:icon]", repeatable')
    parser.add_argument("--prune", action="store_true", help="remove the titles, labels and badges the page no longer uses")
    args = parser.parse_args()
    if args.spec:
        from build_page import Page, SpecError
        try:
            page = Page(args.spec)
            badges = page.badges()
        except SpecError as e:
            raise SystemExit(f"error: {e}")
        out = args.out or page.images_dir
        titles = page.titles()
        build(titles, page.theme, out, page.spec.get("page", {}).get("title_style", "vector"))
        build_badges(badges, page.theme, out, page.badge_rows)
        wanted = {file for file, *_ in titles} | set(badges) | {"divider.gif"}
        stale = sorted(f for f in os.listdir(out) if f.startswith(("title-", "lang-", "badge-")) and f not in wanted)
        if stale and args.prune:
            for name in stale:
                os.remove(os.path.join(out, name))
            print("removed, no longer used by the page: " + ", ".join(stale))
        elif stale:
            print("no longer used by the page: " + ", ".join(stale) + " (--prune removes them)")
    else:
        entries = []
        for item in args.title:
            file, _, rest = item.partition("=")
            text, _, icon_name = rest.partition(":")
            entries.append((file, text, icon_name or None, file.startswith("lang-")))
        build(entries, Theme.load(args.theme), args.out or "images")


if __name__ == "__main__":
    main()
