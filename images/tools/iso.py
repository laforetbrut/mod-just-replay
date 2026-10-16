"""Pictures of the blocks and models of a mod, drawn from its own textures and model files, seen
from above a corner the way the inventory shows a block. This is the art of a page when the game
was not recorded: single objects (a machine, an item that only exists as a model) and small scenes
made of blocks.

    from iso import Assets, Scene
    assets = Assets("src/main/resources/assets", "modid")
    scene = Scene(assets)
    scene.fill(0, 0, 0, 4, 0, 4, "block/floor")                       # a floor of 5 x 5 blocks
    scene.cube(2, 1, 2, {"end": "block/log_top", "side": "block/log"})
    scene.model("block/machine", 1, 1, 3)                              # the elements of a JSON model
    scene.cube(3, 0, 1, "block/water", alpha=0.6, height=0.9)          # a liquid
    picture = scene.render(scale=48)                                   # picture.image, picture.at(x, y, z)

x runs east, y up, z south, one unit per block, as in the game. The scene is seen from its
north-east corner, like a block in the inventory: the east face of a block is on the left, its
north face on the right. So the walls at the back of a room are its west wall (smallest x) and its
south wall (largest z); a wall on the north or east side would hide what stands behind it.
`picture.at(x, y, z)` gives the pixel where a point of the scene was drawn, to hang an overlay on
it (a label over a machine, a sparkle on an ore). `trim`, `fit`, `flat` and `outline` prepare such a picture for another use: an
icon, an ornament, a sticker.

Nothing here comes from the game itself: a picture made this way is an illustration, not a
screenshot, and must be presented as such. Requires Pillow and numpy.

Author: vyrriox
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

# Brightness of a face by the side it looks at, as the game shades blocks.
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "west": 0.62, "east": 0.62}
NEIGHBOUR = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}


def corners(face, a, b):
    """The four corners of a face of the box a..b, in the order the game maps a texture on them:
    top left, bottom left, bottom right, top right of the texture area."""
    x0, y0, z0 = a
    x1, y1, z1 = b
    return {
        "down": [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
        "up": [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
        "north": [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
        "south": [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
        "west": [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
        "east": [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
    }[face]


class Assets:
    """Textures and models of a mod, read from its assets folder."""

    def __init__(self, assets_root, namespace):
        self.root = assets_root
        self.namespace = namespace
        self._textures = {}
        self._models = {}

    def _path(self, kind, name, ext):
        namespace, _, path = name.partition(":") if ":" in name else (self.namespace, "", name)
        return os.path.join(self.root, namespace, kind, path.replace("/", os.sep) + ext)

    def texture(self, name):
        """A texture as an array of RGBA pixels. name: 'block/poop_stone' or 'modid:block/poop_stone'."""
        if name not in self._textures:
            self._textures[name] = np.asarray(Image.open(self._path("textures", name, ".png")).convert("RGBA"))
        return self._textures[name]

    def model(self, name):
        if name not in self._models:
            with open(self._path("models", name, ".json"), encoding="utf-8") as f:
                self._models[name] = json.load(f)
        return self._models[name]


class Picture:
    """A rendered scene: the image, and where a point of the scene landed on it."""

    def __init__(self, image, project):
        self.image = image
        self._project = project

    def at(self, x, y, z):
        """Pixel of the image where the point (x, y, z) of the scene is drawn."""
        return self._project(x, y, z)


class Scene:
    """Blocks and models placed on a grid of one unit per block, then drawn from above at an angle.

    yaw turns the scene around the vertical axis: 225 is the view of the inventory (east face on
    the left, north face on the right), 135 shows south and east, 45 south and west, 315 north and west."""

    def __init__(self, assets):
        self.assets = assets
        self.quads = []         # (corners, texture, (u1, v1, u2, v2) in texels, shade, alpha)
        self.solid = set()      # cells filled by a whole opaque block: faces between two of them are not drawn
        self.fluid = {}         # cells of see-through blocks (liquids) and their height: faces between two of the same height are not drawn
        self._cubes = []

    # ------------------------------------------------------------ building

    def cube(self, x, y, z, textures, alpha=1.0, height=1.0, solid=None, light=1.0):
        """A whole block. textures: one name for every face, or a table by face with 'side' and 'end' as shortcuts.
        light brightens (above 1) or darkens a block whose texture is too dark or too bright on the page."""
        if isinstance(textures, str):
            textures = {"all": textures}
        solid = (alpha >= 1.0 and height >= 1.0) if solid is None else solid
        if solid:
            self.solid.add((x, y, z))
        if alpha < 1.0:
            self.fluid[(x, y, z)] = height
        self._cubes.append((x, y, z, textures, alpha, height, light))

    def fill(self, x0, y0, z0, x1, y1, z1, textures, **kwargs):
        """Blocks from (x0, y0, z0) to (x1, y1, z1), both included."""
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.cube(x, y, z, textures, **kwargs)

    def remove(self, x, y, z):
        self._cubes = [c for c in self._cubes if tuple(c[:3]) != (x, y, z)]
        self.solid.discard((x, y, z))
        self.fluid.pop((x, y, z), None)

    def box(self, a, b, texture, uv=None, alpha=1.0, faces=None, shade=1.0):
        """A box between two corners (in blocks), the same texture on every face."""
        tex = self.assets.texture(texture) if isinstance(texture, str) else texture
        h, w = tex.shape[:2]
        for face in faces or SHADE:
            self.quads.append((np.array(corners(face, a, b), dtype=np.float64), tex, uv or (0, 0, w, h), SHADE[face] * shade, alpha))

    def model(self, name, x=0, y=0, z=0, size=1.0, alpha=1.0, snug=False, light=1.0):
        """A model of the mod (the elements of its JSON file). The corner of its block goes at (x, y, z);
        with snug, the lowest corner of the model itself does, which sets a small item on the ground.
        size 2 draws it twice as large; light brightens (above 1) a model whose texture is very dark."""
        data = self.assets.model(name)
        while "elements" not in data and "parent" in data and not data["parent"].startswith("minecraft:"):
            parent = self.assets.model(data["parent"].split(":", 1)[-1])
            data = dict(parent, textures=dict(parent.get("textures", {}), **data.get("textures", {})))
        if "elements" not in data:
            raise ValueError(f"{name} has no elements: use cube() for a plain block")
        names = data.get("textures", {})
        k = size / 16.0
        if snug:
            low = [min(e["from"][i] for e in data["elements"]) for i in range(3)]
            x, y, z = x - low[0] * k, y - low[1] * k, z - low[2] * k
        for element in data["elements"]:
            a = [x + element["from"][0] * k, y + element["from"][1] * k, z + element["from"][2] * k]
            b = [x + element["to"][0] * k, y + element["to"][1] * k, z + element["to"][2] * k]
            for face, spec in element.get("faces", {}).items():
                ref = spec["texture"].lstrip("#")
                while ref in names:
                    ref = names[ref].lstrip("#")
                tex = self.assets.texture(ref)
                th, tw = tex.shape[:2]
                u1, v1, u2, v2 = spec.get("uv", (0, 0, 16, 16))
                uv = (u1 / 16 * tw, v1 / 16 * th, u2 / 16 * tw, v2 / 16 * th)
                self.quads.append((np.array(corners(face, a, b), dtype=np.float64), tex, uv, SHADE[face] * light, alpha))

    def _cube_quads(self):
        out = []
        for x, y, z, textures, alpha, height, light in self._cubes:
            for face, (dx, dy, dz) in NEIGHBOUR.items():
                beside = (x + dx, y + dy, z + dz)
                if beside in self.solid and (alpha >= 1.0 or face != "up"):
                    continue
                if alpha < 1.0 and beside in self.fluid and (face in ("up", "down") or self.fluid[beside] >= height):
                    continue
                name = textures.get(face) or textures.get("end" if face in ("up", "down") else "side") or textures.get("all")
                if not name:
                    continue
                tex = self.assets.texture(name)
                th, tw = tex.shape[:2]
                uv = (0, 0, tw, th) if face in ("up", "down") or height >= 1.0 else (0, th * (1 - height), tw, th)
                pts = corners(face, (x, y, z), (x + 1, y + height, z + 1))
                out.append((np.array(pts, dtype=np.float64), tex, uv, SHADE[face] * light, alpha))
        return out

    # ------------------------------------------------------------ drawing

    def render(self, scale=32, yaw=225, pitch=30, smooth=2, pad=2, light=1.0):
        """Draws the scene. scale: pixels per block. smooth: the scene is drawn that many times larger
        and reduced, which softens the edges. Returns a Picture."""
        quads = self.quads + self._cube_quads()
        if not quads:
            raise ValueError("the scene is empty")
        ya, pa = math.radians(yaw), math.radians(pitch)
        ry = np.array([[math.cos(ya), 0, math.sin(ya)], [0, 1, 0], [-math.sin(ya), 0, math.cos(ya)]])
        rx = np.array([[1, 0, 0], [0, math.cos(pa), -math.sin(pa)], [0, math.sin(pa), math.cos(pa)]])
        rot = rx @ ry
        s = scale * smooth
        views = [q[0] @ rot.T for q in quads]
        allp = np.concatenate(views)
        min_x, max_x = allp[:, 0].min(), allp[:, 0].max()
        min_y, max_y = allp[:, 1].min(), allp[:, 1].max()
        w = int(math.ceil((max_x - min_x) * s)) + 2 * pad * smooth
        h = int(math.ceil((max_y - min_y) * s)) + 2 * pad * smooth
        ox, oy = pad * smooth - min_x * s, pad * smooth + max_y * s
        color = np.zeros((h, w, 4), dtype=np.float32)
        depth = np.full((h, w), -1e9, dtype=np.float32)
        order = sorted(range(len(quads)), key=lambda i: (quads[i][4] < 1.0, views[i][:, 2].mean()))
        for i in order:
            _, tex, (u1, v1, u2, v2), shade, alpha = quads[i]
            v = views[i]
            normal = np.cross(v[1] - v[0], v[3] - v[0])
            if normal[2] <= 1e-9:
                continue                                    # the face looks away
            p = np.stack([v[:, 0] * s + ox, oy - v[:, 1] * s, v[:, 2]], axis=1)
            eu, ev = p[3] - p[0], p[1] - p[0]
            det = eu[0] * ev[1] - eu[1] * ev[0]
            if abs(det) < 1e-9:
                continue
            x0, x1 = max(0, int(math.floor(p[:, 0].min()))), min(w, int(math.ceil(p[:, 0].max())) + 1)
            y0, y1 = max(0, int(math.floor(p[:, 1].min()))), min(h, int(math.ceil(p[:, 1].max())) + 1)
            if x1 <= x0 or y1 <= y0:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
            dx, dy = gx - p[0, 0], gy - p[0, 1]
            a = (dx * ev[1] - dy * ev[0]) / det
            b = (dy * eu[0] - dx * eu[1]) / det
            eps = 1e-4
            inside = (a >= -eps) & (a <= 1 + eps) & (b >= -eps) & (b <= 1 + eps)
            if not inside.any():
                continue
            z = p[0, 2] + a * eu[2] + b * ev[2]
            th, tw = tex.shape[:2]
            tu = np.clip(np.floor(np.clip(u1 + a * (u2 - u1), min(u1, u2), max(u1, u2) - 1e-3)), 0, tw - 1).astype(np.intp)
            tv = np.clip(np.floor(np.clip(v1 + b * (v2 - v1), min(v1, v2), max(v1, v2) - 1e-3)), 0, th - 1).astype(np.intp)
            texel = tex[tv, tu].astype(np.float32)
            visible = inside & (texel[:, :, 3] > 8) & (z > depth[y0:y1, x0:x1] + 1e-5)
            if not visible.any():
                continue
            region = color[y0:y1, x0:x1]
            rgb = np.minimum(255.0, texel[:, :, :3] * (shade * light))
            if alpha >= 1.0:
                region[visible, :3] = rgb[visible]
                region[visible, 3] = 255.0
                depth[y0:y1, x0:x1][visible] = z[visible]
            else:
                under = region[visible]
                k = alpha
                out_a = k * 255.0 + under[:, 3] * (1 - k)
                mixed = (rgb[visible] * k * 255.0 + under[:, :3] * under[:, 3:4] * (1 - k)) / np.maximum(out_a[:, None], 1e-6)
                region[visible, :3] = mixed
                region[visible, 3] = out_a
        image = Image.fromarray(np.clip(color, 0, 255).astype(np.uint8), "RGBA")
        if smooth != 1:
            image = image.convert("RGBa").resize((max(1, w // smooth), max(1, h // smooth)), Image.LANCZOS).convert("RGBA")

        def project(x, y, z):
            q = rot @ np.array([x, y, z], dtype=np.float64)
            return ((q[0] * s + ox) / smooth, (oy - q[1] * s) / smooth)

        return Picture(image, project)


def trim(image, margin=0):
    """The image without its empty border."""
    box = image.getchannel("A").getbbox()
    if not box:
        return image
    box = (max(0, box[0] - margin), max(0, box[1] - margin), min(image.width, box[2] + margin), min(image.height, box[3] + margin))
    return image.crop(box)


def fit(image, size):
    """The image reduced to fit a square of `size` pixels, centred on a see-through background."""
    image = trim(image)
    k = min(size / image.width, size / image.height)
    small = image.convert("RGBa").resize((max(1, round(image.width * k)), max(1, round(image.height * k))), Image.LANCZOS).convert("RGBA")
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(small, ((size - small.width) // 2, (size - small.height) // 2))
    return out


def flat(image, colors=32):
    """The image as flat pixel art: a pixel is either shown or not, and only a few colors are kept.
    For an icon or an ornament made from a rendered picture, which sits next to pixel art."""
    alpha = np.asarray(image.getchannel("A")) >= 128
    reduced = np.asarray(image.convert("RGB").quantize(colors=colors, method=Image.MEDIANCUT).convert("RGB"))
    out = np.zeros((image.height, image.width, 4), dtype=np.uint8)
    out[:, :, :3] = reduced
    out[:, :, 3] = np.where(alpha, 255, 0)
    out[~alpha] = 0
    return Image.fromarray(out, "RGBA")


def outline(image, color, width=1):
    """The image with a line of `color` around its shape, like a sticker. Room is added around the
    image for the line, so a shape that touches the border keeps its outline."""
    padded = Image.new("RGBA", (image.width + 2 * width, image.height + 2 * width), (0, 0, 0, 0))
    padded.alpha_composite(image.convert("RGBA"), (width, width))
    image = padded
    alpha = np.asarray(image.getchannel("A")) > 96
    grown = alpha.copy()
    for _ in range(width):
        g = grown.copy()
        g[1:, :] |= grown[:-1, :]
        g[:-1, :] |= grown[1:, :]
        g[:, 1:] |= grown[:, :-1]
        g[:, :-1] |= grown[:, 1:]
        grown = g
    edge = grown & ~alpha
    out = np.asarray(image).copy()
    out[edge] = tuple(color[:3]) + (255,)
    return Image.fromarray(out, "RGBA")
