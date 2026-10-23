#!/usr/bin/env bash
# Builds the native macOS client (macos/) into build/macos/Riff.app.
#
# Steps: generate the Spotify API crate if needed, build the Rust core
# (crates/riff-ffi), generate its Swift bindings, convert translations, build
# the SwiftUI app with SwiftPM, then assemble and ad-hoc sign the app bundle.
#
# Requirements: macOS 26, Xcode or the Command Line Tools (Swift 6.2+),
# Rust (aarch64-apple-darwin), Java (for the API generator).
#
# Usage: ./scripts/build-macos.sh [debug|release] [--dmg] [--run]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PROFILE="release"
MAKE_DMG=0
RUN_APP=0
for arg in "$@"; do
    case "$arg" in
        debug | release) PROFILE="$arg" ;;
        --dmg) MAKE_DMG=1 ;;
        --run) RUN_APP=1 ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

APP_NAME="Riff"
BUNDLE_ID="com.aidenir.Riff"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)"
OUT_DIR="$REPO_ROOT/build/macos"
MACOS_DIR="$REPO_ROOT/macos"
FFI_DIR="$MACOS_DIR/Sources/RiffFFI"
CORE_DIR="$MACOS_DIR/Sources/RiffCore"
export MACOSX_DEPLOYMENT_TARGET=26.0

if [[ "$PROFILE" == "release" ]]; then
    CARGO_FLAGS=(--release)
    RUST_LIB_DIR="$REPO_ROOT/target/release"
else
    CARGO_FLAGS=()
    RUST_LIB_DIR="$REPO_ROOT/target/debug"
fi

echo "=== Spotify API crate ==="
if [[ ! -d generated/spotify-api ]]; then
    ./scripts/generate-spotify-api.sh
else
    echo "  generated/spotify-api present"
fi

echo "=== Rust core ($PROFILE) ==="
cargo build -p riff-ffi ${CARGO_FLAGS[@]+"${CARGO_FLAGS[@]}"}

echo "=== Swift bindings ==="
BINDINGS_TMP="$(mktemp -d)"
trap 'rm -rf "$BINDINGS_TMP"' EXIT
cargo run -q -p uniffi-bindgen -- generate \
    --metadata-no-deps \
    --library "$RUST_LIB_DIR/libriff_ffi.dylib" \
    --language swift \
    --out-dir "$BINDINGS_TMP"
mkdir -p "$FFI_DIR/include" "$CORE_DIR"
cp "$BINDINGS_TMP/riff_ffiFFI.h" "$FFI_DIR/include/"
cp "$BINDINGS_TMP/riff_ffiFFI.modulemap" "$FFI_DIR/include/module.modulemap"
cp "$BINDINGS_TMP/riff_ffi.swift" "$CORE_DIR/RiffCore.swift"

echo "=== Translations ==="
python3 scripts/po-to-strings.py po "$MACOS_DIR/Resources"

echo "=== App icon ==="
"$REPO_ROOT/scripts/make-macos-icon.sh" "$MACOS_DIR/Resources/AppIcon.icns"

echo "=== Swift app ($PROFILE) ==="
# The static library must win over the dylib of the same name.
mkdir -p "$OUT_DIR/rustlib"
cp "$RUST_LIB_DIR/libriff_ffi.a" "$OUT_DIR/rustlib/"
RIFF_RUST_LIB_DIR="$OUT_DIR/rustlib" swift build \
    --package-path "$MACOS_DIR" \
    -c "$PROFILE" \
    --product "$APP_NAME"
BIN_DIR="$(RIFF_RUST_LIB_DIR="$OUT_DIR/rustlib" swift build --package-path "$MACOS_DIR" -c "$PROFILE" --show-bin-path)"

echo "=== App bundle ==="
APP="$OUT_DIR/$APP_NAME.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/$APP_NAME" "$APP/Contents/MacOS/$APP_NAME"
sed -e "s/@VERSION@/$VERSION/g" -e "s/@BUNDLE_ID@/$BUNDLE_ID/g" \
    "$MACOS_DIR/Resources/Info.plist" >"$APP/Contents/Info.plist"
cp "$MACOS_DIR/Resources/AppIcon.icns" "$APP/Contents/Resources/"
cp -R "$MACOS_DIR"/Resources/*.lproj "$APP/Contents/Resources/"
printf 'APPL????' >"$APP/Contents/PkgInfo"

# Ad-hoc signature with the hardened runtime. Swap "-" for a Developer ID
# identity (and add notarization) once one is available.
codesign --force --options runtime \
    --entitlements "$MACOS_DIR/Resources/Riff.entitlements" \
    --sign - "$APP"
echo "  $APP"

if [[ "$MAKE_DMG" -eq 1 ]]; then
    echo "=== DMG ==="
    DMG="$OUT_DIR/$APP_NAME-$VERSION.dmg"
    STAGING="$OUT_DIR/dmg"
    rm -rf "$STAGING" "$DMG"
    mkdir -p "$STAGING"
    cp -R "$APP" "$STAGING/"
    ln -s /Applications "$STAGING/Applications"
    hdiutil create -volname "$APP_NAME" -srcfolder "$STAGING" -ov -format UDZO "$DMG" >/dev/null
    rm -rf "$STAGING"
    echo "  $DMG"
fi

if [[ "$RUN_APP" -eq 1 ]]; then
    RUST_LOG="${RUST_LOG:-riff_ffi=debug,riff_player=info,librespot=warn}" \
        "$APP/Contents/MacOS/$APP_NAME"
fi
