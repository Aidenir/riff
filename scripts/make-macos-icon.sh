#!/usr/bin/env bash
# Renders the app icon SVG into a macOS .icns file.
#
# Usage: ./scripts/make-macos-icon.sh <output.icns>

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SVG="$REPO_ROOT/data/hicolor/scalable/apps/dev.diegovsky.Riff.svg"
OUT="$1"

if [[ -f "$OUT" && "$OUT" -nt "$SVG" ]]; then
    echo "  $OUT up to date"
    exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

BASE="$WORK/icon.png"
swift "$REPO_ROOT/scripts/render-svg.swift" "$SVG" "$BASE" 1024
if [[ ! -f "$BASE" ]]; then
    echo "Could not render $SVG" >&2
    exit 1
fi

ICONSET="$WORK/AppIcon.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$BASE" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$BASE" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
mkdir -p "$(dirname "$OUT")"
iconutil -c icns "$ICONSET" -o "$OUT"
echo "  $OUT"
