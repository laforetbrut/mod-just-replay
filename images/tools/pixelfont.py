"""A small pixel font for page images: titles, labels and counters that sit well next to Minecraft.

Glyphs are 5 pixels wide at most. A line is 11 rows tall: 2 rows for accents, 7 rows for the
letter body, 2 rows for descenders. Upper case and digits fill the 7 body rows. Latin letters with
the accents of French, Spanish, German, Italian and Portuguese are covered; an unknown character is
drawn as a question mark, so check `missing(text)` when a title comes from another language.

Author: vyrriox
"""
import unicodedata

from PIL import ImageDraw

BODY = {
    "A": [".XXX.", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    "B": ["XXXX.", "X...X", "X...X", "XXXX.", "X...X", "X...X", "XXXX."],
    "C": [".XXX.", "X...X", "X....", "X....", "X....", "X...X", ".XXX."],
    "D": ["XXXX.", "X...X", "X...X", "X...X", "X...X", "X...X", "XXXX."],
    "E": ["XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "XXXXX"],
    "F": ["XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "X...."],
    "G": [".XXX.", "X...X", "X....", "X.XXX", "X...X", "X...X", ".XXX."],
    "H": ["X...X", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    "I": ["XXX", ".X.", ".X.", ".X.", ".X.", ".X.", "XXX"],
    "J": ["..XXX", "...X.", "...X.", "...X.", "...X.", "X..X.", ".XX.."],
    "K": ["X...X", "X..X.", "X.X..", "XX...", "X.X..", "X..X.", "X...X"],
    "L": ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    "M": ["X...X", "XX.XX", "X.X.X", "X.X.X", "X...X", "X...X", "X...X"],
    "N": ["X...X", "XX..X", "X.X.X", "X..XX", "X...X", "X...X", "X...X"],
    "O": [".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    "P": ["XXXX.", "X...X", "X...X", "XXXX.", "X....", "X....", "X...."],
    "Q": [".XXX.", "X...X", "X...X", "X...X", "X.X.X", "X..X.", ".XX.X"],
    "R": ["XXXX.", "X...X", "X...X", "XXXX.", "X.X..", "X..X.", "X...X"],
    "S": [".XXXX", "X....", "X....", ".XXX.", "....X", "....X", "XXXX."],
    "T": ["XXXXX", "..X..", "..X..", "..X..", "..X..", "..X..", "..X.."],
    "U": ["X...X", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    "V": ["X...X", "X...X", "X...X", "X...X", "X...X", ".X.X.", "..X.."],
    "W": ["X...X", "X...X", "X...X", "X.X.X", "X.X.X", "XX.XX", "X...X"],
    "X": ["X...X", "X...X", ".X.X.", "..X..", ".X.X.", "X...X", "X...X"],
    "Y": ["X...X", "X...X", ".X.X.", "..X..", "..X..", "..X..", "..X.."],
    "Z": ["XXXXX", "....X", "...X.", "..X..", ".X...", "X....", "XXXXX"],
    "0": [".XXX.", "X...X", "X..XX", "X.X.X", "XX..X", "X...X", ".XXX."],
    "1": ["..X..", ".XX..", "..X..", "..X..", "..X..", "..X..", ".XXX."],
    "2": [".XXX.", "X...X", "....X", "...X.", "..X..", ".X...", "XXXXX"],
    "3": ["XXXXX", "...X.", "..X..", "...X.", "....X", "X...X", ".XXX."],
    "4": ["...X.", "..XX.", ".X.X.", "X..X.", "XXXXX", "...X.", "...X."],
    "5": ["XXXXX", "X....", "XXXX.", "....X", "....X", "X...X", ".XXX."],
    "6": ["..XX.", ".X...", "X....", "XXXX.", "X...X", "X...X", ".XXX."],
    "7": ["XXXXX", "....X", "...X.", "..X..", ".X...", ".X...", ".X..."],
    "8": [".XXX.", "X...X", "X...X", ".XXX.", "X...X", "X...X", ".XXX."],
    "9": [".XXX.", "X...X", "X...X", ".XXXX", "....X", "...X.", ".XX.."],
    "a": [".....", ".....", ".XXX.", "....X", ".XXXX", "X...X", ".XXXX"],
    "b": ["X....", "X....", "X.XX.", "XX..X", "X...X", "X...X", "XXXX."],
    "c": [".....", ".....", ".XXX.", "X....", "X....", "X...X", ".XXX."],
    "d": ["....X", "....X", ".XX.X", "X..XX", "X...X", "X...X", ".XXXX"],
    "e": [".....", ".....", ".XXX.", "X...X", "XXXXX", "X....", ".XXX."],
    "f": ["..XX.", ".X..X", ".X...", "XXX..", ".X...", ".X...", ".X..."],
    "g": [".....", ".....", ".XXXX", "X...X", "X...X", ".XXXX", "....X", ".XXX."],
    "h": ["X....", "X....", "X.XX.", "XX..X", "X...X", "X...X", "X...X"],
    "i": [".X.", "...", "XX.", ".X.", ".X.", ".X.", "XXX"],
    "j": ["...X", "....", "..XX", "...X", "...X", "...X", "X..X", ".XX."],
    "k": ["X....", "X....", "X..X.", "X.X..", "XX...", "X.X..", "X..X."],
    "l": ["XX.", ".X.", ".X.", ".X.", ".X.", ".X.", "XXX"],
    "m": [".....", ".....", "XX.X.", "X.X.X", "X.X.X", "X...X", "X...X"],
    "n": [".....", ".....", "X.XX.", "XX..X", "X...X", "X...X", "X...X"],
    "o": [".....", ".....", ".XXX.", "X...X", "X...X", "X...X", ".XXX."],
    "p": [".....", ".....", "XXXX.", "X...X", "X...X", "XXXX.", "X....", "X...."],
    "q": [".....", ".....", ".XX.X", "X..XX", "X...X", ".XXXX", "....X", "....X"],
    "r": [".....", ".....", "X.XX.", "XX..X", "X....", "X....", "X...."],
    "s": [".....", ".....", ".XXX.", "X....", ".XXX.", "....X", "XXXX."],
    "t": [".X...", ".X...", "XXX..", ".X...", ".X...", ".X..X", "..XX."],
    "u": [".....", ".....", "X...X", "X...X", "X...X", "X..XX", ".XX.X"],
    "v": [".....", ".....", "X...X", "X...X", "X...X", ".X.X.", "..X.."],
    "w": [".....", ".....", "X...X", "X...X", "X.X.X", "X.X.X", ".X.X."],
    "x": [".....", ".....", "X...X", ".X.X.", "..X..", ".X.X.", "X...X"],
    "y": [".....", ".....", "X...X", "X...X", "X...X", ".XXXX", "....X", ".XXX."],
    "z": [".....", ".....", "XXXXX", "...X.", "..X..", ".X...", "XXXXX"],
    "ß": [".XX..", "X..X.", "X..X.", "X.X..", "X..X.", "X...X", "X.XX."],
    " ": ["...", "...", "...", "...", "...", "...", "..."],
    "!": ["X", "X", "X", "X", "X", ".", "X"],
    "¡": ["X", ".", "X", "X", "X", "X", "X"],
    "?": [".XXX.", "X...X", "....X", "...X.", "..X..", ".....", "..X.."],
    "¿": ["..X..", ".....", "..X..", ".X...", "X....", "X...X", ".XXX."],
    ".": [".", ".", ".", ".", ".", ".", "X"],
    ",": ["..", "..", "..", "..", "..", ".X", ".X", "X."],
    ":": [".", ".", "X", ".", ".", "X", "."],
    ";": ["..", "..", ".X", "..", "..", ".X", ".X", "X."],
    "'": ["X", "X", ".", ".", ".", ".", "."],
    "\"": ["X.X", "X.X", "...", "...", "...", "...", "..."],
    "-": ["....", "....", "....", "XXXX", "....", "....", "...."],
    "+": [".....", "..X..", "..X..", "XXXXX", "..X..", "..X..", "....."],
    "/": ["....X", "....X", "...X.", "..X..", ".X...", "X....", "X...."],
    "\\": ["X....", "X....", ".X...", "..X..", "...X.", "....X", "....X"],
    "(": [".X", "X.", "X.", "X.", "X.", "X.", ".X"],
    ")": ["X.", ".X", ".X", ".X", ".X", ".X", "X."],
    "[": ["XX", "X.", "X.", "X.", "X.", "X.", "XX"],
    "]": ["XX", ".X", ".X", ".X", ".X", ".X", "XX"],
    "<": ["...X", "..X.", ".X..", "X...", ".X..", "..X.", "...X"],
    ">": ["X...", ".X..", "..X.", "...X", "..X.", ".X..", "X..."],
    "|": ["X", "X", "X", "X", "X", "X", "X"],
    "_": [".....", ".....", ".....", ".....", ".....", ".....", "XXXXX"],
    "=": ["....", "....", "XXXX", "....", "XXXX", "....", "...."],
    "*": [".....", "X.X.X", ".XXX.", "XXXXX", ".XXX.", "X.X.X", "....."],
    "%": ["XX..X", "XX..X", "...X.", "..X..", ".X...", "X..XX", "X..XX"],
    "&": [".XX..", "X..X.", "X.X..", ".X...", "X.X.X", "X..X.", ".XX.X"],
    "#": [".X.X.", ".X.X.", "XXXXX", ".X.X.", "XXXXX", ".X.X.", ".X.X."],
    "@": [".XXX.", "X...X", "X.XXX", "X.X.X", "X.XXX", "X....", ".XXX."],
    "$": ["..X..", ".XXXX", "X.X..", ".XXX.", "..X.X", "XXXX.", "..X.."],
    "€": ["..XXX", ".X...", "XXXX.", ".X...", "XXXX.", ".X...", "..XXX"],
    "~": [".....", ".....", ".X...", "X.X.X", "...X.", ".....", "....."],
    "^": ["..X..", ".X.X.", "X...X", ".....", ".....", ".....", "....."],
    "°": [".X.", "X.X", ".X.", "...", "...", "...", "..."],
    "·": [".", ".", ".", "X", ".", ".", "."],
    "«": ["......", "......", ".X..X.", "X..X..", ".X..X.", "......", "......"],
    "»": ["......", "......", ".X..X.", "..X..X", ".X..X.", "......", "......"],
    "♥": [".......", ".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."],
    "★": ["...X...", "...X...", "XXXXXXX", ".XXXXX.", ".XXXXX.", ".XX.XX.", "X.....X"],
    "→": [".......", "....X..", ".....X.", "XXXXXXX", ".....X.", "....X..", "......."],
}

# Characters drawn like another one.
SAME = {"’": "'", "‘": "'", "“": "\"", "”": "\"", "–": "-", "—": "-", " ": " ", " ": " ", "×": "x"}
# Characters drawn as two letters side by side.
PAIRS = {"œ": "oe", "Œ": "OE", "æ": "ae", "Æ": "AE", "…": ".."}

# Accent marks, two rows, drawn above the letter: one shape per glyph width.
ACUTE = {5: ["...X.", "..X.."], 4: ["..X.", ".X.."], 3: ["..X", ".X."]}
GRAVE = {5: [".X...", "..X.."], 4: [".X..", "..X."], 3: ["X..", ".X."]}
CIRCUMFLEX = {5: ["..X..", ".X.X."], 4: [".XX.", "X..X"], 3: [".X.", "X.X"]}
DIAERESIS = {5: [".....", ".X.X."], 4: ["....", "X..X"], 3: ["...", "X.X"]}
TILDE = {5: [".XX.X", "X..X."], 4: [".X.X", "X.X."], 3: [".XX", "XX."]}
RING = {5: ["..X..", "..X.."], 4: [".XX.", ".XX."], 3: [".X.", ".X."]}

ACCENTED = {}
for _marks, _table in ((ACUTE, "áÁéÉíÍóÓúÚýÝ"), (GRAVE, "àÀèÈìÌòÒùÙ"), (CIRCUMFLEX, "âÂêÊîÎôÔûÛ"), (DIAERESIS, "äÄëËïÏöÖüÜÿ"),
                       (TILDE, "ãÃñÑõÕ"), (RING, "åÅ")):
    for _ch in _table:
        ACCENTED[_ch] = (unicodedata.normalize("NFD", _ch)[0], _marks)
# Cedillas hang under the letter.
CEDILLA = {"ç": "c", "Ç": "C"}

ACCENT_ROWS = 2
BODY_ROWS = 7
LINE_ROWS = 11


def _pad(rows, width):
    rows = list(rows)[:LINE_ROWS]
    while len(rows) < LINE_ROWS:
        rows.append("." * width)
    return rows


def _single(ch):
    if ch in ACCENTED:
        base, marks = ACCENTED[ch]
        rows = list(BODY[base])
        width = len(rows[0])
        mark = marks[width]
        if base.islower():
            # Lower case: the mark takes the two empty rows above the x height. The dot of the i goes.
            rows = ["." * width] * ACCENT_ROWS + mark + rows[2:]
        else:
            rows = mark + rows
        return _pad(rows, width)
    if ch in CEDILLA:
        rows = list(BODY[CEDILLA[ch]])
        width = len(rows[0])
        return _pad(["." * width] * ACCENT_ROWS + rows + ["..X..", ".XX.."], width)
    rows = BODY.get(SAME.get(ch, ch)) or BODY["?"]
    width = len(rows[0])
    return _pad(["." * width] * ACCENT_ROWS + list(rows), width)


def glyph(ch):
    """Rows of the glyph on the 11 row line, as strings of '.' and 'X'."""
    if ch in PAIRS:
        left, right = (_single(c) for c in PAIRS[ch])
        return [a + "." + b for a, b in zip(left, right)]
    return _single(ch)


def known(ch):
    return ch in BODY or ch in SAME or ch in PAIRS or ch in ACCENTED or ch in CEDILLA


def missing(text):
    """The characters of the text this font cannot draw (they would show as question marks)."""
    return sorted({ch for ch in text if not known(ch)})


def width(text, scale=1, spacing=1):
    total = 0
    for ch in text:
        total += (len(glyph(ch)[0]) + spacing) * scale
    return max(0, total - spacing * scale)


def height(scale=1):
    """Height of the letter body (cap height), without accents and descenders."""
    return BODY_ROWS * scale


def fit(text, max_width, largest=9, smallest=2, spacing=1):
    """The largest scale at which the text is at most max_width pixels wide."""
    for scale in range(largest, smallest - 1, -1):
        if width(text, scale, spacing) <= max_width:
            return scale
    return smallest


def draw(img, text, x, y, scale, color, shadow=None, spacing=1, shadow_offset=None):
    """Draws the text with the top of the capital letters at y. Returns the x after the text."""
    d = ImageDraw.Draw(img)
    off = shadow_offset if shadow_offset is not None else max(1, scale // 2)
    cx = x
    y0 = y - ACCENT_ROWS * scale
    for ch in text:
        rows = glyph(ch)
        for pass_color, dx, dy in ((shadow, off, off), (color, 0, 0)):
            if pass_color is None:
                continue
            for gy, row in enumerate(rows):
                for gx, c in enumerate(row):
                    if c == "X":
                        px = cx + gx * scale + dx
                        py = y0 + gy * scale + dy
                        d.rectangle([px, py, px + scale - 1, py + scale - 1], fill=pass_color)
        cx += (len(rows[0]) + spacing) * scale
    return cx - spacing * scale


def draw_centered(img, text, cx, y, scale, color, shadow=None, spacing=1):
    w = width(text, scale, spacing)
    return draw(img, text, int(cx - w / 2), y, scale, color, shadow, spacing)
