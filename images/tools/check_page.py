"""Checks a CurseForge page before it is handed over.

    python check_page.py curseforge_page.md [--images images] [--online] [--private [owner/repo]] [--uploads [folder]]

--images   the local images folder (default: the folder named images next to the page): every file
           the page loads from its own address must be there, and weigh less than 2 MB.
--online   asks each image address whether it answers (status, type, weight). Run it after the
           images are published.
--private  the repository is private or its visibility unknown: any link into it is a dead link
           for a visitor. With owner/repo only that repository is looked for; alone, every link to
           a GitHub repository is reported.
--uploads  lists the files the page loads from its own address, the ones that must be online for
           the page to work. With a folder, also copies them there, laid out as they must be at
           that address: what to send to a site when the repository cannot serve them.

It prints the size of the page (bytes, visible words, images), then errors (the page must not
ship), then warnings (worth a look). Exit code 1 on errors. The first line also serves to compare
an old page with its replacement: run it on both.

Author: vyrriox
"""
import argparse
import os
import re
import shutil
import sys
import unicodedata
import urllib.request

ALLOWED_STYLES = {"text-align", "color", "background-color", "font-size", "font-family", "font-weight", "font-style", "text-decoration",
                  "display", "margin-left", "margin-right", "padding-left", "line-height", "width", "border"}
PAIRED = ["div", "p", "h1", "h2", "h3", "h4", "table", "tr", "td", "th", "span", "strong", "em", "a", "code", "ul", "ol", "li"]
WARN_BYTES = 40_000
MAX_BYTES = 52_000
MAX_IMAGE_BYTES = 2_000_000
SYMBOLS = [(0x1F000, 0x1FAFF), (0x2600, 0x27BF), (0xFE0F, 0xFE0F), (0x2B00, 0x2BFF), (0x1F1E6, 0x1F1FF)]
EMOJI = re.compile("[" + "".join(chr(a) if a == b else f"{chr(a)}-{chr(b)}" for a, b in SYMBOLS) + "]")
EM_DASH = chr(0x2014)
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
IMG_SRC = re.compile(r'<img\b[^>]*?\bsrc="([^"]+)"')
ANY_REPOSITORY = r"(?!Team-Arcadia/images\b)[\w.-]+/[\w.-]+"   # the shared images repository is public


def visible_words(page):
    """Words a visitor reads: tags, entities, image and link addresses are left out; a version number is one word."""
    text = re.sub(r"<[^>]+>", " ", page)
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", text)
    text = re.sub(r"\]\([^)]*\)", " ", text)
    text = re.sub(r"&#?\w+;", " ", text)
    return len(re.findall(r"[^\W_]+(?:[.'’-][^\W_]+)*", text))


def local_file(url, images):
    """The file of the local images folder that an image address stands for: the longest tail of the
    address that names a file there (icons/heart.png before heart.png)."""
    parts = url.split("?")[0].split("#")[0].split("/")
    for start in range(len(parts)):
        tail = parts[start:]
        if not all(tail) or ":" in tail[0]:
            continue
        path = os.path.join(images, *tail)
        if os.path.isfile(path):
            return path
    return None


def images_base(page, images=None):
    """The address the page loads its own images from. It is worked out from the files of the local
    images folder, so it holds for any host: jsDelivr for a public repository, a site of the user's
    for a private one. Without a local folder, an address with '/images/' in it gives it away."""
    urls = [u for u in IMG_SRC.findall(page) if "img.shields.io" not in u]
    if images and os.path.isdir(images):
        counts = {}
        for url in urls:
            path = local_file(url, images)
            if path:
                tail = os.path.relpath(path, images).replace(os.sep, "/")
                clean = url.split("?")[0].split("#")[0]
                base = clean[:len(clean) - len(tail)]
                counts[base] = counts.get(base, 0) + 1
        if counts:
            return max(counts, key=counts.get)
    for url in urls:
        if "/images/" in url:
            return url[:url.index("/images/") + len("/images/")]
    return None


