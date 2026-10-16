"""Shows a page the way CurseForge will: its filter and its stylesheet are imitated, and a headless
browser takes pictures of the result, cut in slices that are easy to look at.

    python preview.py curseforge_page.md [--images images] [--out images/preview] [--strict] [--only 0,2]

--images  the local images folder (default: the folder named images next to the page): every image
          of the page that exists there is shown from there, so a page can be looked at before its
          files are published, wherever they will be hosted.
--out     where the pictures go (default: preview/ inside the images folder).
--strict  also drops the styles of tables and cells, the part of the filter that could not be
          observed on real pages: the worst case.

The preview page itself (curseforge.html) can be opened in a browser to see the banners move. The
font of CurseForge and the images that are not in the local folder are loaded from the network.

What CurseForge does was measured on live project pages: it removes the `align` attribute and
every style property outside a short list, shows h3 at 16 px in regular weight, text in #999 on
#0d0d0d, tables with dark striped rows and cells aligned to the left. Never judge a page on its
GitHub rendering: GitHub keeps `align` and drops `style`, the opposite.

Author: vyrriox
"""
import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import quote

from check_page import local_file

ALLOWED = {"text-align", "color", "background-color", "font-size", "font-family", "font-weight", "font-style", "text-decoration",
           "display", "margin-left", "margin-right", "padding-left", "line-height", "width", "border"}
WIDTH = 832         # the description column of a CurseForge project page
SLICE = 1500
OVERLAP = 60        # two slices share a strip, so a line cut by one is whole in the next

CSS = """
body { background: #0d0d0d; margin: 0; padding: 24px 0 60px; }
.project-description { width: %dpx; margin: 0 auto; color: #999; font-family: Lato, Arial, sans-serif; font-size: 16px; line-height: 24px; overflow-wrap: break-word; }
.project-description p { margin: 0 0 16px; line-height: 1.45; }
.project-description h1 { font-size: 40px; font-weight: 400; color: #e5e5e5; margin: 0 0 24px; }
.project-description h2 { font-size: 24px; font-weight: 400; color: #e5e5e5; margin: 0 0 24px; }
.project-description h3 { font-size: 16px; font-weight: 400; color: #e5e5e5; margin: 0 0 24px; line-height: 1.45; }
.project-description strong { font-weight: 700; }
.project-description a { color: #b2b2b2; text-decoration: underline; }
.project-description img { max-width: 100%%; vertical-align: top; }
.project-description hr { margin: 0 0 12px; }
.project-description table { border-collapse: collapse; margin: 0 0 16px; }
.project-description th { background: #404040; color: #fff; font-weight: 500; padding: 8px 12px; text-align: left; }
.project-description td { padding: 8px 12px; text-align: left; }
.project-description tr:nth-child(odd) { background: #303030; }
.project-description tr:nth-child(even) { background: #202020; }
.project-description code { background: #262626; border: 1px solid #4d4d4d; padding: 2px 4px; font-family: monospace; font-size: 16px; }
""" % WIDTH


def sanitize(page, strict=False):
    """The page after CurseForge's filter, as far as it could be observed."""
    page = re.sub(r'\salign="[^"]*"', "", page)

    def retag(match):
        tag, attrs = match.group(1), match.group(2)
        style = re.search(r'\sstyle="([^"]*)"', attrs)
        if not style:
            return match.group(0)
        kept = []
        for part in style.group(1).split(";"):
            if ":" not in part:
                continue
            name, value = part.split(":", 1)
            if name.strip().lower() in ALLOWED and not (strict and tag.lower() in ("table", "td", "th", "tr")):
                kept.append(f"{name.strip().lower()}:{value.strip()}")
        attrs = attrs.replace(style.group(0), f' style="{";".join(kept)}"' if kept else "")
        return f"<{tag}{attrs}>"

    return re.sub(r"<(\w+)((?:\s[^<>]*)?)>", retag, page)


def localize(body, images, out_dir):
    """Points every image that exists in the local images folder at that file, wherever the page will
    load it from once published: a page can be looked at before anything is online. The addresses are
    written relative to the preview folder, so the preview holds no local path."""
    def swap(match):
        path = local_file(match.group(2), images)
        if not path:
            return match.group(0)
        relative = os.path.relpath(os.path.abspath(path), os.path.abspath(out_dir)).replace(os.sep, "/")
        return match.group(1) + quote(relative) + match.group(3)

    return re.sub(r'(<img\b[^>]*?\bsrc=")([^"]+)(")', swap, body)


def document(page, images=None, strict=False, out_dir="."):
    body = sanitize(page, strict)
    if images:
        body = localize(body, images, out_dir)
    return ('<!doctype html>\n<html><head><meta charset="utf-8"><title>CurseForge preview</title>\n'
            '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Lato:ital,wght@0,400;0,700;1,400&display=swap">\n'
            f"<style>{CSS}</style></head><body><div class=\"project-description\">\n{body}\n</div></body></html>\n")


