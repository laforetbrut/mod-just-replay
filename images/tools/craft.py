"""Drives the ArtCraft suite from the page toolkit: PhotoCraft (raster finishing), VectorCraft
(vector art and type), EffectCraft (motion design, After Effects style) and FilmCraft (editing).

    python craft.py check          # which apps are installed, and their command line tools

Every function raises CraftError with the step that failed. `available(app)` says whether an app is
there, so the toolkit can fall back to its own Pillow drawing when one is missing. Set CRAFT=off to
build a page without the suite (the pictures then look as they did before it).

Author: vyrriox
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

APPS = {
    "photocraft": ("PhotoCraft", "photocraft-cli"),
    "vectorcraft": ("VectorCraft", "vectorcraft-cli"),
    "effectcraft": ("EffectCraft", "effectcraft-cli"),
    "filmcraft": ("FilmCraft", "filmcraft-cli"),
}
_found = {}


class CraftError(RuntimeError):
    pass


def find(app):
    """Full path of an app's command line tool, or None."""
    if app in _found:
        return _found[app]
    folder, exe = APPS[app]
    env = os.environ.get(f"ARTCRAFT_{app.upper()}")
    path = env if env and os.path.isfile(env) else shutil.which(exe)
    if not path:
        roots = [os.environ.get("ProgramFiles", r"C:\Program Files"), os.environ.get("ProgramFiles(x86)", ""),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs"), "/Applications"]
        for root in filter(None, roots):
            for name in (exe + ".exe", exe):
                candidate = os.path.join(root, folder, name)
                if os.path.isfile(candidate):
                    path = candidate
                    break
            if path:
                break
    _found[app] = path
    return path


def available(app):
    return os.environ.get("CRAFT", "on").lower() not in ("off", "0", "no") and find(app) is not None


def _run(args, what, cwd=None):
    proc = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=cwd)
    out = proc.stdout
    if proc.returncode != 0 or '"error"' in out[:2000] and out.lstrip().startswith("{\"error"):
        raise CraftError(f"{what} failed: {(proc.stderr.strip() or out.strip())[:800]}")
    return out


def tool(app):
    path = find(app)
    if not path:
        raise CraftError(f"{APPS[app][0]} is not installed (ArtCraft launcher), or set ARTCRAFT_{app.upper()}")
    return path


# ------------------------------------------------------------------ PhotoCraft

def pc_batch(actions, folder_in, folder_out):
    """Plays a list of [command, params] steps on every image of folder_in, into folder_out (PNG)."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as handle:
        json.dump(actions, handle)
        steps = handle.name
    try:
        _run([tool("photocraft"), "batch", "--actions", steps, "--in", folder_in, "--out", folder_out, "--format", "png"],
             "PhotoCraft batch")
    finally:
        os.remove(steps)


def pc_run(src=None, new=None, cmds=(), out=None, quality=None):
    args = [tool("photocraft"), "run"]
    args += [os.path.abspath(src)] if src else ["--new", json.dumps(new or {})]
    for cmd, params in cmds:
        args += ["--cmd", cmd] + (["--params", json.dumps(params)] if params is not None else [])
    if out:
        args += ["--out", os.path.abspath(out)]
    if quality:
        args += ["--quality", str(quality)]
    return _run(args, "PhotoCraft run")


# ------------------------------------------------------------------ VectorCraft

def vc_convert(src, out, scale=None, outline_text=False):
    args = [tool("vectorcraft"), "convert", os.path.abspath(src), os.path.abspath(out)]
    if scale:
        args += ["--scale", str(scale)]
    if outline_text:
        args.append("--outline-text")
    return _run(args, f"VectorCraft convert {os.path.basename(src)}")


# ------------------------------------------------------------------ EffectCraft

def js_path(path):
    """A path as a JavaScript string literal (forward slashes, quotes escaped)."""
    return json.dumps(os.path.abspath(path).replace("\\", "/"))


def ec_script(jsx, project_out=None, project_in=None):
    """Runs an ExtendScript-style script (the After Effects object model) and saves the project."""
    with tempfile.NamedTemporaryFile("w", suffix=".jsx", delete=False, encoding="utf-8") as handle:
        handle.write(jsx)
        script = handle.name
    try:
        args = [tool("effectcraft")]
        args += ["--project", os.path.abspath(project_in)] if project_in else ["--empty"]
        args += ["script", script]
        if project_out:
            args += ["--save-as", os.path.abspath(project_out)]
        return _run(args, "EffectCraft script")
    finally:
        os.remove(script)


def ec_render_sequence(project, comp, folder, fps=None):
    """Renders a comp to RGBA PNG frames o_0000.png... in folder; returns their sorted paths."""
    os.makedirs(folder, exist_ok=True)
    args = [tool("effectcraft"), "--project", os.path.abspath(project), "render", "--comp", comp,
            "--out", os.path.join(os.path.abspath(folder), "o_[####].png"), "--format", "png", "--channels", "rgba"]
    if fps:
        args += ["--fps", str(fps)]
    _run(args, f"EffectCraft render of {comp}")
    return sorted(os.path.join(folder, f) for f in os.listdir(folder) if f.startswith("o_") and f.endswith(".png"))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2 or sys.argv[1] != "check":
        print(__doc__)
        return 1
    for app, (name, _) in APPS.items():
        path = find(app)
        print(f"{name:12} {'found: ' + path if path else 'not installed'}")
    if os.environ.get("CRAFT", "on").lower() in ("off", "0", "no"):
        print("CRAFT=off: the toolkit will not use the suite")
    return 0


if __name__ == "__main__":
    sys.exit(main())
