#!/usr/bin/env bash
#
# Generate and deploy diff reports to the gh-pages branch.
#
# Usage:
#   bash scripts/deploy-reports.sh
#
# This script clones the gh-pages branch, runs generate-reports.sh into it,
# then commits and pushes.

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=$(python3 -c "import json; print(json.load(open('$REPO_ROOT/package.json'))['version'])")
DEPLOY_DIR="$(mktemp -d)"

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

# Generate reports into the deploy directory
bash "$REPO_ROOT/scripts/generate-reports.sh" "$DEPLOY_DIR"

# Commit and push
cd "$DEPLOY_DIR"
git add -A
git commit -m "Update reports for $VERSION"
git push origin gh-pages

echo "Done! Reports deployed to gh-pages."
rm -rf "$DEPLOY_DIR"
