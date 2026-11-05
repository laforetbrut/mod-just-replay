#!/usr/bin/env bash
# Regenerates the per-target branches from main.
#
# main holds one folder per target (<loader>-<minecraft version>/) and the shared code of each
# Minecraft version (common/<minecraft version>/src/main). Each target also has a branch named like
# its folder, holding that single project at the repository root with the shared code merged into
# src/main, so it builds on its own with ./gradlew build.
#
# The branches are never edited by hand: change main, then run this script from the repository
# root. An existing branch gets a new commit on top of its history (no force push needed); a branch
# whose content did not change is left alone.
#
# Author: vyrriox
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

if [ -n "$(git status --porcelain)" ]; then
    echo "Commit or stash your changes first: the branches are built from the main branch as committed." >&2
    exit 1
fi

MAIN=$(git rev-parse --verify refs/heads/main)
# Shared files copied to every branch when they exist on main.
SHARED=(LICENSE README.md CHANGELOG.md CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md .gitignore .gitattributes .github docs)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

for target in $(git ls-tree -d --name-only "$MAIN"); do
    if [[ ! "$target" =~ ^(neoforge|forge|fabric)-(.+)$ ]]; then
        continue
    fi
    version=${BASH_REMATCH[2]}
    if ! git cat-file -e "$MAIN:$target/build.gradle" 2>/dev/null; then
        continue
    fi

    tree_dir="$WORK/$target"
    mkdir -p "$tree_dir"

    # The project itself, moved to the root.
    git archive "$MAIN" "$target" | tar -x -C "$WORK/"
    # Shared code of this Minecraft version, merged into src/main.
    if git cat-file -e "$MAIN:common/$version/src/main" 2>/dev/null; then
        mkdir -p "$WORK/common-$target"
        git archive "$MAIN" "common/$version/src/main" | tar -x -C "$WORK/common-$target"
        mkdir -p "$tree_dir/src/main"
        cp -R "$WORK/common-$target/common/$version/src/main/." "$tree_dir/src/main/"
        rm -rf "$WORK/common-$target"
    fi
    # Shared documents and repository files.
    for item in "${SHARED[@]}"; do
        if git cat-file -e "$MAIN:$item" 2>/dev/null; then
            git archive "$MAIN" "$item" | tar -x -C "$tree_dir"
        fi
    done

    # Build the tree with a throwaway index, without touching the checked out branch.
    export GIT_INDEX_FILE="$WORK/index-$target"
    rm -f "$GIT_INDEX_FILE"
    git --work-tree="$tree_dir" add -A .
    git --work-tree="$tree_dir" update-index --chmod=+x gradlew 2>/dev/null || true
    tree=$(git write-tree)
    unset GIT_INDEX_FILE

    old=$(git rev-parse -q --verify "refs/heads/$target" || true)
    if [ -n "$old" ] && [ "$(git rev-parse "$old^{tree}")" = "$tree" ]; then
        echo "$target: unchanged"
        continue
    fi
    if [ -n "$old" ]; then
        commit=$(git commit-tree "$tree" -p "$old" -p "$MAIN" -m "chore: update $target project from main")
    else
        commit=$(git commit-tree "$tree" -p "$MAIN" -m "chore: isolate $target project at repository root")
    fi
    git update-ref "refs/heads/$target" "$commit"
    echo "$target: $(git rev-parse --short "$commit")"
done
