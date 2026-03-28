#!/usr/bin/env bash
#
# Run ktlint on all Kotlin sources under android/.
#
# We use the standalone ktlint CLI rather than the Gradle plugin because
# the library's source files (android/src/) are not part of a standalone
# Gradle project — they are compiled inside the host app at build time.
# The Gradle plugin in the test harness can only lint sources registered
# in the harness's own source sets, and those include copies (via
# syncPipelineSource) rather than the originals that get committed.
# The standalone CLI avoids all of that and lints the actual source files.
#
# Downloads the ktlint binary on first run.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/.."
KTLINT_VERSION="1.8.0"
KTLINT_BIN="$SCRIPT_DIR/.ktlint-$KTLINT_VERSION"

if [ ! -f "$KTLINT_BIN" ]; then
  echo "Downloading ktlint $KTLINT_VERSION..."
  curl -fsSL "https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint" -o "$KTLINT_BIN"
  chmod +x "$KTLINT_BIN"
fi

"$KTLINT_BIN" "$@" "$ANDROID_DIR/src/**/*.kt" "$ANDROID_DIR/test-harness/app/src/**/*.kt"
