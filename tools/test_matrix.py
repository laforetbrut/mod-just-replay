#!/usr/bin/env python3
"""Test matrix of Just Replay: builds each target, then plays its in-game self test, one target after the other.

For every target (the loader projects, in the order of .github/workflows/build.yml), the script:

  1. checks that no Minecraft game is running (a build under a running dev client crashes it, and this machine
     runs one game at a time);
  2. builds the target with its own JDK (./gradlew build: compile, unit tests, jar);
  3. starts the dev client with the self test (-Pselftest=true) in a game folder of its own; the self test plays
     its scenario (record, replay, seeks, editor, export) and closes the game by itself;
  4. waits until that game is gone, then reads the SELFTEST lines of its log: PASS or FAIL for each check.

A report sums it up in build/test-matrix/<date>/ (report.md, report.json, the PASS/FAIL lines of each target and
the Gradle output), copied to build/test-matrix/latest-report.md.

    python3 tools/test_matrix.py --dry-run                      # the steps and their commands, nothing runs
    python3 tools/test_matrix.py                                # every target (about 10 min per target)
    python3 tools/test_matrix.py --targets 1.21.1               # one Minecraft version (also: a loader, a target)
    python3 tools/test_matrix.py --targets neoforge-1.21.1 --mods ~/instances/pack/mods
    python3 tools/test_matrix.py --parse neoforge-1.21.1/run-matrix/logs/latest.log
    python3 tools/test_matrix.py --list-games                   # the Minecraft games running now
    python3 tools/test_matrix.py --self-check                   # the log reader on known good and bad lines

On a machine shared with other jobs, put every build and game behind the machine lock with
--wrap "vigie verrou-test --". Without a screen (Linux, no DISPLAY), the game runs in xvfb-run with software
rendering. Exit code: 0 all passed, 1 a build or a self test failed, 2 something timed out, 3 a game was already
running, 4 bad options or a missing JDK. See docs/TEST_MATRIX.md.

Author: vyrriox
"""
import argparse
import datetime
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET_NAME = re.compile(r"^(neoforge|forge|fabric)-(\d+\.\d+(?:\.\d+)?)$")
# Gradle's own JVM per target when the workflow file cannot be read (it is the reference: .github/workflows/build.yml).
FALLBACK_TARGETS = [("neoforge-1.20.1", 17), ("forge-1.20.1", 17), ("fabric-1.20.1", 21),
                    ("neoforge-1.21.1", 21), ("forge-1.21.1", 21), ("fabric-1.21.1", 21),
                    ("neoforge-26.1.2", 25), ("forge-26.1.2", 25), ("fabric-26.1.2", 25)]
# Released jars of these targets use other names than their dev client: only forge-1.20.1 can remap them (devMods).
REMAPPED_BY_DEVMODS = {"forge-1.20.1"}
NOT_REMAPPABLE = {"neoforge-1.20.1"}
MATRIX_MARK = ".test-matrix"

EXIT_OK, EXIT_FAILED, EXIT_TIMEOUT, EXIT_BUSY, EXIT_USAGE = 0, 1, 2, 3, 4


# ---------------------------------------------------------------- targets and JDKs

def read_targets():
    """(target, launcher JDK) pairs in the order of the CI workflow, or the fallback list."""
    path = os.path.join(REPO, ".github", "workflows", "build.yml")
    pairs = []
    try:
        project = None
        with open(path, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"\s*-\s*project:\s*([\w.-]+)", line)
                if m:
                    project = m.group(1)
                    continue
                m = re.match(r"\s*launcher:\s*(\d+)", line)
                if m and project:
                    pairs.append((project, int(m.group(1))))
                    project = None
    except OSError:
        pass
    pairs = [(t, j) for t, j in pairs if TARGET_NAME.match(t) and os.path.isdir(os.path.join(REPO, t))]
    return pairs or [(t, j) for t, j in FALLBACK_TARGETS if os.path.isdir(os.path.join(REPO, t))]


def select_targets(all_targets, spec):
    """Targets matching a comma separated list of target names, loaders or Minecraft versions."""
    if not spec:
        return list(all_targets)
    tokens = [t.strip() for t in spec.split(",") if t.strip()]
    chosen = []
    for target, jdk in all_targets:
        loader, mc = TARGET_NAME.match(target).groups()
        if any(tok in (target, loader, mc) for tok in tokens):
            chosen.append((target, jdk))
    unknown = [tok for tok in tokens if not any(tok in (t, *TARGET_NAME.match(t).groups()) for t, _ in all_targets)]
    if unknown:
        raise SystemExit(f"unknown target, loader or version: {', '.join(unknown)} "
                         f"(known: {', '.join(t for t, _ in all_targets)})")
    return chosen


