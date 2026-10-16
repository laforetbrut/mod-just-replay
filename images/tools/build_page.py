"""Builds the CurseForge page of a mod (curseforge_page.md) from its description in page.toml.

    python build_page.py page.toml            # writes the page, prints a report
    python build_page.py page.toml --check    # report only

The page is HTML made for CurseForge's filter: it drops the align attribute and most styles, so
everything is centered by wrappers that carry text-align, section titles are images, and the names
of the features are colored with span tags. The markup is kept as short as it can be, and the same
structure is written for every language, so no translation can be forgotten.

Author: vyrriox
"""
import argparse
import html
import os
import re
import sys
import tomllib
from urllib.parse import quote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from theme import Theme, to_hex  # noqa: E402

OPEN = '<div align="center" style="text-align:center">'
CLOSE = "</div>"
BLANK = "<p>&nbsp;</p>"
MINT = "#7ee0c3"

LANGUAGE_NAMES = {"en": "English", "fr": "Français", "es": "Español", "de": "Deutsch", "it": "Italiano", "pt": "Português",
                  "nl": "Nederlands", "pl": "Polski", "sv": "Svenska", "tr": "Türkçe", "id": "Bahasa Indonesia"}
YES = {"en": "Yes", "fr": "Oui", "es": "Sí", "de": "Ja", "it": "Sì", "pt": "Sim", "nl": "Ja", "pl": "Tak", "sv": "Ja", "tr": "Evet"}
NO = {"en": "No", "fr": "Non", "es": "No", "de": "Nein", "it": "No", "pt": "Não", "nl": "Nee", "pl": "Nie", "sv": "Nej", "tr": "Hayır"}
AUTHOR = {"en": "Author", "fr": "Auteur", "es": "Autor", "de": "Autor", "it": "Autore", "pt": "Autor", "nl": "Auteur", "pl": "Autor"}
LICENSE = {"en": "License", "fr": "Licence", "es": "Licencia", "de": "Lizenz", "it": "Licenza", "pt": "Licença", "nl": "Licentie", "pl": "Licencja"}

# Budgets. The page should be read in a minute: the images show, the text only names and states.
MAX_PITCH_WORDS = 40
MAX_ITEM_WORDS = 30         # in the list and grid layouts: a name, then one sentence under it
MAX_LINE_WORDS = 16         # in the lines layout: the name and its text share one line
MAX_ITEMS = 6
MAX_SECTIONS = 7
MAX_WORDS = 280             # per language, what a visitor reads (alt texts are not counted)
WARN_BYTES = 40_000
MAX_BYTES = 52_000          # the longest description found on CurseForge is 53 KB; no limit is documented

# Link badges: (label, message, color, logo) by kind, then by the host of a donation link.
LINK_BADGES = {
    "discord": ("Discord", "Join", "5865F2", "discord"),
    "website": ("Website", "Visit", None, "googlechrome"),
    "source": ("Source", "GitHub", "181717", "github"),
    "wiki": ("Wiki", "Read", None, "bookstack"),
    "docs": ("Docs", "Read", None, "readthedocs"),
    "issues": ("Issues", "Report", "181717", "github"),
    "support": ("Support", "Get help", None, None),
    "modrinth": ("Modrinth", "Download", "00AF5C", "modrinth"),
    "curseforge": ("CurseForge", "Download", "F16436", "curseforge"),
    "youtube": ("YouTube", "Watch", "FF0000", "youtube"),
    "partners": ("Partners", "See", "F28C28", None),
    "donate": ("Donate", "Support", None, None),
}
DONATION_HOSTS = {
    "stripe.com": ("Donate", "Stripe", "635BFF", "stripe"),
    "ko-fi.com": ("Donate", "Ko-fi", "FF5E5B", "kofi"),
    "patreon.com": ("Donate", "Patreon", "F96854", "patreon"),
    "tipeee.com": ("Donate", "Tipeee", "D64758", "tipeee"),
    "paypal.com": ("Donate", "PayPal", "00457C", "paypal"),
    "paypal.me": ("Donate", "PayPal", "00457C", "paypal"),
    "buymeacoffee.com": ("Donate", "Buy me a coffee", "FFDD00", "buymeacoffee"),
    "liberapay.com": ("Donate", "Liberapay", "F6C915", "liberapay"),
    "opencollective.com": ("Donate", "Open Collective", "7FADF2", "opencollective"),
    "github.com": ("Sponsor", "GitHub", "EA4AAA", "githubsponsors"),
}
# Icon of a badge drawn in the theme, by kind of link or fact (names of the built-in set).
BADGE_ICONS = {"discord": "chat", "website": "globe", "source": "book", "wiki": "book", "docs": "book", "issues": "warning",
               "support": "question", "modrinth": "download", "curseforge": "download", "youtube": "play", "partners": "users",
               "donate": "heart", "minecraft": "cube", "loaders": "puzzle", "side": "server", "java": "gear", "license": "key"}


