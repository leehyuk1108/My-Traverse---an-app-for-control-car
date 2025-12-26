#!/bin/bash
# start_emulator.sh

EMULATOR_PATH=~/Library/Android/sdk/emulator/emulator
AVD_NAME="Pixel_9a"

echo "Starting emulator: $AVD_NAME..."
$EMULATOR_PATH -avd $AVD_NAME -netdelay none -netspeed full &
EMULATOR_PID=$!
echo "Emulator started with PID: $EMULATOR_PID"

echo "Waiting for emulator to boot..."
adb wait-for-device

echo "Emulator is online."
