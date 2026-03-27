#!/usr/bin/env bash
# Download Hunspell dictionaries for OCR spell correction.
# Source repo: https://github.com/wooorm/dictionaries
#
# Dictionaries are placed in the APK's assets/hunspell/ directory so the
# pipeline can read them via context.assets. They are .gitignored — run
# this script before building the test harness.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DICT_DIR="$SCRIPT_DIR/../app/src/main/assets/hunspell-all"

BASE_URL="https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries"

langs="en en-GB fr de it el"

mkdir -p "$DICT_DIR"

for lang in $langs; do
  if [ -f "$DICT_DIR/$lang.dic" ] && [ -f "$DICT_DIR/$lang.aff" ]; then
    echo "$lang dictionary already present, skipping"
    continue
  fi
  echo "Downloading $lang dictionary..."
  curl -fsSL "$BASE_URL/$lang/index.dic" -o "$DICT_DIR/$lang.dic"
  curl -fsSL "$BASE_URL/$lang/index.aff" -o "$DICT_DIR/$lang.aff"
done

echo "All dictionaries downloaded to $DICT_DIR"
