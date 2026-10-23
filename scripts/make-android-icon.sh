#!/usr/bin/env bash
# Renders the app icon SVG into the Android adaptive icon layers
# (android/app/src/main/res/mipmap-*). Needs macOS (AppKit renders the SVG);
# the PNGs are checked in so other hosts do not need to run this.
#
# Usage: ./scripts/make-android-icon.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SVG="$REPO_ROOT/data/hicolor/scalable/apps/dev.diegovsky.Riff.svg"
RES="$REPO_ROOT/android/app/src/main/res"

# Adaptive icon layers are 108 dp.
for density in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    name="${density%%:*}"
    px="${density##*:}"
    mkdir -p "$RES/mipmap-$name"
    swift "$REPO_ROOT/scripts/render-android-icon.swift" "$SVG" "$RES/mipmap-$name/ic_launcher_foreground.png" "$px"
    swift "$REPO_ROOT/scripts/render-android-icon.swift" "$SVG" "$RES/mipmap-$name/ic_launcher_monochrome.png" "$px" mono
done
echo "  $RES/mipmap-*"
