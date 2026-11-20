#!/usr/bin/env python3
"""Checks the layers of Just Replay (see docs/BUILDING.md), fit for CI.

  core/src/main/java, core/src/test/java
      Plain Java compiled into every target. It imports only the JDK, the libraries every target
      ships (Gson, Guava, SLF4J, fastutil, JOML, Apache Commons), JUnit in the tests, and other core
      classes. It names no Minecraft or loader class (import or full name), and no class of common/,
      whether imported or used by its simple name from the same package: core is compiled next to
      common in each target, so only this check sees such a slip.
  core and common/<mc>
      Never hold the same file twice (the build would see a duplicate class).
  common/<mc>/src/{main,test}/java
      No plain Java file identical in every version: it belongs once in core, same package. No
      loader import either (loader code goes to <loader>-<mc>/), except the files listed in
      COMMON_LOADER_FILES with their reason.

Prints one line per problem and how many files it read; exit status 1 on a problem.

    python3 tools/check_layers.py                # the repository this script is in
    python3 tools/check_layers.py --root <repo>
    python3 tools/check_layers.py --self-check   # small trees with one known mistake each: every
                                                 # mistake must be found, the clean tree must pass

Author: vyrriox
"""
import argparse
import os
import re
import shutil
import sys
import tempfile

MOD = "com.vyrriox.justreplay."
MINECRAFT = ("net.minecraft.", "com.mojang.")
LOADER = ("net.neoforged.", "net.minecraftforge.", "net.fabricmc.", "dev.architectury.", "org.quiltmc.")
# Libraries every target has on its classpath (the game ships them) and that exist outside the game.
PURE = ("java.", "javax.", "jdk.", "org.slf4j.", "com.google.gson.", "com.google.common.", "it.unimi.dsi.fastutil.",
        "org.joml.", "org.apache.commons.", "org.jetbrains.annotations.", "org.jspecify.")
TEST_LIBS = ("org.junit.", "org.opentest4j.")
# Loader code that stays in common/ on purpose: path under common/ -> why.
COMMON_LOADER_FILES = {
    "1.20.1/src/main/java/com/vyrriox/justreplay/compat/CuriosCompat.java":
        "Forge 47 code shared by forge-1.20.1 and neoforge-1.20.1; fabric-1.20.1 leaves it out for its own copy",
}

IMPORT = re.compile(r"^[ \t]*import[ \t]+(static[ \t]+)?([\w.]+?)(\.\*)?[ \t]*;", re.M)
PACKAGE = re.compile(r"^[ \t]*package[ \t]+([\w.]+)[ \t]*;", re.M)
GAME_NAME = re.compile(r"\b((?:net\.minecraft|com\.mojang|net\.neoforged|net\.minecraftforge|net\.fabricmc"
                       r"|dev\.architectury|org\.quiltmc)\.[\w.]*\w)")