def java_version(home):
    """Version of a JDK folder as numbers, (17, 0, 13) or (1, 8, 0) for Java 8; None when it is not a usable JDK."""
    exe = os.path.join(home, "bin", "java.exe" if os.name == "nt" else "java")
    release = os.path.join(home, "release")
    if not os.path.isfile(exe) or not os.path.isfile(release):
        return None
    try:
        with open(release, encoding="utf-8", errors="replace") as f:
            m = re.search(r'^JAVA_VERSION="?([\d._]+)', f.read(), re.M)
    except OSError:
        return None
    return tuple(int(p) for p in re.split(r"[._]", m.group(1)) if p.isdigit()) if m else None


def java_major(home):
    """Major version of a JDK folder (17, 21...), or None when it is not a usable JDK."""
    version = java_version(home)
    if not version:
        return None
    return version[1] if version[0] == 1 and len(version) > 1 else version[0]


def jdk_roots():
    home = os.path.expanduser("~")
    roots = [os.path.join(home, ".jdks"), os.path.join(home, ".gradle", "jdks")]
    if os.name == "nt":
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"), os.environ.get("LOCALAPPDATA", "")):
            if base:
                for vendor in ("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto",
                               os.path.join("Programs", "Eclipse Adoptium")):
                    roots.append(os.path.join(base, vendor))
    else:
        roots += ["/usr/lib/jvm", "/opt/java", "/opt", "/Library/Java/JavaVirtualMachines",
                  os.path.join(home, "Library", "Java", "JavaVirtualMachines")]
    return roots


def find_jdks(overrides):
    """Major version -> JDK folder: --jdk options first, then JAVA_HOME_<n>_X64 / JAVA<n>_HOME / JDK<n>_HOME, then the
    usual install folders (the newest build of each major wins)."""
    found = {}
    for major, path in overrides.items():
        found[major] = path
    for key, value in os.environ.items():
        m = re.match(r"^(?:JAVA_HOME_(\d+)_X64|JAVA(\d+)_HOME|JDK(\d+)_HOME)$", key)
        if m and value:
            major = int(next(g for g in m.groups() if g))
            if major not in found and java_major(value) == major:
                found[major] = value
    current = os.environ.get("JAVA_HOME")
    if current:
        major = java_major(current)
        if major and major not in found:
            found[major] = current
    newest = {}
    for root in jdk_roots():
        if not os.path.isdir(root):
            continue
        for name in sorted(os.listdir(root)):
            top = os.path.join(root, name)
            if not os.path.isdir(top):
                continue
            # Gradle's own downloads keep the JDK one folder down, macOS in Contents/Home.
            homes = [top, os.path.join(top, "Contents", "Home")]
            homes += [os.path.join(top, sub) for sub in sorted(os.listdir(top))]
            for home in homes:
                major, version = java_major(home), java_version(home)
                if major and (major not in newest or version > newest[major][0]):
                    newest[major] = (version, home)
    for major, (_, home) in newest.items():
        found.setdefault(major, home)
    return found


# ---------------------------------------------------------------- Minecraft games running

# Words that only a game client's or a server's command line holds (dev runs, launchers, installed servers).
CLIENT_MARKS = ("net.minecraft.client.main.Main", "net.fabricmc.loader.impl.launch.knot.KnotClient", "--assetIndex",
                "-Dfabric.dli.env=client")
SERVER_MARKS = ("net.minecraft.server.Main", "net.fabricmc.loader.impl.launch.knot.KnotServer",
                "net.minecraft.bundler.Main", "-Dfabric.dli.env=server", "nogui", "--nogui")


def _argfile_words(arg, cwd):
    """Words of a java @argfile (dev runs pass their arguments through files)."""
    path = arg[1:].strip('"')
    if cwd and not os.path.isabs(path):
        path = os.path.join(cwd, path)
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read(200000).split()
    except OSError:
        return []


def _classify(words):
    """'client', 'server' or None. Forge and NeoForge dev runs name their side in --launchTarget."""
    for i, word in enumerate(words[:-1]):
        if word == "--launchTarget":
            side = words[i + 1].lower()
            return "client" if "client" in side else "server" if "server" in side else None
    if any(word in CLIENT_MARKS for word in words):
        return "client"
    if any(word in SERVER_MARKS for word in words):
        return "server"
    return None


def _linux_processes():
    for pid in os.listdir("/proc"):
        if not pid.isdigit() or int(pid) == os.getpid():
            continue
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                args = [a.decode("utf-8", "replace") for a in f.read().split(b"\0") if a]
            try:
                cwd = os.readlink(f"/proc/{pid}/cwd")
            except OSError:
                cwd = None
            try:
                exe = os.path.basename(os.readlink(f"/proc/{pid}/exe"))
            except OSError:
                exe = ""
        except OSError:
            continue
        if args:
            yield int(pid), args, cwd, exe


