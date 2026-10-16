"""Motion design passes with EffectCraft, the After Effects style app of the ArtCraft suite.

The scenes of kit.py build the frames of a banner; these passes make them look finished before
they are encoded, and they all keep the loop seamless:

    frames = fx.finishing(frames, theme, sweep_box=(260, 90, 540, 210), particles=14)
    kit.finish(frames, "images/banner-hero.gif")

or for every banner of make_banners.py at once:

    kit.LOOK = fx.look(THEME, particles=10, particle_type="cube")

finishing()   a color grade (vibrance), a vignette, an optional glow on the highlights, a light that
              sweeps once across a box (the name, the logo), and drifting particles in the colors
              of the theme. Particles are rendered over twice the length and cross-faded, so the
              last frame flows into the first.
sweep()       a light sweep across RGBA frames that keeps their transparency (title ribbons, the
              divider, a sprite).
branded_hero()  a whole top banner built in EffectCraft: a plate, the logo of the brand kit with
              its glow and a light sweep, the tagline in a real font, particles.

Every pass returns the frames untouched (and says so once) when EffectCraft is not installed or
CRAFT=off, so a page can always be built. Weight: particles and grain are moving pixels and a GIF
pays for each of them; keep particles under about 20 and look at the size `kit.finish` prints.

Author: vyrriox
"""
import os
import shutil
import tempfile

from PIL import Image

import craft

FPS = 20
# CC Particle World's Particle Type menu (one-based in scripts). Cubes suit a Minecraft mod.
PARTICLE_TYPES = {"line": 1, "shaded": 2, "faded": 3, "bubble": 4, "star": 5, "polygon": 6, "triangle": 7, "cube": 8, "square": 9}
_warned = set()


def _skip(what):
    if not craft.available("effectcraft"):
        if what not in _warned:
            print(f"note: EffectCraft not available, {what} skipped (the frames are kept as they are)")
            _warned.add(what)
        return True
    return False


def _rgb01(color):
    if isinstance(color, str):
        h = color.lstrip("#")
        color = tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    return "[%.4f,%.4f,%.4f]" % tuple(c / 255 for c in color[:3])


def _write(frames, folder):
    os.makedirs(folder, exist_ok=True)
    for i, f in enumerate(frames):
        f.save(os.path.join(folder, f"f_{i:04d}.png"), compress_level=1)
    return os.path.join(folder, "f_0000.png")


def _import(first, fps):
    return (f"var io = new ImportOptions(new File({craft.js_path(first)})); io.sequence = true;\n"
            f"var item = app.project.importFile(io); item.mainSource.conformFrameRate = {fps};\n"
            f"var D = item.duration;\n")


def _render(jsx, work, comp="fx"):
    project = os.path.join(work, "fx.ecproj")
    craft.ec_script(jsx, project)
    paths = craft.ec_render_sequence(project, comp, os.path.join(work, "out"))
    return [Image.open(p).convert("RGBA") for p in paths]


def _sweep_jsx(layer, box, angle, width, intensity, color, start, end):
    """CC Light Sweep on `layer`, travelling across box (x0, y0, x1, y1) between times start and end."""
    x0, y0, x1, y1 = box
    cy = (y0 + y1) / 2
    return (f"var sw = {layer}.property('ADBE Effect Parade').addProperty('CC Light Sweep');\n"
            f"sw.property('Direction').setValue({angle}); sw.property('Width').setValue({width});\n"
            f"sw.property('Sweep Intensity').setValue({intensity}); sw.property('Edge Intensity').setValue({intensity * 1.6:.1f});\n"
            f"sw.property('Light Color').setValue({_rgb01(color)[:-1]},1]);\n"
            f"sw.property('Center').setValueAtTime({start:.4f}, [{x0 - width * 2}, {cy}]);\n"
            f"sw.property('Center').setValueAtTime({end:.4f}, [{x1 + width * 2}, {cy}]);\n")


