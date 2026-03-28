#!/usr/bin/env bash
#
# Run SwiftLint on all Swift sources under ios/.
#
# Downloads the SwiftLint binary on first run, similar to android/scripts/ktlint.sh.
# This avoids requiring a global swiftlint install for local development or CI.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IOS_DIR="$SCRIPT_DIR/.."
SWIFTLINT_VERSION="0.63.2"
SWIFTLINT_BIN="$SCRIPT_DIR/.swiftlint-$SWIFTLINT_VERSION"

if [ ! -f "$SWIFTLINT_BIN" ]; then
  echo "Downloading SwiftLint $SWIFTLINT_VERSION..."
  curl -fsSL "https://github.com/realm/SwiftLint/releases/download/$SWIFTLINT_VERSION/portable_swiftlint.zip" -o "$SCRIPT_DIR/.swiftlint.zip"
  unzip -o "$SCRIPT_DIR/.swiftlint.zip" swiftlint -d "$SCRIPT_DIR"
  mv "$SCRIPT_DIR/swiftlint" "$SWIFTLINT_BIN"
  rm "$SCRIPT_DIR/.swiftlint.zip"
fi

cd "$IOS_DIR"
"$SWIFTLINT_BIN" "$@"