class SpecError(Exception):
    pass


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", str(text).lower()).strip("-") or "badge"


def words(text):
    """Words a visitor reads: tags and entities are left out; a version number or a hyphenated word is one word."""
    text = re.sub(r"&#?\w+;", " ", re.sub(r"<[^>]+>", " ", text or ""))
    return len(re.findall(r"[^\W_]+(?:[.'’-][^\W_]+)*", text))


def polish(text, lang):
    """What a careful typesetter does and nobody wants to type: a bare & becomes an entity, a command
    in <code> stays on one line, and French punctuation keeps its space without ever starting a line."""
    text = re.sub(r"&(?!#?\w+;)", "&amp;", text)
    text = re.sub(r"<code>(.*?)</code>", lambda m: "<code>" + m.group(1).replace(" ", "&nbsp;") + "</code>", text, flags=re.S)
    if lang == "fr":
        parts = re.split(r"(<[^>]+>)", text)
        for k in range(0, len(parts), 2):               # the text between the tags
            parts[k] = re.sub(r" (?=[:;!?»%])", "&nbsp;", parts[k]).replace("« ", "«&nbsp;")
        text = "".join(parts)
    return text


def shield(label, message, color, logo=None, label_color=None):
    """Address of a shields.io badge in the 'for the badge' style."""
    def part(s):
        return quote(str(s).replace("-", "--").replace("_", "__").replace(" ", "_"), safe="_.~")
    url = f"https://img.shields.io/badge/{part(label)}-{part(message)}-{color.lstrip('#')}?style=for-the-badge"
    if logo:
        url += f"&logo={logo}&logoColor=white"
    if label_color:
        url += f"&labelColor={label_color.lstrip('#')}"
    return url


