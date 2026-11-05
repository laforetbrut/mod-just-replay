#!/usr/bin/env bash
# Copies the mod jar of every target into jars/<mod version>/ at the repository root (a local
# folder, not in git): jars/2.0.0/, jars/2.0.1/... so the jars of earlier versions are kept.
# Run it after building: "./gradlew build" in each target folder, or pass --build to build them all
# first.
#
# Author: vyrriox
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

for dir in */; do
    target=${dir%/}
    if [[ ! "$target" =~ ^(neoforge|forge|fabric)-.+$ ]] || [ ! -f "$target/build.gradle" ]; then
        continue
    fi
    if [ "${1:-}" = "--build" ]; then
        echo "== building $target"
        (cd "$target" && ./gradlew build --console=plain -q)
    fi
    # The version of this target decides the folder, and which jar of build/libs is the current one.
    version=$(sed -n 's/^mod_version=//p' "$target/gradle.properties" | tr -d '\r' | head -n 1)
    if [ -z "$version" ]; then
        echo "$target: no mod_version in gradle.properties" >&2
        continue
    fi
    # The mod jar only: not the sources, dev or javadoc jars, nor a jar left by another version.
    jar="$target/build/libs/justreplay-$target-$version.jar"
    if [ ! -e "$jar" ]; then
        echo "$target: no jar for $version, build it first" >&2
        continue
    fi
    mkdir -p "jars/$version"
    cp "$jar" "jars/$version/"
    echo "$target: jars/$version/$(basename "$jar")"
done