def _windows_processes():
    command = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
               "ForEach-Object { [string]$_.ProcessId + [char]9 + $_.CommandLine }")
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", command],
                             capture_output=True, text=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return
    for line in out.splitlines():
        pid, _, cmd = line.partition("\t")
        if pid.strip().isdigit() and cmd:
            try:
                args = shlex.split(cmd, posix=False)
            except ValueError:
                args = cmd.split()
            yield int(pid), args, None, "java.exe"


def _ps_processes():
    try:
        out = subprocess.run(["ps", "-axww", "-o", "pid=,command="], capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.SubprocessError):
        return
    for line in out.splitlines():
        pid, _, cmd = line.strip().partition(" ")
        if pid.isdigit() and int(pid) != os.getpid():
            yield int(pid), cmd.split(), None, ""


def running_games():
    """Minecraft games running now: (pid, 'client' or 'server', short command)."""
    if sys.platform.startswith("linux"):
        source = _linux_processes()
    elif os.name == "nt":
        source = _windows_processes()
    else:
        source = _ps_processes()
    games = []
    for pid, args, cwd, exe in source:
        launcher = os.path.basename(args[0].strip('"')).lower()
        if launcher not in ("java", "javaw", "java.exe", "javaw.exe") and exe not in ("java", "java.exe"):
            continue
        words = []
        for arg in args[1:]:
            words += _argfile_words(arg, cwd) if arg.startswith("@") else [arg]
        kind = _classify(words)
        if kind:
            games.append((pid, kind, " ".join(args)[:160]))
    return games


def wait_until_free(wait_seconds, ignore_servers, say):
    """True once no game blocks a build or a launch; waits up to wait_seconds, polling every 10 s."""
    deadline = time.time() + max(0, wait_seconds)
    announced = False
    while True:
        busy = [g for g in running_games() if g[1] == "client" or not ignore_servers]
        if not busy:
            return True
        if time.time() >= deadline:
            for pid, kind, cmd in busy:
                say(f"    still running: {kind} pid {pid}: {cmd}")
            return False
        if not announced:
            say(f"    waiting: {len(busy)} Minecraft game(s) running (pid {', '.join(str(g[0]) for g in busy)})")
            announced = True
        time.sleep(10)


# ---------------------------------------------------------------- self test log

VERDICT = re.compile(r"\b(OK|PASS|FAIL)\b")


def parse_selftest(lines):
    """PASS and FAIL lines of a self test log. A line counts as FAIL when it holds a FAIL verdict (some lines carry
    two checks), as PASS when it holds only OK or PASS verdicts; other SELFTEST lines are information."""
    result = {"pass": [], "fail": [], "info": 0, "done": False, "aborted": None}
    for raw in lines:
        if "SELFTEST " not in raw:
            continue
        message = raw[raw.index("SELFTEST ") + len("SELFTEST "):].rstrip()
        if message == "DONE":
            result["done"] = True
            continue
        if message.startswith("FAIL at step"):
            result["aborted"] = message
            result["fail"].append(message)
            continue
        verdicts = VERDICT.findall(message)
        if "FAIL" in verdicts:
            result["fail"].append(message)
        elif verdicts:
            result["pass"].append(message)
        else:
            result["info"] += 1
    if result["fail"]:
        result["verdict"] = "FAIL"
    elif not result["done"]:
        result["verdict"] = "INCOMPLETE"
    elif not result["pass"]:
        result["verdict"] = "NO CHECKS"
    else:
        result["verdict"] = "PASS"
    return result