class Page:
    def __init__(self, spec_path):
        self.path = os.path.abspath(spec_path)
        self.dir = os.path.dirname(self.path)
        try:
            with open(self.path, "rb") as f:
                self.spec = tomllib.load(f)
        except tomllib.TOMLDecodeError as e:
            raise SpecError(f"{os.path.basename(self.path)} is not valid TOML: {e}. A text with both quotes and apostrophes goes "
                            "between three single quotes: '''...'''") from None
        page = self.spec.get("page", {})
        self.languages = page.get("languages", ["en"])
        self.base = page.get("images_base", "").rstrip("/") + "/"
        theme_path = os.path.join(self.dir, page.get("theme", "theme.json"))
        if not os.path.exists(theme_path):
            raise SpecError(f"the theme {theme_path} does not exist: make it from the mod's own art with theme.py extract, "
                            "or from a color with theme.py new")
        self.theme = Theme.load(theme_path)
        self.accent = to_hex(self.theme.page_accent)
        self.light = to_hex(self.theme.page_light)
        self.out = os.path.normpath(os.path.join(self.dir, page.get("out", "curseforge_page.md")))
        self.images_dir = os.path.normpath(os.path.join(self.dir, page.get("images_dir", "..")))
        self.repeat_banners = bool(page.get("repeat_banners", False))
        self.roomy = bool(page.get("roomy", False))           # a blank line after every section: more air, a taller page
        self.badge_style = self.spec.get("badges", {}).get("style", "pixel")
        if self.badge_style not in ("pixel", "shields"):
            raise SpecError(f"badges.style: '{self.badge_style}' is not a style (pixel, shields)")
        self.badge_files = {}
        self.badge_rows = {}
        self.warnings = []
        self.count = {lang: 0 for lang in self.languages}
        self.blocks = {lang: {} for lang in self.languages}          # words by part of the page, to say where to cut
        self.part = "header"

    # ------------------------------------------------------------ helpers

    def warn(self, message):
        if message not in self.warnings:
            self.warnings.append(message)

    def pick(self, value, lang, what, required=True):
        """The raw text of a field in a language. A plain string is the same in every language."""
        if value is None:
            if required:
                raise SpecError(f"{what}: missing")
            return ""
        if isinstance(value, str):
            return value.strip()
        if lang not in value:
            raise SpecError(f"{what}: no text for the language '{lang}' (it has: {', '.join(value)})")
        return value[lang].strip()

    def t(self, value, lang, what, required=True):
        """A text shown on the page: counted in the words of its language, and typeset."""
        text = self.pick(value, lang, what, required)
        self.add_words(lang, words(text))
        # A link written in a text takes the color of the page's own links.
        return re.sub(r'<a href="([^"]+)">', lambda m: f'<a href="{m.group(1)}" style="color:{self.accent}">', polish(text, lang))

    def add_words(self, lang, n):
        self.count[lang] += n
        self.blocks[lang][self.part] = self.blocks[lang].get(self.part, 0) + n

    def img(self, file, alt, width=800):
        return f'<img src="{self.base}{file}" alt="{html.escape(alt, quote=True)}" width="{width}">'

    def name(self, text, size=20):
        return f'<span style="color:{self.accent};font-size:{size}px"><strong>{text}</strong></span>'

    def big(self, text):
        return f'<span style="font-size:24px;color:{self.light}"><strong>{text}</strong></span>'

    def icon(self, name):
        if not name:
            return ""
        if os.path.isdir(self.images_dir) and not os.path.exists(os.path.join(self.images_dir, "icons", name + ".png")):
            self.warn(f"icon '{name}' is not in {os.path.join(self.images_dir, 'icons')}: run export_icons.py")
        return f'<img src="{self.base}icons/{name}.png" alt="" width="32"> '

    def badge(self, url, image, alt):
        # No space inside the link: CurseForge underlines it as if it were text.
        tag = f'<img src="{image}" alt="{html.escape(alt, quote=True)}">'
        return f'<a href="{url}">{tag}</a>' if url else tag

    def themed_badge(self, url, file, label, message, icon=None, alt=False, color=None, logo=None, lang=None, row=None, drawn=None):
        """A badge of the page. In the default style it is an image drawn in the theme (badge-<file>.png,
        made by titles.py); with `style = "shields"` in [badges] it comes from shields.io instead.
        A label or a message given per language makes one image per language. `drawn` is what the
        image shows when it is shorter than the full text: (label, message), either one may be empty."""
        if isinstance(label, dict) or isinstance(message, dict):
            label = label[lang] if isinstance(label, dict) else label
            message = message[lang] if isinstance(message, dict) else message
            file = f"{file}-{lang}"
        text = f"{label}: {message}" if message else str(label)
        if self.badge_style == "shields":
            return self.badge(url, shield(label, message, color or to_hex(self.theme.accent_dark), logo), text)
        name = f"badge-{file}.png"
        shown = drawn if drawn is not None and icon else (label, message)
        self.badge_files[name] = (str(shown[0]).upper(), str(shown[1]).upper(), icon, alt)
        if row:
            self.badge_rows.setdefault(row, []).append(name)
        return self.badge(url, self.base + name, text)

    def link_badge(self, kind, value, row=None):
        url = value if isinstance(value, str) else value.get("url", "")
        label, message, color, logo = LINK_BADGES.get(kind, (kind.capitalize(), "Open", None, None))
        if kind == "donate":
            host = urlparse(url).netloc.lower().removeprefix("www.").removeprefix("buy.")
            for known, style in DONATION_HOSTS.items():
                if host == known or host.endswith("." + known):
                    label, message, color, logo = style
        icon = BADGE_ICONS.get(kind)
        drawn = (label, "")                     # drawn in the theme, a link is a button: its icon and its name
        if isinstance(value, dict):
            label, message = value.get("label", label), value.get("message", message)
            color, logo, icon = value.get("color", color), value.get("logo", logo), value.get("icon", icon)
            drawn = (label, value.get("message", ""))
        return self.themed_badge(url, kind, label, message, icon, False, color, logo, row=row, drawn=drawn)

    def title_file(self, section):
        title = section.get("title")
        same = isinstance(title, str) or len({title.get(lang) for lang in self.languages}) == 1
        return (lambda lang: f"title-{section['id']}.gif") if same else (lambda lang: f"title-{section['id']}-{lang}.gif")

    # ------------------------------------------------------------ blocks

    def header(self):
        out = [OPEN]
        hero = self.spec.get("hero")
        if hero:
            alt = hero.get("alt", self.spec.get("mod", {}).get("name", ""))
            if not isinstance(alt, str):
                alt = " / ".join(dict.fromkeys(alt[lang] for lang in self.languages if lang in alt))
            out.append(f'<p>{self.img(hero["file"], alt)}</p>')
        links = [self.link_badge(kind, value, row="links") for kind, value in self.spec.get("links", {}).items() if value]
        if links:
            out.append("<p>" + " ".join(links) + "</p>")
        facts = []
        badges = self.spec.get("badges", {})
        for key, label in (("minecraft", "Minecraft"), ("loaders", "Loaders"), ("side", "Side"), ("java", "Java"), ("license", "License")):
            value = badges.get(key)
            if isinstance(value, dict):
                # { value = ..., label = "..." }: another word for the label (a single loader can carry the version).
                label, value = value.get("label", label), value.get("value")
            if value:
                message = " | ".join(str(v) for v in value) if isinstance(value, list) else str(value)
                if key == "loaders" and label == "Loaders" and not (isinstance(value, list) and len(value) > 1):
                    label = "Loader"
                # Loaders and sides name themselves: their icon is label enough, and the row stays on one line.
                drawn = ("", message) if key in ("loaders", "side") and label in ("Loaders", "Loader", "Side") else None
                facts.append(self.themed_badge("", key, label, message, BADGE_ICONS.get(key), True, row="facts", drawn=drawn))
        for extra in badges.get("extra", []):
            if extra.get("image"):
                # A badge served by someone else, such as a live download count.
                facts.append(self.badge(extra.get("url", ""), extra["image"], extra.get("alt", "")))
            else:
                facts.append(self.themed_badge(extra.get("url", ""), "extra-" + slug(extra["label"]), extra["label"], extra["message"],
                                               extra.get("icon"), True, extra.get("color"), extra.get("logo"), row="facts"))
        if facts:
            out.append("<p>" + " ".join(facts) + "</p>")
        out += [CLOSE, ""]
        return out

    def intro(self, lang):
        self.part = "intro"
        intro = self.spec.get("intro", {})
        out = [OPEN]
        if len(self.languages) > 1:
            out.append(f'<p>{self.img(f"lang-{lang}.gif", LANGUAGE_NAMES.get(lang, lang.upper()))}</p>')
        pitch = self.t(intro.get("pitch"), lang, "intro.pitch")
        if words(pitch) > MAX_PITCH_WORDS:
            self.warn(f"[{lang}] the pitch has {words(pitch)} words: say what the mod is in {MAX_PITCH_WORDS} or fewer")
        out.append(f'<p><span style="font-size:18px;color:{self.light}">{pitch}</span></p>')
        if intro.get("tagline"):
            out.append(f'<p><span style="font-size:20px;color:{self.accent}"><em>{self.t(intro["tagline"], lang, "intro.tagline")}</em></span></p>')
        if intro.get("credit"):
            out.append(f'<p><strong>{self.t(intro["credit"], lang, "intro.credit")}</strong></p>')
        out += [CLOSE, ""]
        return out

    def banner(self, entry, lang, where):
        """A banner of a section. One that holds no words (the same file for every language) is shown
        once, in the first language: a visitor of the second one has scrolled past it already, and a
        page in two languages should not be twice as tall. `repeat_banners = true` shows it in each."""
        file = entry.get("file")
        if isinstance(file, str):
            if lang != self.languages[0] and not self.repeat_banners:
                return None
        else:
            file = file[lang]
        return f'<p>{self.img(file, self.pick(entry.get("alt"), lang, where + ".alt"))}</p>'

    ITEM_KEYS = {"icon", "name", "text", "url"}

    def item_html(self, item, lang, where, limit=MAX_ITEM_WORDS, colon=False):
        odd = sorted(set(item) - self.ITEM_KEYS)
        if odd:
            # In TOML a key written under [[section.item]] belongs to that item, even when it was meant for the section.
            self.warn(f"{where}: {', '.join(odd)} is not a key of an item and is ignored; a section's lead or note goes right "
                      "under [[section]], before its first [[section.item]]")
        name = self.t(item.get("name"), lang, where + ".name")
        if colon:
            name += "&nbsp;:" if lang == "fr" else ":"
        head = self.icon(item.get("icon")) + self.name(name, 18 if colon else 20)
        text = self.t(item.get("text"), lang, where + ".text")
        if words(text) > limit:
            self.warn(f"[{lang}] {where}: {words(text)} words. {limit} at most here"
                      + (" (the name and its text share one line)" if colon else "") + "; the rest belongs in the docs")
        return head, text

    def section(self, section, lang):
        sid = section.get("id")
        if not sid:
            raise SpecError("a section has no id")
        where = f"section '{sid}'"
        self.part = sid
        layout = section.get("layout", "lines")
        items = section.get("item", [])
        if layout in ("lines", "list", "grid") and len(items) > MAX_ITEMS:
            self.warn(f"{where} has {len(items)} items: keep the {MAX_ITEMS} that matter most to a player, or split the section")
        title = self.pick(section.get("title"), lang, where + ".title")
        self.add_words(lang, words(title))
        out = [OPEN, f'<h3>{self.img(self.title_file(section)(lang), title)}</h3>']
        banners = sorted(section.get("banner", []), key=lambda b: b.get("after", 0))
        placed = 0

        def banners_before(index):
            nonlocal placed
            while placed < len(banners) and banners[placed].get("after", 0) <= index:
                tag = self.banner(banners[placed], lang, where + ".banner")
                if tag:
                    out.append(tag)
                placed += 1

        if section.get("lead"):
            out.append(f'<p>{self.t(section["lead"], lang, where + ".lead")}</p>')
        if layout == "lines":
            for k, item in enumerate(items):
                banners_before(k)
                head, text = self.item_html(item, lang, f"{where} item {k + 1}", MAX_LINE_WORDS, colon=True)
                out.append(f"<p>{head} {text}</p>")
        elif layout == "list":
            for k, item in enumerate(items):
                banners_before(k)
                head, text = self.item_html(item, lang, f"{where} item {k + 1}")
                out.append(f"<p>{head}<br>\n{text}</p>")
        elif layout == "grid":
            banners_before(0)
            out.append('<div align="center" style="display:table;margin-left:auto;margin-right:auto">')
            out.append('<table style="margin-left:auto;margin-right:auto">')
            cells = []
            for k, item in enumerate(items):
                head, text = self.item_html(item, lang, f"{where} item {k + 1}")
                cells.append(f'<td align="center" style="text-align:center;width:50%">{head}<br>{text}</td>')
            if len(cells) % 2:
                cells.append("<td></td>")
            for k in range(0, len(cells), 2):
                out.append(f"<tr>{cells[k]}{cells[k + 1]}</tr>")
            out += ["</table>", CLOSE]
        elif layout == "steps":
            banners_before(0)
            for k, item in enumerate(items):
                odd = sorted(set(item) - self.ITEM_KEYS)
                if odd:
                    self.warn(f"{where} step {k + 1}: {', '.join(odd)} is not a key of a step and is ignored; a section's lead or "
                              "note goes right under [[section]], before its first [[section.item]]")
                out.append(f'<p>{self.name(f"{k + 1}.")} {self.t(item.get("text"), lang, f"{where} step {k + 1}")}</p>')
        elif layout == "links":
            banners_before(0)
            links = [f'<a href="{item["url"]}" style="color:{self.accent}">{self.t(item.get("name"), lang, f"{where} link {k + 1}")}</a>'
                     for k, item in enumerate(items)]
            per_line = 5 if len(links) > 5 else len(links)
            lines = [" &nbsp;|&nbsp; ".join(links[k:k + per_line]) for k in range(0, len(links), per_line)]
            out.append("<p>" + "<br>\n".join(lines) + "</p>")
        elif layout == "table":
            banners_before(0)
            table = section.get("table", {})
            out.append('<div align="center" style="display:table;margin-left:auto;margin-right:auto">')
            out.append('<table style="margin-left:auto;margin-right:auto">')
            columns = table.get("columns", {})
            columns = columns if isinstance(columns, list) else columns[lang]
            self.add_words(lang, sum(words(c) for c in columns))
            out.append("<tr>" + "".join(f"<th>{c}</th>" for c in columns) + "</tr>")
            for r, row in enumerate(table.get("row", [])):
                cells = [f"<td>{self.t(row.get('name'), lang, f'{where} row {r + 1}')}</td>"]
                for cell in row.get("cells", []):
                    cell = cell if isinstance(cell, str) else cell[lang]
                    if cell.lower() == "yes" or cell.lower().startswith("yes,"):
                        rest = cell[3:]
                        cells.append(f'<td><span style="color:{MINT}"><strong>{YES.get(lang, "Yes")}{rest}</strong></span></td>')
                    elif cell.lower() == "no":
                        cells.append(f"<td>{NO.get(lang, 'No')}</td>")
                    else:
                        cells.append(f"<td>{cell}</td>")
                out.append("<tr>" + "".join(cells) + "</tr>")
            out += ["</table>", CLOSE]
            for k, item in enumerate(items):
                head, text = self.item_html(item, lang, f"{where} item {k + 1}")
                out.append(f"<p>{head}<br>\n{text}</p>")
        elif layout == "text":
            banners_before(0)
            for k, block in enumerate(section.get("text", [])):
                out.append(f"<p>{self.t(block, lang, f'{where} text {k + 1}')}</p>")
        else:
            raise SpecError(f"{where}: unknown layout '{layout}' (lines, list, grid, steps, links, table, text)")
        banners_before(10 ** 6)
        if section.get("note"):
            out.append(f'<p><em>{self.t(section["note"], lang, where + ".note")}</em></p>')
        out += ([BLANK] if self.roomy else []) + [CLOSE, ""]
        return out

    def block(self, entry, lang, where):
        """A partner or an extra block: optional title, text, banner image and badge, all linking to its url."""
        out = []
        url = entry.get("url", "")
        if entry.get("title"):
            out.append(f'<h3>{self.big(self.t(entry["title"], lang, where + ".title"))}</h3>')
        if entry.get("text"):
            out.append(f'<p>{self.t(entry["text"], lang, where + ".text")}</p>')
        if entry.get("image"):
            alt = self.pick(entry.get("alt", entry.get("title", "")), lang, where + ".alt", required=False)
            tag = f'<img src="{entry["image"]}" alt="{html.escape(re.sub("<[^>]+>", "", alt), quote=True)}" width="{entry.get("width", 800)}">'
            out.append(f'<p><a href="{url}">{tag}</a></p>' if url else f"<p>{tag}</p>")
        if entry.get("badge"):
            b = entry["badge"]
            out.append("<p>" + self.themed_badge(url, where.replace(" ", "-"), b["label"], b.get("message", ""), b.get("icon"), False,
                                                 b.get("color"), b.get("logo"), lang) + "</p>")
        return out

    def closing(self, lang):
        """Support line, partners and extras of one language."""
        self.part = "support, partner and extra blocks"
        out = []
        support = self.spec.get("support")
        if support and support.get("url"):
            text = self.t(support.get("text", {"en": "A problem or an idea?", "fr": "Un problème ou une idée ?"}), lang, "support.text")
            link = self.t(support.get("link", {"en": "Get in touch", "fr": "Contactez-nous"}), lang, "support.link")
            out.append(f'<p>{text} <a href="{support["url"]}" style="color:{self.accent}">{link}</a>.</p>')
        blocks = []
        for kind in ("partner", "extra"):
            for k, entry in enumerate(self.spec.get(kind, [])):
                if not entry.get("shared") and entry.get("lang", lang) == lang:
                    blocks += ([BLANK] if blocks else []) + self.block(entry, lang, f"{kind} {k + 1}")
        if blocks:
            out += [f'<p>{self.img("divider.gif", "")}</p>'] + blocks
        return [OPEN] + out + [CLOSE, ""] if out else []

    def shared_blocks(self):
        """Partner and extra blocks marked `shared = true`: shown once, after every language, their
        texts in each language one under the other."""
        out = []
        for kind in ("partner", "extra"):
            for k, entry in enumerate(self.spec.get(kind, [])):
                if not entry.get("shared"):
                    continue
                where = f"{kind} {k + 1}"
                self.part = "support, partner and extra blocks"
                first = self.languages[0]
                if entry.get("title"):
                    titles = [self.t(entry["title"], lang, where + ".title") for lang in self.languages]
                    out.append(f'<h3>{self.big(" / ".join(dict.fromkeys(titles)))}</h3>')
                if entry.get("text"):
                    lines = list(dict.fromkeys(self.t(entry["text"], lang, where + ".text") for lang in self.languages))
                    out.append("<p>" + "<br>".join([lines[0]] + [f"<em>{line}</em>" for line in lines[1:]]) + "</p>")
                rest = self.block({key: value for key, value in entry.items() if key not in ("title", "text")}, first, where)
                out += rest + [BLANK]
        return [OPEN] + out[:-1] + [CLOSE, ""] if out else []

    def footer(self):
        self.part = "footer"
        footer = self.spec.get("footer", {})
        page = self.spec.get("page", {})
        out = [OPEN, f'<p>{self.img("divider.gif", "")}</p>']
        if footer.get("title"):
            titles = [self.t(footer["title"], lang, "footer.title") for lang in self.languages]
            out.append(f'<p>{self.big(" / ".join(dict.fromkeys(titles)))}</p>')
        if footer.get("text"):
            lines = [self.t(footer["text"], lang, "footer.text") for lang in self.languages]
            lines = list(dict.fromkeys(lines))
            out.append("<p>" + "<br>".join([f"<strong>{lines[0]}</strong>"] + [f"<em>{line}</em>" for line in lines[1:]]) + "</p>")
        for kind in footer.get("badges", []):
            value = self.spec.get("links", {}).get(kind)
            if value:
                out.append(f"<p>{self.link_badge(kind, value)}</p>")

        def label(table):
            return " / ".join(dict.fromkeys(table.get(lang, table["en"]) for lang in self.languages))

        credit = []
        if page.get("author"):
            credit.append(f"<strong>{label(AUTHOR)}:</strong> {page['author']}")
        if page.get("license"):
            credit.append(f"<strong>{label(LICENSE)}:</strong> {page['license']}")
        if credit:
            out.append("<p>" + " &nbsp;|&nbsp; ".join(credit) + "</p>")
        out.append(CLOSE)
        return out

    # ------------------------------------------------------------ page

    def build(self):
        sections = self.spec.get("section", [])
        ids = [s.get("id") for s in sections]
        if len(set(ids)) != len(ids):
            raise SpecError("two sections have the same id")
        if len(sections) > MAX_SECTIONS:
            self.warn(f"{len(sections)} sections: a page reads well with {MAX_SECTIONS} at most")
        self.count = {lang: 0 for lang in self.languages}
        self.blocks = {lang: {} for lang in self.languages}
        out = self.header()
        for lang in self.languages:
            out += self.intro(lang)
            for section in sections:
                out += self.section(section, lang)
            out += self.closing(lang)
            if lang != self.languages[-1]:
                out += [BLANK, ""]
        out += self.shared_blocks()
        out += self.footer()
        text = re.sub(r"\n{3,}", "\n\n", "\n".join(out)) + "\n"
        for lang, total in self.count.items():
            if total > MAX_WORDS:
                parts = ", ".join(f"{name} {n}" for name, n in sorted(self.blocks[lang].items(), key=lambda kv: -kv[1]))
                self.warn(f"[{lang}] {total} words on the page: aim for {MAX_WORDS} or fewer, a visitor decides in a minute. By part: {parts}")
        size = len(text.encode("utf-8"))
        if size > MAX_BYTES:
            self.warn(f"the page weighs {size} bytes: more than any description found on CurseForge ({MAX_BYTES}). Cut text or icons.")
        elif size > WARN_BYTES:
            self.warn(f"the page weighs {size} bytes: over {WARN_BYTES}, look for what can go")
        return text

    def titles(self):
        """Every title image the page needs: (file name, text, icon, alternate style)."""
        out = []
        for section in self.spec.get("section", []):
            name = self.title_file(section)
            seen = set()
            for lang in self.languages:
                file = name(lang)
                if file not in seen:
                    seen.add(file)
                    title = section["title"] if isinstance(section["title"], str) else section["title"][lang]
                    out.append((file, title, section.get("icon"), False))
        if len(self.languages) > 1:
            for lang in self.languages:
                out.append((f"lang-{lang}.gif", LANGUAGE_NAMES.get(lang, lang.upper()), self.spec.get("page", {}).get("language_icon", "chat"), True))
        return out

    def badges(self):
        """Every badge image the page needs: {file name: (label, message, icon, alternate style)}.
        Empty with the shields style, where the badges are not files of the page."""
        if not self.badge_files and self.badge_style == "pixel":
            self.build()
        return self.badge_files

    def icons(self):
        """Names of the icons the page uses: next to feature names, and on the title ribbons."""
        names = []
        for section in self.spec.get("section", []):
            for name in [section.get("icon")] + [item.get("icon") for item in section.get("item", [])]:
                if name and name not in names:
                    names.append(name)
        return names


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("spec")
    parser.add_argument("--check", action="store_true", help="report only, write nothing")
    args = parser.parse_args()
    try:
        page = Page(args.spec)
        text = page.build()
    except SpecError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except KeyError as e:
        print(f"error: a required key is missing in {os.path.basename(args.spec)}: {e}", file=sys.stderr)
        return 1
    size = len(text.encode("utf-8"))
    images = text.count("<img ")
    print(f"{os.path.basename(page.out)}: {size} bytes, {images} images, " + ", ".join(f"{lang}: {n} words" for lang, n in page.count.items()))
    for w in page.warnings:
        print("warning:", w)
    if not args.check:
        with open(page.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"written to {page.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
