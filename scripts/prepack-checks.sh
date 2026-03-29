#!/usr/bin/env bash
#
# Sanity checks that run before npm publish (via the "prepack" script).
# Ensures the package doesn't accidentally include test files, dev config,
# or unexpectedly large content.

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

ERRORS=0

echo "Running pre-publish checks..."

# 1. No test files in package
TEST_FILES=$(npm pack --dry-run 2>&1 | grep -E '\.(test|spec)\.' || true)
if [ -n "$TEST_FILES" ]; then
  echo "ERROR: Test files found in package:"
  echo "$TEST_FILES"
  ERRORS=$((ERRORS + 1))
fi

# 2. No dev/config files leaking
LEAKED=$(npm pack --dry-run 2>&1 | grep -iE '(\.env|\.eslint|vitest\.config|jest\.config|\.swiftlint|\.gitignore|tsconfig)' || true)
if [ -n "$LEAKED" ]; then
  echo "ERROR: Dev/config files found in package:"
  echo "$LEAKED"
  ERRORS=$((ERRORS + 1))
fi

# 3. Package size under 100KB
SIZE_KB=$(npm pack --dry-run 2>&1 | grep 'package size' | grep -oE '[0-9.]+' | head -1)
SIZE_UNIT=$(npm pack --dry-run 2>&1 | grep 'package size' | grep -oE '(kB|MB)' | head -1)
if [ "$SIZE_UNIT" = "MB" ] || ([ "$SIZE_UNIT" = "kB" ] && [ "$(echo "$SIZE_KB > 100" | bc)" -eq 1 ]); then
  echo "ERROR: Package size too large: $SIZE_KB $SIZE_UNIT (max 100 kB)"
  ERRORS=$((ERRORS + 1))
fi

# 4. Version in package.json matches git tag (if running in CI on a release)
if [ -n "$GITHUB_REF" ] && echo "$GITHUB_REF" | grep -q '^refs/tags/'; then
  GIT_TAG=$(echo "$GITHUB_REF" | sed 's|refs/tags/||')
  PKG_VERSION=$(python3 -c "import json; print(json.load(open('package.json'))['version'])")
  if [ "$GIT_TAG" != "v$PKG_VERSION" ] && [ "$GIT_TAG" != "$PKG_VERSION" ]; then
    echo "ERROR: Git tag '$GIT_TAG' does not match package.json version '$PKG_VERSION'"
    ERRORS=$((ERRORS + 1))
  fi
fi

if [ "$ERRORS" -gt 0 ]; then
  echo "Pre-publish checks failed with $ERRORS error(s)."
  exit 1
fi

echo "All pre-publish checks passed."