def read_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def self_check():
    """Proves the log reader on known lines, good and bad; returns an exit code."""
    good = ["[12:00:00] [Render thread/INFO] [com.vyrriox.justreplay.JustReplay/]: SELFTEST world requested",
            "[12:00:01] [Render thread/INFO] [JustReplay/]: SELFTEST seek forward: 120 ticks -> OK",
            "[12:00:02] [Render thread/INFO] [JustReplay/]: SELFTEST replay version check: OK",
            "[12:00:03] [Render thread/INFO] [JustReplay/]: SELFTEST checkpoint under forced slow frames: PASS (12 ticks)",
            "[12:00:04] [Render thread/INFO] [JustReplay/]: SELFTEST DONE"]
    bad = good[:2] + [
        "[12:00:05] [Render thread/INFO] [JustReplay/]: SELFTEST entity edits: skipped renders 3 -> OK, offset renders 0 -> FAIL",
        "[12:00:06] [Render thread/INFO] [JustReplay/]: SELFTEST clock export: no sample while the export ran FAIL",
        "[12:00:07] [Render thread/INFO] [JustReplay/]: SELFTEST DONE"]
    aborted = good[:2] + ["[12:00:08] [Render thread/ERROR] [JustReplay/]: SELFTEST FAIL at step 7: timed out"]
    unfinished = good[:3]
    checks = [
        ("good log", parse_selftest(good), "PASS", 3, 0),
        ("two failed checks", parse_selftest(bad), "FAIL", 1, 2),
        ("aborted run", parse_selftest(aborted), "FAIL", 1, 1),
        ("game closed before DONE", parse_selftest(unfinished), "INCOMPLETE", 2, 0),
        ("no SELFTEST line", parse_selftest(["[12:00:00] [main/INFO] [minecraft/]: Loading"]), "INCOMPLETE", 0, 0),
    ]
    wrong = 0
    for name, got, verdict, passes, fails in checks:
        ok = got["verdict"] == verdict and len(got["pass"]) == passes and len(got["fail"]) == fails
        wrong += not ok
        print(f"{'ok  ' if ok else 'BAD '} {name}: {got['verdict']} ({len(got['pass'])} PASS, {len(got['fail'])} FAIL),"
              f" expected {verdict} ({passes} PASS, {fails} FAIL)")
    print(f"{len(checks)} cases, {wrong} wrong")
    return EXIT_OK if wrong == 0 else EXIT_FAILED


# ---------------------------------------------------------------- mods of a modpack

def jar_loader(path):
    """'fabric', 'neoforge' or 'forge' from a jar's metadata, None for a jar that is not a mod (a content pack)."""
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "fabric.mod.json" in names or "quilt.mod.json" in names:
                return "fabric"
            if "META-INF/neoforge.mods.toml" in names:
                return "neoforge"
            if "META-INF/mods.toml" in names:
                toml = z.read("META-INF/mods.toml").decode("utf-8", "replace")
                return "neoforge" if re.search(r'modId\s*=\s*"neoforge"', toml) else "forge"
    except (OSError, zipfile.BadZipFile):
        return "unreadable"
    return None


def mods_source(mods, target):
    """The folder of jars for this target: <mods>/<target> when the folder is split per target, else <mods>."""
    if not mods:
        return None
    per_target = os.path.join(mods, target)
    if os.path.isdir(per_target):
        return per_target
    if any(TARGET_NAME.match(d) for d in os.listdir(mods) if os.path.isdir(os.path.join(mods, d))):
        return None  # split per target, but not for this one
    return mods


def place_mods(source, target, game_dir, dry_run, say):
    """Links (or copies) the jars of a modpack into the game folder. Just Replay's own jars are left out: the dev
    client runs the mod from source. On forge-1.20.1 the mods go to devmods/, remapped by -PdevMods; jars that are not
    mods (content packs) go to mods/. Returns the extra Gradle properties."""
    jars = sorted(f for f in os.listdir(source) if f.endswith(".jar") and not f.lower().startswith("justreplay"))
    loader = TARGET_NAME.match(target).group(1)
    kinds = {jar: jar_loader(os.path.join(source, jar)) for jar in jars}
    other = [j for j, k in kinds.items() if k and k not in (loader, "unreadable")
             and not (loader == "neoforge" and target.endswith("-1.20.1") and k == "forge")]
    if other:
        say(f"    warning: {len(other)} of {len(jars)} jars are not {loader} mods (first: {other[0]})")
    if target in NOT_REMAPPABLE:
        say(f"    warning: released {target} mods use SRG names and this dev client cannot remap them;"
            " most of them will not load (use forge-1.20.1 or an installed game)")
    devmods = target in REMAPPED_BY_DEVMODS
    plan = {"mods": [j for j in jars if not devmods or kinds[j] is None],
            "devmods": [j for j in jars if devmods and kinds[j] is not None]}
    say(f"    mods: {len(jars)} jars from {source}" + (f" ({len(plan['devmods'])} in devmods, remapped)" if devmods else ""))
    if dry_run:
        return ["-PdevMods=" + game_dir + "/devmods"] if devmods and plan["devmods"] else []
    root = os.path.join(REPO, target, game_dir)
    for folder, names in plan.items():
        dest = os.path.join(root, folder)
        os.makedirs(dest, exist_ok=True)
        for old in os.listdir(dest):
            if old.endswith(".jar"):
                os.remove(os.path.join(dest, old))
        for name in names:
            src, dst = os.path.join(source, name), os.path.join(dest, name)
            try:
                os.symlink(src, dst)
            except (OSError, NotImplementedError):
                try:
                    os.link(src, dst)
                except OSError:
                    shutil.copy2(src, dst)
    return ["-PdevMods=" + game_dir + "/devmods"] if devmods and plan["devmods"] else []


