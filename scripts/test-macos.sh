#!/usr/bin/env bash
# Runs the macOS client's tests: the GTK-free Rust crates and the Swift tests.
# Builds the Rust core first if the app has not been built yet.
#
# Usage: ./scripts/test-macos.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Rust tests ==="
cargo test -p riff-core -p riff-player -p riff-ffi
cargo test -p riff-api -p riff-auth -p riff-config --no-default-features

echo "=== Swift tests ==="
if [[ ! -f build/macos/rustlib/libriff_ffi.a ]]; then
    ./scripts/build-macos.sh debug
fi

SWIFT_FLAGS=()
# The Command Line Tools ship swift-testing outside the default search path.
if ! xcode-select -p | grep -q Xcode.app; then
    CLT_DEV="$(xcode-select -p)/Library/Developer"
    SWIFT_FLAGS=(
        -Xswiftc -F -Xswiftc "$CLT_DEV/Frameworks"
        -Xlinker -F -Xlinker "$CLT_DEV/Frameworks"
        -Xlinker -rpath -Xlinker "$CLT_DEV/Frameworks"
        -Xlinker -rpath -Xlinker "$CLT_DEV/usr/lib"
    )
fi
RIFF_RUST_LIB_DIR="$REPO_ROOT/build/macos/rustlib" \
    swift test --package-path macos ${SWIFT_FLAGS[@]+"${SWIFT_FLAGS[@]}"}