def browser():
    names = ["msedge", "microsoft-edge", "google-chrome", "chrome", "chromium", "chromium-browser"]
    paths = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ]
    for name in names:
        found = shutil.which(name)
        if found:
            return found
    for path in paths:
        if os.path.exists(path):
            return path
    return None


def run(exe, profile, args, timeout=120):
    cmd = [exe, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run", "--no-default-browser-check",
           f"--user-data-dir={profile}", "--virtual-time-budget=9000"] + args
    # The browser leaves files in the temporary folder: point it at the profile, which is removed afterwards.
    env = dict(os.environ, TMP=profile, TEMP=profile, TMPDIR=profile)
    os.makedirs(profile, exist_ok=True)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, env=env)


def page_height(exe, profile, html_path):
    """Height of the rendered document, read from a copy of the page that writes it in its title."""
    probe = html_path[:-5] + "_probe.html"
    text = open(html_path, encoding="utf-8").read().replace(
        "</body>", "<script>addEventListener('load',()=>{document.title='H='+document.documentElement.scrollHeight})</script></body>")
    with open(probe, "w", encoding="utf-8") as f:
        f.write(text)
    try:
        out = run(exe, profile, ["--window-size=900,1200", "--dump-dom", pathlib.Path(probe).as_uri()]).stdout
    finally:
        os.remove(probe)
    m = re.search(r"<title>H=(\d+)</title>", out or "")
    return int(m.group(1)) if m else None


def shoot(exe, profile, html_path, out_dir, height, only=None):
    """Pictures of the page, SLICE pixels tall each. Returns their paths."""
    from PIL import Image
    text = open(html_path, encoding="utf-8").read()
    paths = []
    step = SLICE - OVERLAP
    for n, offset in enumerate(range(0, height, 2 * step)):
        tmp_html = html_path[:-5] + f"_at{offset}.html"
        with open(tmp_html, "w", encoding="utf-8") as f:
            f.write(text.replace("<body>", f'<body style="margin-top:-{offset}px">'))
        tmp_png = os.path.join(out_dir, f"_chunk{n}.png")
        h = min(step + SLICE, height - offset)
        try:
            run(exe, profile, [f"--window-size=900,{h}", f"--screenshot={tmp_png}", pathlib.Path(tmp_html).as_uri()])
        finally:
            os.remove(tmp_html)
        if not os.path.exists(tmp_png):
            continue
        img = Image.open(tmp_png).convert("RGB")
        for k, top in enumerate((0, step)):
            if k == 1 and img.height <= SLICE:
                break                                   # the first slice already shows all of it
            index = n * 2 + k
            if only is not None and index not in only:
                continue
            path = os.path.join(out_dir, f"slice-{index:02d}.png")
            img.crop((0, top, img.width, min(img.height, top + SLICE))).save(path)
            paths.append(path)
        os.remove(tmp_png)
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("page")
    parser.add_argument("--out", help="folder of the pictures (default: preview/ inside the images folder)")
    parser.add_argument("--images", help="local images folder (default: images/ next to the page)")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--only", help="slice numbers to keep, separated by commas")
    parser.add_argument("--no-shots", action="store_true", help="write the HTML file only")
    parser.add_argument("--offline", action="store_true", help="no font from the network: the text is drawn in Arial, a little narrower")
    args = parser.parse_args()
    if not args.images:
        beside = os.path.join(os.path.dirname(os.path.abspath(args.page)), "images")
        args.images = beside if os.path.isdir(beside) else None
    if not args.out:
        args.out = os.path.join(args.images, "preview") if args.images else "preview"
    os.makedirs(args.out, exist_ok=True)
    page = open(args.page, encoding="utf-8").read()
    html_path = os.path.abspath(os.path.join(args.out, "curseforge.html"))
    with open(html_path, "w", encoding="utf-8", newline="\n") as f:
        html_text = document(page, args.images, args.strict, args.out)
        if args.offline:
            html_text = re.sub(r'<link rel="stylesheet" href="https://fonts.googleapis.com[^>]*>\n', "", html_text)
        f.write(html_text)
    print(f"preview page: {html_path}")
    if args.no_shots:
        return 0
    exe = browser()
    if not exe:
        print("no Chrome, Edge or Chromium found: open the preview page in a browser to look at it")
        return 0
    for old in os.listdir(args.out):
        if re.fullmatch(r"slice-\d+\.png", old):
            os.remove(os.path.join(args.out, old))
    profile = tempfile.mkdtemp(prefix="cfpreview_", dir=os.environ.get("PAGE_TEMP") or None)
    try:
        height = page_height(exe, profile, html_path) or 12000
        only = {int(v) for v in args.only.split(",")} if args.only else None
        paths = shoot(exe, profile, html_path, os.path.abspath(args.out), height, only)
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    print(f"page height on CurseForge: about {height} pixels ({height / 900:.1f} screens)")
    if height > 3600:
        print("  that is more than two screens per language for a page in two languages: drop a banner, a section or the "
              "second version of a banner (writing.md, Budgets)")
    for p in paths:
        print("  " + p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