def prepare_game_dir(target, game_dir, mods, dry_run):
    """Makes the game folder. The matrix only clears the mods of folders it made: a folder without its mark that
    already holds jars is refused (returns the reason), so a dev's own game folder is never emptied."""
    root = os.path.join(REPO, target, game_dir)
    mark = os.path.join(root, MATRIX_MARK)
    if os.path.isdir(root) and not os.path.exists(mark) and mods:
        for folder in ("mods", "devmods"):
            path = os.path.join(root, folder)
            if os.path.isdir(path) and any(f.endswith(".jar") for f in os.listdir(path)):
                return (f"{target}/{game_dir}/{folder} holds jars the matrix did not put there: "
                        f"choose another --game-dir")
    if not dry_run:
        os.makedirs(root, exist_ok=True)
        if not os.path.exists(mark):
            with open(mark, "w", encoding="utf-8") as f:
                f.write("Game folder of tools/test_matrix.py: its mods/ and devmods/ are rewritten at each run.\n")
    return None


# ---------------------------------------------------------------- running Gradle

def gradle_command(target):
    project = os.path.join(REPO, target)
    if os.name == "nt":
        return [os.path.join(project, "gradlew.bat")]
    wrapper = os.path.join(project, "gradlew")
    return [wrapper] if os.access(wrapper, os.X_OK) else ["sh", wrapper]


def use_xvfb(mode):
    if mode == "never" or os.name == "nt" or sys.platform == "darwin":
        return False
    if mode == "always":
        return True
    return not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY")


def run_logged(cmd, cwd, env, log_path, timeout):
    """Runs a command in its own process group, output to a file. Returns (exit code or None on timeout, seconds)."""
    start = time.time()
    with open(log_path, "w", encoding="utf-8", errors="replace") as log:
        log.write("$ " + " ".join(shlex.quote(c) for c in cmd) + "\n")
        log.flush()
        kwargs = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                **kwargs)
        try:
            code = proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            stop_group(proc)
            code = None
        finally:
            end_leftovers(proc)
    return code, time.time() - start


def stop_group(proc):
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        return
    for sig, grace in ((signal.SIGTERM, 20), (signal.SIGKILL, 5)):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            return
        try:
            proc.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            pass


def end_leftovers(proc):
    """Waits until every process the command started is gone (the game outlives Gradle a moment), then ends any
    that hang on. POSIX only: on Windows taskkill /T covers the tree while the command runs."""
    if os.name == "nt":
        return
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            os.killpg(proc.pid, 0)
        except ProcessLookupError:
            return
        except PermissionError:
            return
        time.sleep(2)
    stop_group(proc)


def unit_tests(target, since):
    """(tests, failures + errors, fresh) from the JUnit XML results of the target."""
    folder = os.path.join(REPO, target, "build", "test-results", "test")
    total = failed = 0
    fresh = False
    if not os.path.isdir(folder):
        return None
    for name in os.listdir(folder):
        if not name.endswith(".xml"):
            continue
        path = os.path.join(folder, name)
        fresh |= os.path.getmtime(path) >= since
        with open(path, encoding="utf-8", errors="replace") as f:
            head = f.read(4000)
        m = re.search(r'tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', head)
        if m:
            total += int(m.group(1))
            failed += int(m.group(2)) + int(m.group(3))
    return total, failed, fresh


def new_files(folder, since, suffix):
    if not os.path.isdir(folder):
        return []
    return sorted(os.path.join(folder, f) for f in os.listdir(folder)
                  if f.endswith(suffix) and os.path.getmtime(os.path.join(folder, f)) >= since)


def crash_cause(path):
    try:
        for line in read_lines(path)[:20]:
            if line.startswith("Description:"):
                return line[len("Description:"):].strip()
    except OSError:
        pass
    return "see the crash report"


# ---------------------------------------------------------------- the matrix

def fmt_time(seconds):
    seconds = int(seconds)
    return f"{seconds // 60} min {seconds % 60:02d} s" if seconds >= 60 else f"{seconds} s"


def rel(path):
    return os.path.relpath(path, REPO).replace(os.sep, "/")


