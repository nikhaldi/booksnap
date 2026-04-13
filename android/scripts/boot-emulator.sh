#!/usr/bin/env bash
# Boot an Android emulator if none is currently running.
set -e

# Check if any emulator is already running
if adb devices 2>/dev/null | grep -q "emulator.*device$"; then
  SERIAL=$(adb devices | grep "emulator.*device$" | head -1 | cut -f1)
  echo "Emulator already running: $SERIAL"
  exit 0
fi

# Find the first available AVD and boot it
AVD=$(emulator -list-avds 2>/dev/null | head -1) \
  || { echo "Error: No AVDs found. Create one via Android Studio > Virtual Device Manager."; exit 1; }

if [ -z "$AVD" ]; then
  echo "Error: No AVDs found. Create one via Android Studio > Virtual Device Manager."
  exit 1
fi

echo "Booting emulator '$AVD'..."
emulator -avd "$AVD" -no-window -no-audio -no-boot-anim &

# Wait for the device to come online
echo "Waiting for emulator to boot..."
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
echo "Emulator booted."
