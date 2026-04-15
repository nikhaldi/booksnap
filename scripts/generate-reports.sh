#!/usr/bin/env bash
#
# Generate the reports site into a local directory.
#
# Usage:
#   bash scripts/generate-reports.sh [output-dir]
#
# If output-dir is omitted, defaults to tmp/reports-site/ in the repo root.
# When an output-dir already contains previous reports (e.g. cloned from
# gh-pages), new reports are added alongside them.
#
# Reads the version from package.json. For each platform (android, ios)
# that has a lab directory, this script:
#   1. Generates a fresh diff report via `uv run diff-report`
#   2. Generates an experiment progress plot via `arl plot`
#   3. Generates an index.html listing all versions

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=$(python3 -c "import json; print(json.load(open('$REPO_ROOT/package.json'))['version'])")
OUTPUT_DIR="${1:-$REPO_ROOT/tmp/reports-site}"
DATA_DIR="$REPO_ROOT/datasets/booksnap-base"
PLOT_YMAX=0.25
PLOT_YLABEL="CER (lower is better)"

mkdir -p "$OUTPUT_DIR"

echo "Generating reports for version $VERSION into $OUTPUT_DIR..."

# Generate reports and plots for each platform
for platform in android ios; do
  LAB_DIR="$REPO_ROOT/$platform/lab"
  if [ ! -f "$LAB_DIR/lab.toml" ]; then
    echo "No $platform lab found, skipping."
    continue
  fi

  DEST="$OUTPUT_DIR/reports/$VERSION/$platform"
  rm -rf "$DEST"
  mkdir -p "$DEST"

  PLATFORM_LABEL="$platform $VERSION"

  echo "Generating $platform report..."
  cd "$LAB_DIR"
  uv run diff-report \
    --data "$DATA_DIR" \
    --output "$DEST/report.html" \
    --bounds \
    --version "$PLATFORM_LABEL"

  echo "  Generated $platform report at reports/$VERSION/$platform/"

  # Generate experiment progress plot
  echo "Generating $platform progress plot..."
  uv run arl plot \
    --output "$DEST/progress.png" \
    --title "$PLATFORM_LABEL — Experiment Progress (v$VERSION)" \
    --no-labels \
    --ymax "$PLOT_YMAX" \
    --ylabel "$PLOT_YLABEL" \
    --figsize 10x5
  echo "  Generated $platform plot at reports/$VERSION/$platform/progress.png"
done

# Generate a combined latest plot at the root for the README.
# arl plot automatically includes the current lab's results.tsv, so we only
# pass the other labs as extras to avoid duplicates.
echo "Generating combined latest progress plot..."
EXTRA_PLOT_ARGS=()
for platform in android ios; do
  LAB_DIR="$REPO_ROOT/$platform/lab"
  if [ ! -f "$LAB_DIR/lab.toml" ]; then
    continue
  fi
  if [ -z "$PRIMARY_LAB" ]; then
    PRIMARY_LAB="$LAB_DIR"
  elif [ -f "$LAB_DIR/results.tsv" ]; then
    EXTRA_PLOT_ARGS+=("$LAB_DIR/results.tsv")
  fi
done
if [ -n "$PRIMARY_LAB" ]; then
  cd "$PRIMARY_LAB"
  uv run arl plot \
    "${EXTRA_PLOT_ARGS[@]}" \
    --output "$OUTPUT_DIR/progress.png" \
    --title "BookSnap v$VERSION — Experiment Progress" \
    --no-labels \
    --ymax "$PLOT_YMAX" \
    --ylabel "$PLOT_YLABEL" \
    --figsize 10x5
  echo "  Generated combined plot at progress.png"
fi

# Generate index.html
cat > "$OUTPUT_DIR/index.html" << 'HEADER'
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>BookSnap Reports</title>
<style>
  body { font-family: -apple-system, sans-serif; max-width: 700px; margin: 40px auto; padding: 0 20px; color: #333; }
  h1 { border-bottom: 1px solid #eee; padding-bottom: 10px; }
  .version { margin-bottom: 20px; }
  .version h2 { margin-bottom: 5px; }
  .version a { margin-right: 15px; color: #0366d6; text-decoration: none; }
  .version a:hover { text-decoration: underline; }
</style></head><body>
<h1>BookSnap OCR Diff Reports</h1>
<p>Per-sample OCR accuracy reports for each release of <a href="https://github.com/nikhaldi/booksnap">BookSnap</a>, showing character-level diffs between expected and extracted text.</p>
<h2>Experiment Progress</h2>
<a href="progress.png"><img src="progress.png" alt="Experiment progress chart" style="max-width:100%;"></a>
HEADER

# List versions in reverse order (newest first)
for version_dir in $(ls -rd "$OUTPUT_DIR/reports/"*/ 2>/dev/null); do
  ver=$(basename "$version_dir")
  echo "<div class=\"version\"><h2>$ver</h2>" >> "$OUTPUT_DIR/index.html"
  for platform_dir in "$version_dir"*/; do
    plat=$(basename "$platform_dir")
    if [ -f "$platform_dir/report.html" ]; then
      echo "  <a href=\"reports/$ver/$plat/report.html\">$plat</a>" >> "$OUTPUT_DIR/index.html"
    fi
  done
  echo "</div>" >> "$OUTPUT_DIR/index.html"
done

echo "</body></html>" >> "$OUTPUT_DIR/index.html"

echo "Done! Reports generated at $OUTPUT_DIR"
echo "Open $OUTPUT_DIR/index.html in a browser to preview."