def write_report(report_dir, started, results, options):
    lines = [f"# Just Replay test matrix, {started:%Y-%m-%d %H:%M}", "",
             f"Options: {options}", "",
             "| Target | Build | Unit tests | Self test | PASS | FAIL | Time | Game log |",
             "|---|---|---|---|---|---|---|---|"]
    for r in results:
        tests = r.get("unit_tests")
        tests_text = "-" if not tests else f"{tests[0]} run, {tests[1]} failed" + ("" if tests[2] else " (up to date)")
        st = r.get("selftest") or {}
        lines.append(f"| {r['target']} | {r.get('build', '-')} | {tests_text} | {r.get('run', '-')} | "
                     f"{len(st.get('pass', []))} | {len(st.get('fail', []))} | {fmt_time(r.get('seconds', 0))} | "
                     f"{r.get('log', '-')} |")
    problems = [r for r in results if r.get("notes") or (r.get("selftest") or {}).get("fail")]
    if problems:
        lines += ["", "## What failed", ""]
        for r in problems:
            lines.append(f"### {r['target']}")
            lines += [f"- {note}" for note in r.get("notes", [])]
            lines += [f"- FAIL {line}" for line in (r.get("selftest") or {}).get("fail", [])]
            lines.append("")
    lines += ["", "Every PASS and FAIL line: `selftest-<target>.txt` in this folder; Gradle output: `gradle-*.log`.", ""]
    text = "\n".join(lines)
    with open(os.path.join(report_dir, "report.md"), "w", encoding="utf-8") as f:
        f.write(text)
    with open(os.path.join(report_dir, "report.json"), "w", encoding="utf-8") as f:
        json.dump({"started": started.isoformat(timespec="seconds"), "options": options, "results": results}, f, indent=2)
    shutil.copyfile(os.path.join(report_dir, "report.md"), os.path.join(os.path.dirname(report_dir), "latest-report.md"))