def sweep(frames, fps=12.5, box=None, angle=-30, width=50, intensity=60, color=(255, 255, 255), share=0.6):
    """A light that sweeps once across RGBA frames (alpha kept), during the first `share` of the loop."""
    if not frames or _skip("light sweep"):
        return frames
    work = tempfile.mkdtemp(prefix="pagefx_", dir=os.environ.get("PAGE_TEMP") or None)
    try:
        w, h = frames[0].size
        first = _write([f.convert("RGBA") for f in frames], os.path.join(work, "in"))
        n = len(frames)
        jsx = _import(first, fps)
        jsx += f"var comp = app.project.items.addComp('fx', {w}, {h}, 1, D, {fps});\nvar L = comp.layers.add(item);\n"
        jsx += _sweep_jsx("L", box or (0, 0, w, h), angle, width, intensity, color, 0, n * share / fps)
        out = _render(jsx, work)
        return out[:n] if len(out) >= n else frames
    except craft.CraftError as error:
        print(f"note: light sweep skipped: {error}")
        return frames
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _particles_jsx(w, h, total, count, color, size, rise, kind="faded"):
    """CC Particle World on its own layer: slow motes drifting up through the whole frame."""
    return (f"var dust = comp.layers.addSolid([0,0,0], 'particles', {w}, {h}, 1, {total});\n"
            "var pw = dust.property('ADBE Effect Parade').addProperty('CC Particle World');\n"
            f"pw.property('Birth Rate').setValue({max(0.05, count / 40):.3f});\n"
            "pw.property('Longevity (sec)').setValue(3.2);\n"
            "var pr = pw.property('Producer'); pr.property('Position Y').setValue(0.18);\n"
            "pr.property('Radius X').setValue(0.62); pr.property('Radius Y').setValue(0.16); pr.property('Radius Z').setValue(0.1);\n"
            "var ph = pw.property('Physics'); ph.property('Velocity').setValue(0.05);\n"
            f"ph.property('Gravity').setValue({-abs(rise):.3f}); ph.property('Extra').setValue(0.2);\n"
            "var pa = pw.property('Particle');\n"
            f"pa.property('Particle Type').setValue({PARTICLE_TYPES.get(kind, 3)});\n"
            f"pa.property('Birth Size').setValue({size:.3f}); pa.property('Death Size').setValue({size * 0.4:.3f});\n"
            "pa.property('Size Variation').setValue(40); pa.property('Max Opacity').setValue(70);\n"
            f"pa.property('Birth Color').setValue({_rgb01(color)[:-1]},1]); pa.property('Death Color').setValue({_rgb01(color)[:-1]},1]);\n"
            "pw.property('Extras').property('Random Seed').setValue(7);\n"
            "dust.blendingMode = BlendingMode.ADD;\n")