def own_files(page, images):
    """Relative paths of the files the page loads from its own address, in the order of the page."""
    base = images_base(page, images)
    if not base:
        return base, []
    seen = []
    for url in IMG_SRC.findall(page):
        if url.startswith(base):
            rel = url[len(base):].split("?")[0].split("#")[0]
            if rel not in seen:
                seen.append(rel)
    return base, seen


def check(page, images=None, online=False, private=None):
    errors, warnings = [], []
    size = len(page.encode("utf-8"))
    if size > MAX_BYTES:
        warnings.append(f"the page weighs {size} bytes: more than any description found on CurseForge ({MAX_BYTES}); it may be refused")
    elif size > WARN_BYTES:
        warnings.append(f"the page weighs {size} bytes (over {WARN_BYTES}): shorten it if you can")

    for tag in PAIRED:
        opened = len(re.findall(rf"<{tag}(?=[\s>])", page))
        closed = page.count(f"</{tag}>")
        if opened != closed:
            errors.append(f"<{tag}>: {opened} opened, {closed} closed")

    for style in re.findall(r'style="([^"]*)"', page):
        for part in style.split(";"):
            name = part.split(":")[0].strip().lower()
            if name and name not in ALLOWED_STYLES:
                warnings.append(f"style '{name}' is dropped by CurseForge: {style!r}")
    aligned = len(re.findall(r'<(?:p|h\d|img) [^>]*align="', page))
    if aligned:
        warnings.append(f"{aligned} tags carry align=\"...\": CurseForge removes it; center with a wrapper that has style=\"text-align:center\"")
    if "text-align:center" not in page.replace("text-align: center", "text-align:center"):
        errors.append("nothing on the page uses text-align:center: on CurseForge everything will sit on the left")
    bare = [h for h in re.findall(r"<h[1-6][^>]*>(.*?)</h[1-6]>", page, re.S) if "<img" not in h and "font-size" not in h]
    bare += re.findall(r"^#{1,6} +(.+)$", page, re.M)
    if bare:
        sample = " ".join(re.sub(r"<[^>]+>", " ", bare[0]).split())[:40].encode("ascii", "replace").decode()
        warnings.append(f"{len(bare)} headings are plain text ('{sample}'...): CurseForge shows them small and grey; "
                        "use a title image, or a span with a font-size")

    if re.search(r"<a [^>]*>\s+<img|<img[^>]*>\s+</a>", page):
        warnings.append("white space between <a> and <img>: CurseForge underlines it as link text; write <a ...><img ...></a> on one line")
    if re.search(r'href="\s*"', page):
        errors.append("a link has an empty address (href=\"\")")
    symbols = sorted(set(EMOJI.findall(page)))
    if symbols:
        named = ", ".join(f"U+{ord(s):04X} {unicodedata.name(s, 'unnamed').lower()}" for s in symbols[:8])
        warnings.append(f"{len(EMOJI.findall(page))} emoji or symbols ({named}{', ...' if len(symbols) > 8 else ''}): pages read better without, "
                        "and some do not render")
    if EM_DASH in page:
        warnings.append(f"{page.count(EM_DASH)} em dashes: use a colon, a comma or a full stop")
    for mail in sorted(set(EMAIL.findall(re.sub(r"https?://\S+", "", page)))):
        errors.append(f"e-mail address on a public page: {mail}")
    if re.search(r"img\.shields\.io/discord/\d+", page):
        warnings.append("a badge address holds the number of a Discord server: use a static badge that links to the invite")
    if "raw.githubusercontent.com" in page:
        warnings.append("images are loaded from raw.githubusercontent.com: GitHub limits anonymous requests there, a page with many images "
                        "ends up with broken ones. Use https://cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/...")
    if private:
        slug = ANY_REPOSITORY if private == "*" else re.escape(private)
        hits = len(re.findall(rf"github(?:usercontent)?\.com/{slug}|cdn\.jsdelivr\.net/gh/{slug}", page, flags=re.I))
        if hits and private == "*":
            errors.append(f"{hits} links or images point to a GitHub repository: a visitor cannot open them if that repository is private "
                          "(pass --private owner/repo to look for one repository only)")
        elif hits:
            errors.append(f"{hits} links or images point to the private repository {private}: visitors cannot open them")

    tags = re.findall(r"<img ([^>]*)>", page)
    for attrs in tags:
        if "alt=" not in attrs:
            warnings.append(f"image without alt text: {attrs[:80]}")
    urls = sorted(set(IMG_SRC.findall(page)))
    base, files = own_files(page, images)
    if images and not base:
        warnings.append(f"no image of the page matches a file of {images}: the page's own images could not be checked")
    for rel in files:
        path = os.path.join(images, rel.replace("/", os.sep))
        if not os.path.exists(path):
            errors.append(f"the page loads {rel} but it is not in {images}")
        elif os.path.getsize(path) >= MAX_IMAGE_BYTES:
            errors.append(f"{rel} weighs {os.path.getsize(path) / 1e6:.2f} MB: 2 MB is the limit")
    if online:
        for url in urls:
            if "img.shields.io" in url:
                continue
            try:
                req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "Mozilla/5.0 page-check"})
                with urllib.request.urlopen(req, timeout=20) as r:
                    kind = r.headers.get("Content-Type", "")
                    length = int(r.headers.get("Content-Length") or 0)
                    if not kind.startswith("image/"):
                        errors.append(f"{url}: served as '{kind}', not as an image")
                    if length >= MAX_IMAGE_BYTES:
                        errors.append(f"{url}: {length / 1e6:.2f} MB, 2 MB is the limit")
            except Exception as e:  # noqa: BLE001 - any failure means the image is not reachable
                errors.append(f"{url}: {e}")
    return errors, warnings, {"bytes": size, "words": visible_words(page), "images": len(tags), "unique_images": len(urls), "images_base": base,
                              "files": files}


