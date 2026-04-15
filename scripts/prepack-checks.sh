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

# 1. package.json passes npm pkg fix
npm pkg fix 2>/dev/null
if ! git diff --quiet package.json; then
  echo "ERROR: package.json needs fixes. Run 'npm pkg fix' and commit the result."
  git diff package.json
  git checkout package.json
  ERRORS=$((ERRORS + 1))
fi

# 2. No test files in package
TEST_FILES=$(npm pack --dry-run 2>&1 | grep 'npm notice' | grep -E '\.(test|spec)\.' || true)
if [ -n "$TEST_FILES" ]; then
  echo "ERROR: Test files found in package:"
  echo "$TEST_FILES"
  ERRORS=$((ERRORS + 1))
fi

# 3. No dev/config files leaking
LEAKED=$(npm pack --dry-run 2>&1 | grep 'npm notice' | grep -iE '(\.env|\.eslint|vitest\.config|jest\.config|\.swiftlint|\.gitignore|tsconfig)' || true)
if [ -n "$LEAKED" ]; then
  echo "ERROR: Dev/config files found in package:"
  echo "$LEAKED"
  ERRORS=$((ERRORS + 1))
fi

# 4. Package size under 100KB
SIZE_KB=$(npm pack --dry-run 2>&1 | grep 'package size' | grep -oE '[0-9.]+' | head -1)
SIZE_UNIT=$(npm pack --dry-run 2>&1 | grep 'package size' | grep -oE '(kB|MB)' | head -1)
if [ "$SIZE_UNIT" = "MB" ] || ([ "$SIZE_UNIT" = "kB" ] && [ "$(echo "$SIZE_KB > 100" | bc)" -eq 1 ]); then
  echo "ERROR: Package size too large: $SIZE_KB $SIZE_UNIT (max 100 kB)"
  ERRORS=$((ERRORS + 1))
fi

# 5. Expo config plugin loads without errors
PLUGIN_PATH=$(node -e "const c=require('./expo-module.config.json'); console.log(c.configPlugin || '')" 2>/dev/null)
if [ -n "$PLUGIN_PATH" ]; then
  if ! node -e "require('$PLUGIN_PATH')" 2>/dev/null; then
    echo "ERROR: Expo config plugin at $PLUGIN_PATH failed to load."
    ERRORS=$((ERRORS + 1))
  fi
fi
if [ -f "app.plugin.js" ]; then
  if ! node -e "require('./app.plugin.js')" 2>/dev/null; then
    echo "ERROR: app.plugin.js failed to load."
    ERRORS=$((ERRORS + 1))
  fi
fi

# 6. Files referenced by build.gradle are included in the package
PACK_FILES=$(npm pack --dry-run 2>&1)
for ref in $(grep -oE "apply from:.*\"([^\"]+)\"" android/build.gradle | grep -oE '"[^"]+"' | tr -d '"'); do
  if ! echo "$PACK_FILES" | grep -q "android/$ref"; then
    echo "ERROR: android/build.gradle references '$ref' but it's not in the published package."
    ERRORS=$((ERRORS + 1))
  fi
done

# 7. Version in package.json matches git tag (if running in CI on a release)
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