def finishing(frames, theme=None, fps=FPS, vibrance=18, vignette=30, glow=0, sweep_box=None, sweep_color=None,
              particles=0, particle_color=None, particle_size=0.035, rise=0.06, particle_type="faded"):
    """Grade, vignette, glow, a light sweep across sweep_box and particles, as one EffectCraft render.

    frames are the opaque frames of a banner (any scene of kit.py); the result has the same count
    and size, ready for kit.finish. Colors default to the theme: particles in its light accent."""
    if not frames or _skip("banner finishing"):
        return frames
    work = tempfile.mkdtemp(prefix="pagefx_", dir=os.environ.get("PAGE_TEMP") or None)
    try:
        n = len(frames)
        w, h = frames[0].size
        first = _write([f.convert("RGB") for f in frames], os.path.join(work, "in"))
        twice = particles > 0
        jsx = _import(first, fps)
        jsx += f"var total = {'2 * D' if twice else 'D'};\n"
        jsx += f"var comp = app.project.items.addComp('fx', {w}, {h}, 1, total, {fps});\n"
        jsx += "var base = comp.layers.add(item);\n"
        if twice:
            jsx += "var again = comp.layers.add(item); again.startTime = D;\n"
        jsx += f"var grade = comp.layers.addSolid([1,1,1], 'grade', {w}, {h}, 1, total); grade.adjustmentLayer = true;\n"
        jsx += "var ep = grade.property('ADBE Effect Parade');\n"
        if vibrance:
            jsx += f"ep.addProperty('Vibrance').property('Vibrance').setValue({vibrance});\n"
        if glow:
            jsx += ("var gl = ep.addProperty('Glow'); gl.property('Glow Threshold').setValue(72);"
                    f" gl.property('Glow Radius').setValue({max(4, h // 30)}); gl.property('Glow Intensity').setValue({glow});\n")
        if vignette:
            jsx += f"ep.addProperty('CC Vignette').property('Amount').setValue({vignette});\n"
        if sweep_box:
            color = sweep_color or (theme.accent_light if theme is not None and hasattr(theme, "accent_light") else (255, 255, 255))
            x0, y0, x1, y1 = sweep_box
            jsx += (f"var shine = comp.layers.addSolid([1,1,1], 'shine', {w}, {h}, 1, total); shine.adjustmentLayer = true;\n"
                    "var mk = shine.property('ADBE Mask Parade').addProperty('ADBE Mask Atom');\n"
                    f"var sh = new Shape(); sh.vertices = [[{x0},{y0}],[{x1},{y0}],[{x1},{y1}],[{x0},{y1}]]; sh.closed = true;\n"
                    "mk.property('ADBE Mask Shape').setValue(sh); mk.property('ADBE Mask Feather').setValue([8,8]);\n")
            band = max(30, (x1 - x0) // 8)
            jsx += _sweep_jsx("shine", sweep_box, -28, band, 70, color, n * 0.15 / fps, n * 0.6 / fps)
            if twice:
                # The same sweep again in the second half, at the same moment of the loop, so the
                # cross-fade below shows one whole sweep and never two half ones.
                cy, far_l, far_r = (y0 + y1) / 2, x0 - band * 2, x1 + band * 2
                for t, x in ((n * 0.999, far_r), (n * 1.15, far_l), (n * 1.6, far_r)):
                    jsx += f"sw.property('Center').setValueAtTime({t / fps:.4f}, [{x}, {cy}]);\n"
        if particles:
            color = particle_color or (theme.accent_light if theme is not None and hasattr(theme, "accent_light") else (255, 240, 200))
            jsx += _particles_jsx(w, h, "total", particles, color, particle_size, rise, particle_type)
        out = [f.convert("RGB") for f in _render(jsx, work)]
        if twice:
            if len(out) < 2 * n:
                return frames
            # Frame i shows the second pass fading into the first: frame n-1 leads into frame 0.
            return [Image.blend(out[n + i], out[i], i / n) for i in range(n)]
        return out[:n] if len(out) >= n else frames
    except craft.CraftError as error:
        print(f"note: banner finishing skipped: {error}")
        return frames
    finally:
        shutil.rmtree(work, ignore_errors=True)


def look(theme, border=True, **options):
    """A finishing function for kit.LOOK: finishing() with these options, then the frame border of
    the theme drawn again on top, so the grade and the vignette never dim it."""
    def apply(frames):
        import kit
        done = finishing(frames, theme, **options)
        return [kit.frame_border(f, theme) for f in done] if border else done
    return apply


def branded_hero(theme, plate, logo, tagline=None, size=(800, 300), length=68, fps=FPS, font="Inter",
                 font_style="SemiBold", particles=14, darken=55, particle_type="faded"):
    """The top banner built in EffectCraft: the plate (a still of the game or a backdrop) darkened
    and graded, the brand logo (a PNG of Branding/logo/png, its reverse version for a dark plate)
    with a glow and a light sweep, the tagline in a real font, and particles. Returns the frames;
    give them to kit.frame_border and kit.finish."""
    w, h = size
    if _skip("branded hero"):
        return None
    work = tempfile.mkdtemp(prefix="pagefx_", dir=os.environ.get("PAGE_TEMP") or None)
    try:
        src = Image.open(plate).convert("RGB")
        scale = max(w / src.width, h / src.height)
        src = src.resize((round(src.width * scale), round(src.height * scale)), Image.LANCZOS)
        left, top = (src.width - w) // 2, (src.height - h) // 2
        frames = [src.crop((left, top, left + w, top + h))] * length
        mark = Image.open(logo).convert("RGBA")
        box_h = h * (0.46 if tagline else 0.56)
        k = min(w * 0.7 / mark.width, box_h / mark.height)
        mark = mark.resize((round(mark.width * k), round(mark.height * k)), Image.LANCZOS)
        mark_path = os.path.join(work, "logo.png")
        mark.save(mark_path)
        logo_y = h * (0.42 if tagline else 0.5)
        tag_jsx = ""
        if tagline:
            color = _rgb01(theme.accent_light if hasattr(theme, "accent_light") else (230, 230, 230))
            tag_jsx = (f"var t = comp.layers.addText({json_str(tagline)});\n"
                       "var td = t.property('ADBE Text Properties').property('ADBE Text Document'); var doc = td.value;\n"
                       f"doc.font = {json_str(font + '-' + font_style)}; doc.fontSize = {max(14, h // 16)}; doc.tracking = 180;\n"
                       f"doc.fillColor = {color}; doc.justification = ParagraphJustification.CENTER_JUSTIFY; td.setValue(doc);\n"
                       f"t.property('ADBE Transform Group').property('ADBE Position').setValue([{w / 2}, {h * 0.8}]);\n")
        first = _write(frames, os.path.join(work, "in"))
        jsx = _import(first, fps)
        jsx += f"var comp = app.project.items.addComp('fx', {w}, {h}, 1, D, {fps});\nvar base = comp.layers.add(item);\n"
        jsx += ("var bp = base.property('ADBE Effect Parade');\n"
                f"bp.addProperty('Vibrance').property('Vibrance').setValue(20);\n"
                f"var ex = bp.addProperty('Brightness & Contrast'); if (ex) ex.property(1).setValue({-darken / 2});\n"
                "bp.addProperty('CC Vignette').property('Amount').setValue(55);\n"
                "bp.addProperty('Gaussian Blur').property(1).setValue(3);\n")
        jsx += (f"var lf = app.project.importFile(new ImportOptions(new File({craft.js_path(mark_path)})));\n"
                "var lg = comp.layers.add(lf);\n"
                f"lg.property('ADBE Transform Group').property('ADBE Position').setValue([{w / 2}, {logo_y}]);\n"
                "var lp = lg.property('ADBE Effect Parade');\n"
                "var gl = lp.addProperty('Glow'); gl.property('Glow Threshold').setValue(55); gl.property('Glow Radius').setValue(18);"
                " gl.property('Glow Intensity').setValue(0.6);\n"
                "lp.addProperty('Drop Shadow').property('Opacity').setValue(70);\n")
        x0, x1 = (w - mark.width) / 2, (w + mark.width) / 2
        y0, y1 = logo_y - mark.height / 2, logo_y + mark.height / 2
        jsx += _sweep_jsx("lg", (x0, y0, x1, y1), -28, max(30, mark.width // 7), 70,
                          theme.accent_light if hasattr(theme, "accent_light") else (255, 255, 255),
                          length * 0.2 / fps, length * 0.7 / fps).replace("var sw = lg", "var sw = lg")
        jsx += tag_jsx
        out = [f.convert("RGB") for f in _render(jsx, work)][:length]
        if particles:
            out = finishing(out, theme, fps, vibrance=0, vignette=0, particles=particles, particle_type=particle_type)
        return out
    except (craft.CraftError, OSError) as error:
        print(f"note: branded hero skipped: {error}")
        return None
    finally:
        shutil.rmtree(work, ignore_errors=True)


def json_str(text):
    import json
    return json.dumps(text)
