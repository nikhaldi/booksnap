#!/usr/bin/env bash
#
# Integration test: verifies the published npm package works in a real Expo app.
#
# Packs the library as a tarball, creates a minimal Expo app, installs the
# tarball, runs expo prebuild, and builds the Android project to verify
# everything compiles — including the config plugin and dictionary download.
#
# Requirements: Node.js, JDK 17, Android SDK

set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$(mktemp -d)"
VERSION=$(node -e "console.log(require('$REPO_ROOT/package.json').version)")

cleanup() {
  echo "Cleaning up $TEST_DIR..."
  rm -rf "$TEST_DIR"
}
trap cleanup EXIT

echo "=== Integration test for react-native-booksnap@$VERSION ==="

# 1. Pack the library
echo "Packing library..."
TARBALL=$(npm pack --pack-destination="$TEST_DIR" 2>/dev/null | tail -1)
echo "  Created $TARBALL"

# 2. Create minimal Expo app
echo "Creating test app..."
cd "$TEST_DIR"

cat > package.json << 'EOF'
{
  "name": "booksnap-integration-test",
  "version": "1.0.0",
  "main": "index.js"
}
EOF

cat > app.json << 'EOF'
{
  "expo": {
    "name": "BookSnapIntegrationTest",
    "slug": "booksnap-integration-test",
    "version": "1.0.0",
    "platforms": ["android"],
    "android": {
      "minSdkVersion": 26
    },
    "plugins": [
      ["react-native-booksnap", { "languages": ["en", "fr"] }]
    ]
  }
}
EOF

cat > index.js << 'EOF'
import { registerRootComponent } from 'expo';
import { Text } from 'react-native';
registerRootComponent(() => Text);
EOF

# 3. Install dependencies + library from tarball
echo "Installing dependencies..."
npm install expo@55 react react-native@0.83 expo-modules-core 2>&1 | tail -1
npm install "./$TARBALL" 2>&1 | tail -1

# 4. Verify config plugin loads
echo "Verifying config plugin..."
node -e "
  const plugin = require('react-native-booksnap/app.plugin.js');
  if (typeof plugin !== 'function') {
    console.error('Plugin is not a function');
    process.exit(1);
  }
  console.log('  Config plugin loaded successfully');
"

# 5. Run expo prebuild
echo "Running expo prebuild..."
npx expo prebuild --platform android --clean --no-install 2>&1 || { echo "expo prebuild failed"; exit 1; }

# 6. Verify gradle.properties has the language config and minSdkVersion
echo "Checking gradle.properties..."
if grep -q "hunspell.langs=en,fr" android/gradle.properties; then
  echo "  hunspell.langs correctly set to en,fr"
else
  echo "  ERROR: hunspell.langs not found in gradle.properties"
  cat android/gradle.properties
  exit 1
fi
if grep -q "android.minSdkVersion=26" android/gradle.properties; then
  echo "  android.minSdkVersion correctly set to 26"
else
  echo "  ERROR: android.minSdkVersion not set to 26 by config plugin"
  cat android/gradle.properties
  exit 1
fi

# 8. Build Android project
echo "Building Android project (this may take a few minutes)..."
cd android
./gradlew assembleDebug 2>&1 || { echo "Gradle build failed"; exit 1; }
cd ..

# 9. Verify dictionaries were downloaded
echo "Checking downloaded dictionaries..."
for lang in en fr; do
  for ext in dic aff; do
    FOUND=false
    for dir in android/app/build/intermediates/assets/debug/mergeDebugAssets/hunspell node_modules/react-native-booksnap/android/src/main/assets/hunspell; do
      if [ -f "$dir/$lang.$ext" ]; then
        FOUND=true
        break
      fi
    done
    if [ "$FOUND" = false ]; then
      echo "  ERROR: Missing dictionary file $lang.$ext"
      exit 1
    fi
  done
done
echo "  All dictionaries present"

echo ""
echo "=== Integration test passed ==="
