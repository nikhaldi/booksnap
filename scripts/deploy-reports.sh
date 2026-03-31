#!/usr/bin/env bash
#
# Generate and deploy diff reports to the gh-pages branch.
#
# Usage:
#   bash scripts/deploy-reports.sh
#
# Reads the version from package.json. For each platform (android, ios)
# that has a lab directory, this script:
#   1. Generates a fresh diff report via `uv run diff-report`
#   2. Copies the report + images into a versioned directory on gh-pages
#   3. Generates an index.html listing all versions
#   4. Commits and pushes to the gh-pages branch

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=$(python3 -c "import json; print(json.load(open('$REPO_ROOT/package.json'))['version'])")
DEPLOY_DIR="$(mktemp -d)"
DATA_DIR="$REPO_ROOT/datasets/booksnap-base"

echo "Deploying reports for version $VERSION..."

# Clone the gh-pages branch (or create it if it doesn't exist)
if git -C "$REPO_ROOT" ls-remote --exit-code --heads origin gh-pages &>/dev/null; then
  git clone --branch gh-pages --single-branch --depth 1 \
    "$(git -C "$REPO_ROOT" remote get-url origin)" "$DEPLOY_DIR"
else
  mkdir -p "$DEPLOY_DIR"
  git -C "$DEPLOY_DIR" init
  git -C "$DEPLOY_DIR" checkout -b gh-pages
  git -C "$DEPLOY_DIR" remote add origin \
    "$(git -C "$REPO_ROOT" remote get-url origin)"
fi

# Generate and copy reports for each platform
for platform in android ios; do
  LAB_DIR="$REPO_ROOT/$platform/lab"
  if [ ! -f "$LAB_DIR/lab.toml" ]; then
    echo "No $platform lab found, skipping."
    continue
  fi

  DEST="$DEPLOY_DIR/reports/$VERSION/$platform"
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
done

# Generate index.html
cat > "$DEPLOY_DIR/index.html" << 'HEADER'
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
HEADER

# List versions in reverse order (newest first)
for version_dir in $(ls -rd "$DEPLOY_DIR/reports/"*/ 2>/dev/null); do
  ver=$(basename "$version_dir")
  echo "<div class=\"version\"><h2>$ver</h2>" >> "$DEPLOY_DIR/index.html"
  for platform_dir in "$version_dir"*/; do
    plat=$(basename "$platform_dir")
    if [ -f "$platform_dir/report.html" ]; then
      echo "  <a href=\"reports/$ver/$plat/report.html\">$plat</a>" >> "$DEPLOY_DIR/index.html"
    fi
  done
  echo "</div>" >> "$DEPLOY_DIR/index.html"
done

echo "</body></html>" >> "$DEPLOY_DIR/index.html"

# Commit and push
cd "$DEPLOY_DIR"
git add -A
git commit -m "Update reports for $VERSION"
git push origin gh-pages

echo "Done! Reports deployed to gh-pages."
rm -rf "$DEPLOY_DIR"
