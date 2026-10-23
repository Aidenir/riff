#!/usr/bin/env bash
# Runs the Android client's tests: the shared Rust crates, the Kotlin unit
# tests, and (with --smoke) the debug smoke test on a connected device.
#
# The smoke test walks every page and playback action of an installed,
# logged-in debug build, writes its log to build/android/smoke/smoke.log and
# takes a screenshot at every step. Build and install first with
# ./scripts/build-android.sh debug --install.
#
# Usage: ./scripts/test-android.sh [--smoke] [--no-rust]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SMOKE=0
RUST=1
for arg in "$@"; do
    case "$arg" in
        --smoke) SMOKE=1 ;;
        --no-rust) RUST=0 ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

if [[ -f "$HOME/.cargo/env" ]]; then
    # shellcheck disable=SC1091
    source "$HOME/.cargo/env"
fi

if [[ "$RUST" -eq 1 ]]; then
    echo "=== Rust tests ==="
    cargo test -p riff-core -p riff-player
    cargo test -p riff-api -p riff-auth -p riff-config --no-default-features
    if [[ "$(uname)" == "Darwin" ]]; then
        cargo test -p riff-ffi
    fi
fi

echo "=== Kotlin tests ==="
if [[ ! -d android/uniffi ]]; then
    echo "Kotlin bindings missing; run ./scripts/build-android.sh first." >&2
    exit 1
fi
(cd android && ./gradlew --no-daemon -q testDebugUnitTest)
echo "  passed"

if [[ "$SMOKE" -eq 0 ]]; then
    exit 0
fi

echo "=== Smoke test ==="
PACKAGE="com.aidenir.riff.debug"
OUT="$REPO_ROOT/build/android/smoke"
rm -rf "$OUT"
mkdir -p "$OUT"
# Foldables have several displays; capture the first (the main screen).
DISPLAY_ARGS=()
DISPLAY_ID="$(adb shell dumpsys SurfaceFlinger --display-id 2>/dev/null | awk '/^Display/ {print $2; exit}')"
if [[ -n "$DISPLAY_ID" ]]; then
    DISPLAY_ARGS=(-d "$DISPLAY_ID")
fi
adb shell input keyevent KEYCODE_WAKEUP
adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -n "$PACKAGE/com.aidenir.riff.MainActivity" -e smoke 1 >/dev/null

step=0
deadline=$((SECONDS + 600))
result=1
while IFS= read -r line; do
    echo "$line" >>"$OUT/smoke.log"
    case "$line" in
        *"SMOKE step: "*)
            step=$((step + 1))
            name="${line#*SMOKE step: }"
            echo "  step $step: $name"
            ;;
        *"SMOKE   ok: "* | *"SMOKE   FAIL: "*)
            echo "    ${line#*SMOKE   }"
            # Let the page settle, then capture it.
            sleep 1
            adb exec-out screencap -p "${DISPLAY_ARGS[@]}" >"$OUT/$(printf '%02d' "$step").png" || true
            ;;
        *"SMOKE DONE failures="*)
            failures="${line#*failures=}"
            echo "  done, $failures failures"
            [[ "$failures" == "0" ]] && result=0
            break
            ;;
        *"FATAL EXCEPTION"* | *"Fatal signal"*)
            echo "  CRASH: $line"
            break
            ;;
    esac
    if [[ "$SECONDS" -gt "$deadline" ]]; then
        echo "  timed out"
        break
    fi
done < <(adb logcat -v brief RiffSmoke:I AndroidRuntime:E DEBUG:F libc:F '*:S')
kill "$!" 2>/dev/null || true
adb logcat -d >"$OUT/logcat.txt"
echo "  log and screenshots in $OUT"
exit "$result"
