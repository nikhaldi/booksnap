#!/usr/bin/env bash
#
# Build and run the iOS unit tests on a simulator.
#
# Copies the pipeline source from the library, then runs xcodebuild test
# against the committed .xcodeproj. If xcodegen is available locally, it
# regenerates the project first; otherwise it warns if project.yml is
# newer than the committed .xcodeproj.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HARNESS_DIR="$SCRIPT_DIR/.."

# Copy pipeline source from the library into the harness
PIPELINE_SRC="$HARNESS_DIR/../pipeline"
PIPELINE_DST="$HARNESS_DIR/BookSnapHarness/Pipeline"
mkdir -p "$PIPELINE_DST"
cp "$PIPELINE_SRC"/*.swift "$PIPELINE_DST/"

cd "$HARNESS_DIR"

# Regenerate the Xcode project if xcodegen is available, otherwise check
# that the committed .xcodeproj is up to date with project.yml.
if command -v xcodegen &>/dev/null; then
  xcodegen generate --quiet
elif [ project.yml -nt BookSnapHarness.xcodeproj/project.pbxproj ]; then
  # This check only helps during local development — git doesn't preserve
  # timestamps, so it won't trigger on a fresh clone.
  echo "Warning: project.yml is newer than BookSnapHarness.xcodeproj."
  echo "Install xcodegen and run 'xcodegen generate' to regenerate."
fi

# Pick the first available iPhone simulator
SIMULATOR=$(xcrun simctl list devices available -j \
  | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data['devices'].items():
    if 'iOS' not in runtime:
        continue
    for d in devices:
        if 'iPhone' in d['name'] and d['isAvailable']:
            print(d['name'])
            sys.exit(0)
sys.exit(1)
") || { echo "Error: No available iPhone simulator found. Install one via Xcode > Settings > Platforms."; exit 1; }

echo "Using simulator: $SIMULATOR"

xcodebuild test \
  -project BookSnapHarness.xcodeproj \
  -scheme BookSnapHarnessTests \
  -destination "platform=iOS Simulator,name=$SIMULATOR" \
  -skip-testing:BookSnapHarnessTests/OcrBenchmarkTests