def uploads(files, base, images, folder):
    """Prints what must be online at the page's own address, and copies it into a folder when one is given."""
    total = 0
    print(f"files the page loads from {base}")
    for rel in files:
        path = os.path.join(images, rel.replace("/", os.sep))
        weight = os.path.getsize(path) if os.path.exists(path) else 0
        total += weight
        shown = f"{weight / 1e3:8.0f} KB" if weight >= 1000 else f"{weight:5d} bytes"
        print(f"  {rel:<40} {shown}" + ("" if weight else "   MISSING"))
        if folder and weight:
            target = os.path.join(folder, rel.replace("/", os.sep))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(path, target)
    print(f"  {len(files)} files, {total / 1e6:.1f} MB in all" + (f", copied to {folder}" if folder else ""))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("page")
    parser.add_argument("--images")
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--private", nargs="?", const="*", metavar="OWNER/REPO")
    parser.add_argument("--uploads", nargs="?", const="", metavar="FOLDER")
    args = parser.parse_args()
    page = open(args.page, encoding="utf-8").read()
    images = args.images
    if not images:
        beside = os.path.join(os.path.dirname(os.path.abspath(args.page)), "images")
        images = beside if os.path.isdir(beside) else None
    errors, warnings, facts = check(page, images, args.online, args.private)
    print(f"{os.path.basename(args.page)}: {facts['bytes']} bytes, {facts['words']} visible words, {facts['images']} images "
          f"({facts['unique_images']} different), own images from {facts['images_base']}")
    for e in errors:
        print("ERROR  ", e)
    seen = set()
    for w in warnings:
        if w not in seen:
            seen.add(w)
            print("warning", w)
    if not errors and not warnings:
        print("nothing to report")
    if args.uploads is not None and facts["images_base"]:
        uploads(facts["files"], facts["images_base"], images, args.uploads)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
