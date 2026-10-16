"""Builds the title images of a page: one animated ribbon per section and per language, the labels
of the languages, a divider, and the badges. They are pixel art on a see-through background, in the
colors, the shape and with the ornament of the theme.

    python titles.py page.toml [--prune]             # everything the page needs, into its images folder
    python titles.py --theme theme.json --out images --title "title-start-en.gif=GETTING STARTED:rocket"

No footage is needed. Titles and badges are written in capitals: the pixel font reads best that way.

Author: vyrriox
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import icons as icon_lib  # noqa: E402
import kit  # noqa: E402
import pixelfont as font  # noqa: E402
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


def build(entries, theme, out):
    os.makedirs(out, exist_ok=True)
    total = 0
    for file, text, icon_name, alt in entries:
        text = text.upper()
        check_glyphs(file, text)
        frames = kit.ribbon(text, theme, resolve_icon(icon_name, out), alt=alt)
        total += kit.write_sprite_gif(frames, os.path.join(out, file))
    total += kit.write_sprite_gif(kit.divider(theme), os.path.join(out, "divider.gif"))
    print(f"titles: {len(entries) + 1} files in {out}, {total / 1e3:.0f} KB in all")


def build_badges(badges, theme, out, rows=None):
    """The badges of the page (links, facts, partners), drawn in the theme: badge-<name>.png."""
    total, widths = 0, {}
    for file, (label, message, icon_name, alt) in badges.items():
        check_glyphs(file, label + message)
        img = kit.badge(label, message, theme, resolve_icon(icon_name, out), alt)
        img.save(os.path.join(out, file), optimize=True)
        total += os.path.getsize(os.path.join(out, file))
        widths[file] = img.width
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
        build(titles, page.theme, out)
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