def run_matrix(args):
    say = lambda text: print(text, flush=True)
    all_targets = read_targets()
    targets = select_targets(all_targets, args.targets)
    if not targets:
        say("no target selected")
        return EXIT_USAGE
    overrides = {}
    for spec in args.jdk:
        major, _, path = spec.partition("=")
        if not major.isdigit() or java_major(path) != int(major):
            say(f"--jdk {spec}: not a JDK {major} folder")
            return EXIT_USAGE
        overrides[int(major)] = path
    jdks = find_jdks(overrides)
    if args.mods and not os.path.isdir(args.mods):
        say(f"--mods {args.mods}: no such folder")
        return EXIT_USAGE
    mods = os.path.abspath(os.path.expanduser(args.mods)) if args.mods else None
    if args.game_dir:
        game_dir = args.game_dir
    elif mods:
        # The pack's name: the folder above mods/ for an instance, else the folder itself.
        pack = os.path.basename(os.path.dirname(mods)) if os.path.basename(mods) == "mods" else os.path.basename(mods)
        game_dir = "run-matrix-" + re.sub(r"[^\w.-]+", "_", pack)
    else:
        game_dir = "run-matrix"
    xvfb = use_xvfb(args.xvfb)
    wrap = shlex.split(args.wrap) if args.wrap else []
    extra = ["-P" + p for p in args.property]
    gradle_flags = ["--no-daemon", f"--max-workers={args.max_workers}"]
    started = datetime.datetime.now()
    missing = sorted({jdk for _, jdk in targets if jdk not in jdks})

    mode = "dry run: nothing is built or started" if args.dry_run else f"report in build/test-matrix/{started:%Y%m%d-%H%M%S}"
    say(f"Just Replay test matrix, {len(targets)} target(s) ({mode})")
    games = running_games()
    say("Minecraft games running now: " + (", ".join(f"{k} pid {p}" for p, k, _ in games) if games else "none"))
    if missing:
        say(f"missing JDK {', '.join(map(str, missing))}: install it or pass --jdk <major>=<folder>")
    steps = []
    for index, (target, jdk) in enumerate(targets, 1):
        run_props = ["-Pselftest=true", "-PgameDir=" + game_dir] + extra
        say(f"{index:2}/{len(targets)} {target}  JDK {jdk}: {jdks.get(jdk, 'MISSING')}")
        source = mods_source(mods, target)
        if mods and source:
            refused = prepare_game_dir(target, game_dir, mods, True)
            if refused:
                say(f"    {refused}")
                return EXIT_USAGE
            run_props += place_mods(source, target, game_dir, True, say)
        elif mods:
            say(f"    mods: none for this target in {mods}")
        build_cmd = wrap + gradle_command(target) + gradle_flags + extra + ["build"]
        run_cmd = wrap + (["xvfb-run", "-a", "-s", "-screen 0 1920x1080x24"] if xvfb else []) \
            + gradle_command(target) + gradle_flags + ["runClient"] + run_props
        if not args.skip_build:
            say("    build: " + " ".join(shlex.quote(rel(c) if c.startswith(REPO) else c) for c in build_cmd))
        if not args.skip_run:
            say("    run:   " + " ".join(shlex.quote(rel(c) if c.startswith(REPO) else c) for c in run_cmd))
            say(f"    log:   {target}/{game_dir}/logs/latest.log")
        steps.append((target, jdk, source, build_cmd, run_cmd))
    if args.dry_run:
        say(f"{len(steps)} step(s) planned" + (", JDK missing" if missing else "") + ".")
        return EXIT_USAGE if missing else EXIT_OK
    if missing:
        return EXIT_USAGE

    report_dir = os.path.join(REPO, "build", "test-matrix", f"{started:%Y%m%d-%H%M%S}")
    os.makedirs(report_dir, exist_ok=True)
    options = " ".join(shlex.quote(a) for a in sys.argv[1:]) or "(none)"
    results = []
    worst = EXIT_OK
    for index, (target, jdk, source, build_cmd, run_cmd) in enumerate(steps, 1):
        tag = f"[{index}/{len(steps)}] {target}"
        result = {"target": target, "jdk": jdk, "notes": []}
        results.append(result)
        target_start = time.time()
        env = dict(os.environ, JAVA_HOME=jdks[jdk])
        env["PATH"] = os.path.join(jdks[jdk], "bin") + os.pathsep + env.get("PATH", "")
        if not wait_until_free(args.wait_busy, args.ignore_servers, say):
            say(f"{tag}: a Minecraft game is running, stopping here (a build under a running client crashes it)")
            result["notes"].append("not started: a Minecraft game was running")
            write_report(report_dir, started, results, options)
            return EXIT_BUSY
        if not args.skip_build:
            say(f"{tag}: build ...")
            build_start = time.time()
            code, seconds = run_logged(build_cmd, os.path.join(REPO, target), env,
                                       os.path.join(report_dir, f"gradle-build-{target}.log"), args.build_timeout * 60)
            result["unit_tests"] = unit_tests(target, build_start)
            if code is None:
                result["build"] = "TIMEOUT"
                result["notes"].append(f"build stopped after {args.build_timeout} min")
                worst = max(worst, EXIT_TIMEOUT)
            else:
                result["build"] = "OK" if code == 0 else "FAILED"
                if code != 0:
                    result["notes"].append(f"build failed (exit {code}), see gradle-build-{target}.log")
                    worst = max(worst, EXIT_FAILED)
            tests = result["unit_tests"]
            say(f"{tag}: build {result['build']} ({fmt_time(seconds)}"
                + (f", {tests[0]} unit tests, {tests[1]} failed" if tests else "") + ")")
            if result["build"] != "OK":
                result["seconds"] = time.time() - target_start
                write_report(report_dir, started, results, options)
                if args.stop_on_fail:
                    break
                continue
        if args.skip_run:
            result["seconds"] = time.time() - target_start
            write_report(report_dir, started, results, options)
            continue
        if not wait_until_free(args.wait_busy, args.ignore_servers, say):
            say(f"{tag}: a Minecraft game is running, stopping here")
            result["notes"].append("self test not started: a Minecraft game was running")
            write_report(report_dir, started, results, options)
            return EXIT_BUSY
        if source:
            prepare_game_dir(target, game_dir, mods, False)
            place_mods(source, target, game_dir, False, lambda text: None)
        else:
            prepare_game_dir(target, game_dir, None, False)
        game_root = os.path.join(REPO, target, game_dir)
        say(f"{tag}: self test ..." + (" (xvfb, software rendering: slow)" if xvfb else ""))
        run_start = time.time()
        run_env = dict(env, LIBGL_ALWAYS_SOFTWARE="1") if xvfb else env
        gradle_log = os.path.join(report_dir, f"gradle-run-{target}.log")
        code, seconds = run_logged(run_cmd, os.path.join(REPO, target), run_env, gradle_log, args.run_timeout * 60)
        if not wait_until_free(120, True, lambda text: None):
            result["notes"].append("a Minecraft client was still running 2 min after the self test ended")
        log = os.path.join(game_root, "logs", "latest.log")
        if os.path.isfile(log) and os.path.getmtime(log) >= run_start:
            result["log"] = rel(log)
            parsed = parse_selftest(read_lines(log))
        else:
            result["log"] = rel(gradle_log)
            result["notes"].append("no new game log: read the Gradle output instead")
            parsed = parse_selftest(read_lines(gradle_log))
        result["selftest"] = parsed
        crashes = new_files(os.path.join(game_root, "crash-reports"), run_start, ".txt")
        for crash in crashes:
            result["notes"].append(f"crash: {crash_cause(crash)} ({rel(crash)})")
        result["screenshots"] = len(new_files(os.path.join(game_root, "screenshots"), run_start, ".png"))
        if code is None:
            result["run"] = "TIMEOUT"
            result["notes"].append(f"game stopped after {args.run_timeout} min")
            worst = max(worst, EXIT_TIMEOUT)
        elif parsed["verdict"] == "PASS" and not crashes:
            result["run"] = "PASS"
        else:
            result["run"] = parsed["verdict"] if parsed["verdict"] != "PASS" else "CRASH"
            if parsed["verdict"] == "INCOMPLETE":
                result["notes"].append("the game closed before SELFTEST DONE" + (f" (exit {code})" if code else ""))
            worst = max(worst, EXIT_FAILED)
        with open(os.path.join(report_dir, f"selftest-{target}.txt"), "w", encoding="utf-8") as f:
            f.writelines([f"PASS  {line}\n" for line in parsed["pass"]] + [f"FAIL  {line}\n" for line in parsed["fail"]])
        result["seconds"] = time.time() - target_start
        say(f"{tag}: self test {result['run']} ({len(parsed['pass'])} PASS, {len(parsed['fail'])} FAIL, "
            f"{result['screenshots']} screenshots, {fmt_time(seconds)})")
        write_report(report_dir, started, results, options)
        if args.stop_on_fail and result["run"] != "PASS":
            break
    write_report(report_dir, started, results, options)
    say(f"report: {rel(os.path.join(report_dir, 'report.md'))}")
    return worst


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter,
                                     epilog="\n\n".join(__doc__.split("\n\n")[1:]))
    parser.add_argument("--dry-run", action="store_true", help="list the steps and their commands, run nothing")
    parser.add_argument("--targets", help="comma separated targets, loaders or Minecraft versions (default: all)")
    parser.add_argument("--mods", help="mods folder of a modpack, or a folder with one subfolder per target")
    parser.add_argument("--game-dir", help="game folder inside each target (default run-matrix, run-matrix-<pack> with --mods)")
    parser.add_argument("-P", dest="property", action="append", default=[], metavar="KEY=VALUE",
                        help="extra Gradle property for the build and the game (e.g. -P neo_version=21.1.265)")
    parser.add_argument("--jdk", action="append", default=[], metavar="MAJOR=FOLDER", help="JDK to use for a major version")
    parser.add_argument("--wrap", help='prefix of every build and game command, e.g. "vigie verrou-test --"')
    parser.add_argument("--xvfb", choices=("auto", "always", "never"), default="auto",
                        help="run the game in xvfb-run (auto: on Linux without a screen)")
    parser.add_argument("--build-timeout", type=int, default=30, metavar="MIN", help="minutes per build (default 30)")
    parser.add_argument("--run-timeout", type=int, default=30, metavar="MIN", help="minutes per self test (default 30)")
    parser.add_argument("--wait-busy", type=int, default=0, metavar="SEC",
                        help="seconds to wait for a running game to close before each step (default 0: stop at once)")
    parser.add_argument("--ignore-servers", action="store_true", help="only a running client blocks a step")
    parser.add_argument("--max-workers", type=int, default=2, help="Gradle workers (default 2)")
    parser.add_argument("--skip-build", action="store_true", help="start the self tests without building first")
    parser.add_argument("--skip-run", action="store_true", help="build only")
    parser.add_argument("--stop-on-fail", action="store_true", help="stop at the first failed target")
    parser.add_argument("--parse", metavar="LOG", help="print the PASS and FAIL lines of an existing game log")
    parser.add_argument("--list-games", action="store_true", help="list the Minecraft games running now")
    parser.add_argument("--self-check", action="store_true", help="check the log reader on known lines")
    args = parser.parse_args(argv)
    if args.self_check:
        return self_check()
    if args.list_games:
        games = running_games()
        for pid, kind, cmd in games:
            print(f"{kind:6} pid {pid}: {cmd}")
        print(f"{len(games)} Minecraft game(s) running")
        return EXIT_BUSY if games else EXIT_OK
    if args.parse:
        parsed = parse_selftest(read_lines(args.parse))
        for line in parsed["pass"]:
            print("PASS ", line)
        for line in parsed["fail"]:
            print("FAIL ", line)
        print(f"{parsed['verdict']}: {len(parsed['pass'])} PASS, {len(parsed['fail'])} FAIL, {parsed['info']} other "
              f"SELFTEST lines" + ("" if parsed["done"] else ", no SELFTEST DONE"))
        return EXIT_OK if parsed["verdict"] == "PASS" else EXIT_FAILED
    if args.skip_build and args.skip_run:
        parser.error("--skip-build and --skip-run leave nothing to do")
    return run_matrix(args)


if __name__ == "__main__":
    sys.exit(main())
