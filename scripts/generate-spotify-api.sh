#!/usr/bin/env bash
# Generates the Rust Spotify API client from the checked-in OpenAPI spec.
#
# Requirements: Java (for openapi-generator-cli)
#
# Usage: ./scripts/generate-spotify-api.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SPEC_FILE="spotify-openapi.yaml"
GENERATOR_VERSION="7.12.0"
GENERATOR_JAR="/tmp/openapi-generator-cli-${GENERATOR_VERSION}.jar"
OUTPUT_DIR="generated/spotify-api"

if [[ ! -f "$SPEC_FILE" ]]; then
    echo "Error: $SPEC_FILE not found in repository root." >&2
    echo "Run ./scripts/fetch-spotify-spec.sh to download the spec first." >&2
    exit 1
fi

echo "=== Downloading OpenAPI Generator (if needed) ==="
if [[ ! -f "$GENERATOR_JAR" ]]; then
    curl -fSL -o "$GENERATOR_JAR" \
        "https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/${GENERATOR_VERSION}/openapi-generator-cli-${GENERATOR_VERSION}.jar"
fi
echo "  Using: $GENERATOR_JAR"

echo ""
echo "=== Generating Rust client ==="
rm -rf "$OUTPUT_DIR"
java -jar "$GENERATOR_JAR" generate \
    -i "$SPEC_FILE" \
    -g rust \
    -o "$OUTPUT_DIR" \
    --package-name spotify-api \
    --additional-properties=library=reqwest,supportMiddleware=true \
    --type-mappings=file=String

# Clean up unnecessary generated files
rm -f "$OUTPUT_DIR/git_push.sh" "$OUTPUT_DIR/.travis.yml" "$OUTPUT_DIR/.gitignore"

# Android has no system OpenSSL: use rustls there, like the riff crates do.
python3 - "$OUTPUT_DIR/Cargo.toml" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
line = 'reqwest = { version = "^0.12", features = ["json", "multipart"] }\n'
assert line in text, "unexpected reqwest dependency in the generated crate"
text = text.replace(line, "")
text += """
[target.'cfg(not(target_os = "android"))'.dependencies]
reqwest = { version = "^0.12", features = ["json", "multipart"] }

[target.'cfg(target_os = "android")'.dependencies]
reqwest = { version = "^0.12", default-features = false, features = ["json", "multipart", "charset", "http2", "rustls-tls"] }
"""
open(path, "w").write(text)
PY

echo ""
echo "=== Done ==="
echo "  Generated crate: $OUTPUT_DIR"
echo "  Verify: cd $OUTPUT_DIR && cargo check"
