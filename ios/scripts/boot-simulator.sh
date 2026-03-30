#!/usr/bin/env bash
# Boot an iOS simulator if none is currently running.
set -e

# Check if any simulator is already booted
if xcrun simctl list devices booted -j 2>/dev/null \
    | python3 -c "
import json, sys
data = json.load(sys.stdin)
for devs in data.get('devices', {}).values():
    for d in devs:
        if d.get('state') == 'Booted':
            print(f'Simulator already running: {d[\"name\"]} ({d[\"udid\"]})')
            sys.exit(0)
sys.exit(1)
" 2>/dev/null; then
  exit 0
fi

# Find the first available iPhone simulator and boot it
UDID=$(xcrun simctl list devices available -j \
  | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data['devices'].items():
    if 'iOS' not in runtime: continue
    for d in devices:
        if 'iPhone' in d['name'] and d['isAvailable']:
            print(d['udid']); sys.exit(0)
sys.exit(1)
") || { echo "Error: No available iPhone simulator found. Install one via Xcode > Settings > Platforms."; exit 1; }

echo "Booting simulator $UDID..."
xcrun simctl boot "$UDID"
echo "Simulator booted."
