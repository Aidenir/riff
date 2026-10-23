#!/usr/bin/env bash
# Builds the native Android client (android/) into an APK.
#
# Steps: generate the Spotify API crate if needed, cross-compile the Rust core
# (crates/riff-ffi) for arm64-v8a and x86_64 with cargo-ndk, generate its
# Kotlin bindings, convert translations, then build the app with Gradle.
#
# Requirements: rustup with the aarch64-linux-android and x86_64-linux-android
# targets, cargo-ndk, the Android SDK (platform 37) and NDK, Java 17, and Java
# for the API generator. ANDROID_HOME and ANDROID_NDK_HOME are found in the
# usual places when unset.
#
# The Rust core is always built in release mode: librespot decodes too slowly
# unoptimised. "debug" and "release" select the Android build type.
#
# Release signing reads RIFF_KEYSTORE, RIFF_KEYSTORE_PASSWORD, RIFF_KEY_ALIAS
# and RIFF_KEY_PASSWORD from the environment.
#
# Usage: ./scripts/build-android.sh [debug|release] [--abi arm64-v8a|x86_64] [--install]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PROFILE="debug"
INSTALL=0
ABIS=(arm64-v8a x86_64)
while [[ $# -gt 0 ]]; do
    case "$1" in
        debug | release) PROFILE="$1" ;;
        --install) INSTALL=1 ;;
        --abi)
            shift
            ABIS=("$1")
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
    shift
done

if [[ -f "$HOME/.cargo/env" ]]; then
    # shellcheck disable=SC1091
    source "$HOME/.cargo/env"
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for candidate in "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
        if [[ -d "$candidate" ]]; then
            export ANDROID_HOME="$candidate"
            break
        fi
    done
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
    export ANDROID_NDK_HOME
fi
echo "  SDK: $ANDROID_HOME"
echo "  NDK: $ANDROID_NDK_HOME"

ANDROID_DIR="$REPO_ROOT/android"
JNI_DIR="$ANDROID_DIR/app/src/main/jniLibs"
BINDINGS_DIR="$ANDROID_DIR/uniffi"

echo "=== Spotify API crate ==="
if [[ ! -d generated/spotify-api ]]; then
    ./scripts/generate-spotify-api.sh
else
    echo "  generated/spotify-api present"
fi

echo "=== Rust core (${ABIS[*]}) ==="
TARGET_ARGS=()
for abi in "${ABIS[@]}"; do
    TARGET_ARGS+=(-t "$abi")
done
rm -rf "$JNI_DIR"
cargo ndk "${TARGET_ARGS[@]}" -P 33 -o "$JNI_DIR" build -p riff-ffi --lib --release

echo "=== Kotlin bindings ==="
FIRST_LIB="$(find "$JNI_DIR" -name libriff_ffi.so | head -1)"
rm -rf "$BINDINGS_DIR"
cargo run -q -p uniffi-bindgen -- generate \
    --metadata-no-deps \
    --library "$FIRST_LIB" \
    --language kotlin \
    --no-format \
    --out-dir "$BINDINGS_DIR"

echo "=== Translations ==="
python3 scripts/po-to-android.py po "$ANDROID_DIR/app/src/main/res"

echo "=== Android app ($PROFILE) ==="
TASK="assemble$(tr '[:lower:]' '[:upper:]' <<<"${PROFILE:0:1}")${PROFILE:1}"
(cd "$ANDROID_DIR" && ./gradlew --no-daemon -q "$TASK")
APK="$(find "$ANDROID_DIR/app/build/outputs/apk/$PROFILE" -name '*.apk' | head -1)"
echo "  $APK"

if [[ "$INSTALL" -eq 1 ]]; then
    echo "=== Install ==="
    adb install -r "$APK"
fi