MOD_NAME = re.compile(r"\b(com\.vyrriox\.justreplay\.[\w.]*\w)")
# Comments, text blocks, strings and chars, in the order a Java reader meets them.
NOT_CODE = re.compile(r'//[^\n]*|/\*.*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'', re.S)
WORD = re.compile(r"\b[A-Z]\w*\b")
SETS = ("main", "test")


class Source:
    """One Java file of a source root."""

    def __init__(self, layer, version, kind, root, path):
        self.layer, self.version, self.kind = layer, version, kind
        self.path = path
        self.rel = os.path.relpath(path, root).replace(os.sep, "/")
        with open(path, encoding="utf-8", errors="replace") as handle:
            self.text = handle.read().replace("\r\n", "\n")
        found = PACKAGE.search(self.text)
        self.pkg = found.group(1) if found else ""
        self.simple = os.path.basename(path)[:-5]
        self.fq = (self.pkg + "." if self.pkg else "") + self.simple
        self.code = NOT_CODE.sub(" ", self.text)
        self.imports = [(bool(m.group(1)), m.group(2), bool(m.group(3))) for m in IMPORT.finditer(self.code)]

    def shown(self, repo):
        return os.path.relpath(self.path, repo).replace(os.sep, "/")


def read_root(layer, version, kind, root):
    out = []
    if not os.path.isdir(root):
        return out
    for base, dirs, files in os.walk(root):
        dirs.sort()
        for name in sorted(files):
            if name.endswith(".java"):
                out.append(Source(layer, version, kind, root, os.path.join(base, name)))
    return out


def read_repo(repo):
    core = {kind: read_root("core", "", kind, os.path.join(repo, "core", "src", kind, "java")) for kind in SETS}
    common = {}
    top = os.path.join(repo, "common")
    if os.path.isdir(top):
        for version in sorted(os.listdir(top)):
            if os.path.isdir(os.path.join(top, version, "src")):
                common[version] = {kind: read_root("common", version, kind, os.path.join(top, version, "src", kind, "java"))
                                   for kind in SETS}
    return core, common


def resolve(name, known):
    """The class a full name points at (a nested class or a static member resolve to their top class)."""
    parts = name.split(".")
    for end in range(len(parts), 0, -1):
        candidate = ".".join(parts[:end])
        if candidate in known:
            return candidate
    return None


def mod_uses(src, classes_by_fq, simple_in_pkg):
    """Mod classes a file names: imports, full names in the code, simple names of its own package."""
    uses = set()
    for static, name, wildcard in src.imports:
        if not name.startswith(MOD):
            continue
        if wildcard and not static:
            uses.update(fq for fq in classes_by_fq if fq.rsplit(".", 1)[0] == name)
            continue
        target = resolve(name, classes_by_fq)
        uses.add(target or name)
    for name in MOD_NAME.findall(src.code):
        target = resolve(name, classes_by_fq)
        if target:
            uses.add(target)
    for word in set(WORD.findall(src.code)):
        fq = simple_in_pkg.get((src.pkg, word))
        if fq and fq != src.fq:
            uses.add(fq)
    uses.discard(src.fq)
    return uses


def check_core(repo, core, common, problems):
    core_fq = {"main": {s.fq for s in core["main"]}, "test": {s.fq for s in core["main"] + core["test"]}}
    common_fq = {}
    for version, sets in common.items():
        for kind in SETS:
            for s in sets[kind]:
                common_fq.setdefault(s.fq, set()).add(version)
    known = set(common_fq) | core_fq["test"]
    by_pkg = {}
    for fq in known:
        pkg, _, simple = fq.rpartition(".")
        by_pkg[(pkg, simple)] = fq
    for kind in SETS:
        allowed = core_fq[kind]
        for src in core[kind]:
            where = src.shown(repo)
            for static, name, wildcard in src.imports:
                if name.startswith(MOD):
                    continue
                if name.startswith(MINECRAFT):
                    problems.append(f"{where}: imports Minecraft ({name}): move that code to common/ or pass a plain value")
                elif name.startswith(LOADER):
                    problems.append(f"{where}: imports a loader ({name}): move that code to the loader folders")
                elif name.startswith(PURE) or (kind == "test" and name.startswith(TEST_LIBS)):
                    continue
                else:
                    problems.append(f"{where}: imports {name}, not a library every target ships (add it to PURE in "
                                    f"tools/check_layers.py if the game ships it on every version)")
            for name in sorted(set(GAME_NAME.findall(src.code))):
                problems.append(f"{where}: names {name} in its code: core never uses the game or a loader")
            for fq in sorted(mod_uses(src, known, by_pkg)):
                if fq in allowed:
                    continue
                if fq in common_fq:
                    problems.append(f"{where}: uses {fq} of common/ ({', '.join(sorted(common_fq[fq]))}): "
                                    f"move it to core too, or keep this class in common/")
                elif kind == "main" and fq in core_fq["test"]:
                    problems.append(f"{where}: uses the test class {fq}")
                else:
                    problems.append(f"{where}: uses {fq}, which is not in core")
    for kind in SETS:
        core_rel = {s.rel for s in core[kind]}
        for version, sets in common.items():
            for s in sets[kind]:
                if s.rel in core_rel:
                    problems.append(f"{s.shown(repo)}: also in core/src/{kind}/java: keep one copy")


def pure_imports(src, kind):
    for static, name, wildcard in src.imports:
        if name.startswith(MOD) or name.startswith(PURE) or (kind == "test" and name.startswith(TEST_LIBS)):
            continue
        return False
    # Game or loader names anywhere, strings included: reflection on the game is game code too.
    return not GAME_NAME.search(NOT_CODE.sub(lambda m: " " if m.group(0).startswith("/") else m.group(0), src.text))


def check_common_copies(repo, core, common, problems):
    """Plain Java that every version holds word for word: one copy in core is enough."""
    versions = sorted(common)
    if len(versions) < 2:
        return
    core_fq = {s.fq for s in core["main"] + core["test"]}
    for kind in SETS:
        first = {s.rel: s for s in common[versions[0]][kind]}
        others = [{s.rel: s for s in common[v][kind]} for v in versions[1:]]
        same = {rel for rel, s in first.items() if all(rel in o and o[rel].text == s.text for o in others)}
        files = {s.fq: s for s in common[versions[0]]["main"] + (common[versions[0]]["test"] if kind == "test" else [])}
        by_pkg = {(s.pkg, s.simple): s.fq for s in files.values()}
        for s in core["main"] + core["test"]:
            by_pkg.setdefault((s.pkg, s.simple), s.fq)
        known = set(files) | core_fq
        movable = {first[rel].fq for rel in same if pure_imports(first[rel], kind)}
        uses = {fq: mod_uses(files[fq], known, by_pkg) for fq in movable}
        changed = True
        while changed:
            changed = False
            for fq in sorted(movable):
                if any(u not in movable and u not in core_fq for u in uses[fq]):
                    movable.discard(fq)
                    changed = True
        for fq in sorted(movable):
            src = files[fq]
            problems.append(f"common/*/src/{kind}/java/{src.rel}: plain Java, identical in {', '.join(versions)}: "
                            f"keep one copy in core/src/{kind}/java/{src.rel}")


def check_common_loaders(repo, common, problems, exceptions):
    seen = set()
    for version, sets in common.items():
        for kind in SETS:
            for src in sets[kind]:
                key = f"{version}/src/{kind}/java/{src.rel}"
                loaders = sorted({n for _, n, _ in src.imports if n.startswith(LOADER)})
                if not loaders:
                    continue
                if key in exceptions:
                    seen.add(key)
                    continue
                problems.append(f"{src.shown(repo)}: common imports a loader ({', '.join(loaders)}): "
                                f"move that code to the loader folders")
    for key in sorted(set(exceptions) - seen):
        problems.append(f"common/{key}: listed in COMMON_LOADER_FILES but imports no loader (any more): "
                        f"remove it from the list")


def run(repo, exceptions=None):
    exceptions = COMMON_LOADER_FILES if exceptions is None else exceptions
    core, common = read_repo(repo)
    problems = []
    check_core(repo, core, common, problems)
    check_common_copies(repo, core, common, problems)
    check_common_loaders(repo, common, problems, exceptions)
    read = (len(core["main"]) + len(core["test"]), sum(len(s["main"]) + len(s["test"]) for s in common.values()))
    return problems, read, sorted(common)


# --- self check -------------------------------------------------------------------------------

def write(root, rel, text):
    path = os.path.join(root, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


P = "package com.vyrriox.justreplay.{0};\n\n"
CLEAN = {
    "core/src/main/java/com/vyrriox/justreplay/a/Good.java":
        P.format("a") + "import java.util.List;\n\n/** Uses Game in a comment only: \"Game\". */\n"
        "public final class Good { static List<Helper> all; static String s = \"net.minecraft.client.Minecraft\"; }\n",
    "core/src/main/java/com/vyrriox/justreplay/a/Helper.java": P.format("a") + "public final class Helper { }\n",
    "core/src/test/java/com/vyrriox/justreplay/a/GoodTest.java":
        P.format("a") + "import org.junit.jupiter.api.Test;\n\nclass GoodTest { @Test void t() { new Helper(); } }\n",
}
for _v in ("1.20.1", "1.21.1"):
    CLEAN[f"common/{_v}/src/main/java/com/vyrriox/justreplay/a/Game.java"] = (
        P.format("a") + "import net.minecraft.world.entity.Entity;\n\n"
        f"public final class Game {{ Entity e; Helper h; String v = \"{_v}\"; }}\n")
    CLEAN[f"common/{_v}/src/main/java/com/vyrriox/justreplay/b/Other.java"] = (
        P.format("b") + f"public final class Other {{ String v = \"{_v}\"; }}\n")
    CLEAN[f"common/{_v}/src/main/java/com/vyrriox/justreplay/b/Uses.java"] = (
        P.format("b") + "import com.vyrriox.justreplay.a.Game;\n\npublic final class Uses { Game g; }\n")
    CLEAN[f"common/{_v}/src/main/java/com/vyrriox/justreplay/b/Reflect.java"] = (
        P.format("b") + "public final class Reflect { String n = \"net.fabricmc.loader.api.FabricLoader\"; }\n")
    CLEAN[f"common/{_v}/src/test/java/com/vyrriox/justreplay/b/OtherTest.java"] = (
        P.format("b") + "import org.junit.jupiter.api.Test;\n\nclass OtherTest { @Test void t() { new Other(); } }\n")

CORE_BAD = "core/src/main/java/com/vyrriox/justreplay/a/Bad.java"
CASES = [
    ("core imports Minecraft", {CORE_BAD: P.format("a") + "import net.minecraft.world.entity.Entity;\n\nclass Bad { }\n"},
     "imports Minecraft"),
    ("core imports a loader", {CORE_BAD: P.format("a") + "import net.neoforged.bus.api.SubscribeEvent;\n\nclass Bad { }\n"},
     "imports a loader"),
    ("core names a game class in code", {CORE_BAD: P.format("a") + "class Bad { net.minecraft.client.Minecraft mc; }\n"},
     "names net.minecraft.client.Minecraft"),
    ("core imports another library", {CORE_BAD: P.format("a") + "import org.lwjgl.BufferUtils;\n\nclass Bad { }\n"},
     "not a library every target ships"),
    ("core main imports JUnit", {CORE_BAD: P.format("a") + "import org.junit.jupiter.api.Test;\n\nclass Bad { }\n"},
     "not a library every target ships"),
    ("core uses a common class of its package", {CORE_BAD: P.format("a") + "class Bad { Game g; }\n"},
     "uses com.vyrriox.justreplay.a.Game of common/"),
    ("core imports a common class", {CORE_BAD: P.format("a") + "import com.vyrriox.justreplay.b.Other;\n\nclass Bad { Other o; }\n"},
     "uses com.vyrriox.justreplay.b.Other of common/"),
    ("core test uses a common class", {"core/src/test/java/com/vyrriox/justreplay/b/BadTest.java":
                                       P.format("b") + "class BadTest { Uses u; }\n"},
     "uses com.vyrriox.justreplay.b.Uses of common/"),
    ("a class in core and in common", {"core/src/main/java/com/vyrriox/justreplay/b/Other.java":
                                       P.format("b") + "public final class Other { }\n"},
     "also in core/src/main/java"),
    ("plain Java in triple", {f"common/{v}/src/main/java/com/vyrriox/justreplay/b/Twin.java":
                              P.format("b") + "import com.vyrriox.justreplay.a.Helper;\nimport java.util.Map;\n\n"
                              "public final class Twin { Map<String, Helper> m; }\n"
                              for v in ("1.20.1", "1.21.1")},
     "b/Twin.java: plain Java, identical in 1.20.1, 1.21.1"),
    ("plain test in triple", {f"common/{v}/src/test/java/com/vyrriox/justreplay/a/HelperTest.java":
                              P.format("a") + "import org.junit.jupiter.api.Test;\n\nclass HelperTest { @Test void t() { new Helper(); } }\n"
                              for v in ("1.20.1", "1.21.1")},
     "a/HelperTest.java: plain Java, identical in 1.20.1, 1.21.1"),
    ("common imports a loader", {"common/1.21.1/src/main/java/com/vyrriox/justreplay/b/Glue.java":
                                 P.format("b") + "import net.fabricmc.api.ClientModInitializer;\n\nclass Glue { }\n"},
     "common imports a loader (net.fabricmc.api.ClientModInitializer)"),
]


def self_check():
    failures = []
    with tempfile.TemporaryDirectory(prefix="check-layers-") as tmp:
        def tree(name, extra):
            root = os.path.join(tmp, name)
            for rel, text in {**CLEAN, **extra}.items():
                write(root, rel, text)
            return root

        problems, read, _ = run(tree("clean", {}), exceptions={})
        if problems or read != (3, 10):
            failures.append(f"clean tree: expected no problem in 3 + 10 files, got {read}: {problems}")
        for n, (label, extra, expected) in enumerate(CASES):
            problems, _, _ = run(tree(f"case{n}", extra), exceptions={})
            if not any(expected in p for p in problems):
                failures.append(f"{label}: expected '{expected}', got {problems}")
        listed = "1.21.1/src/main/java/com/vyrriox/justreplay/b/Other.java"
        problems, _, _ = run(tree("stale", {}), exceptions={listed: "test"})
        if not any("imports no loader" in p for p in problems):
            failures.append(f"stale exception: expected 'imports no loader', got {problems}")
        glue = {"common/1.21.1/src/main/java/com/vyrriox/justreplay/b/Glue.java":
                P.format("b") + "import net.fabricmc.api.ClientModInitializer;\n\nclass Glue { }\n"}
        problems, _, _ = run(tree("listed", glue), exceptions={"1.21.1/src/main/java/com/vyrriox/justreplay/b/Glue.java": "t"})
        if problems:
            failures.append(f"listed loader file: expected no problem, got {problems}")
    for f in failures:
        print("FAIL " + f)
    print(f"check_layers self check: {len(CASES) + 3 - len(failures)}/{len(CASES) + 3} cases pass")
    return 1 if failures else 0


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="repository root (default: the one holding this script)")
    parser.add_argument("--self-check", action="store_true", help="check the checks on known mistakes")
    args = parser.parse_args()
    if args.self_check:
        return self_check()
    repo = os.path.abspath(args.root)
    if not os.path.isdir(os.path.join(repo, "core")):
        print("check_layers: no core/ folder here (a target branch?), nothing to check")
        return 0
    problems, read, versions = run(repo)
    for p in problems:
        print(p)
    print(f"check_layers: {len(problems)} problem(s); read {read[0]} core file(s) and {read[1]} common file(s) "
          f"of {len(versions)} version(s) ({', '.join(versions)})")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
