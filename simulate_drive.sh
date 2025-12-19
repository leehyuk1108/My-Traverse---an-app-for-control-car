#!/bin/bash

# Emulator authentication required for telnet, so let's use adb shell emu which is easier
# Route: Gangnam Station -> Yeoksam Station -> Seolleung Station -> COEX (Teheran-ro)

echo "Starting GPS Simulation: Gangnam -> COEX"
echo "Ensure your app is running and 'Driving Mode' is ACTIVE (Bluetooth Connected)"

# 1. Gangnam Station
echo "Moving to Gangnam Station..."
adb emu geo fix 127.0276 37.4979
sleep 2

# 2. Moving East on Teheran-ro
echo "Driving..."
adb emu geo fix 127.0290 37.4985
sleep 2
adb emu geo fix 127.0310 37.4992
sleep 2
adb emu geo fix 127.0330 37.5000
sleep 2

# 3. Yeoksam Station
echo "Passing Yeoksam Station..."
adb emu geo fix 127.0365 37.5007
sleep 2

# 4. Moving East
adb emu geo fix 127.0390 37.5015
sleep 2
adb emu geo fix 127.0410 37.5022
sleep 2
adb emu geo fix 127.0425 37.5030
sleep 2

# 5. Seolleung Station
echo "Passing Seolleung Station..."
adb emu geo fix 127.0430 37.5045
sleep 2

# 6. Moving East towards COEX
adb emu geo fix 127.0450 37.5055
sleep 2
adb emu geo fix 127.0480 37.5070
sleep 2
adb emu geo fix 127.0510 37.5085
sleep 2

# 7. Samsung Station (COEX)
echo "Arriving at COEX (Samsung Station)..."
adb emu geo fix 127.0590 37.5100
sleep 2

echo "Simulation Complete! Stop driving in the app now."
