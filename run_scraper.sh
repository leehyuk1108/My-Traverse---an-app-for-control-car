#!/bin/bash
# Automation Script for Multipack Data Extraction

echo "1. Waking up device..."
adb shell input keyevent KEYCODE_WAKEUP

echo "2. Launching Multipack Connected on Virtual Display 239 (Stealth)..."
# Using 'am start' with --display ID to launch on the invisible overlay
adb shell am start --display 239 -n kr.co.gmone.multipack_connected_v2/.MainActivity > /dev/null 2>&1

echo "3. Waiting for Scraper to extract data (10s)..."
# Start logcat in background
adb logcat -c
adb logcat | grep "MULTIPACK_DATA" | grep "DETECTED" &
LOG_PID=$!

sleep 10

kill $LOG_PID > /dev/null 2>&1

echo "4. Closing App..."
adb shell am force-stop kr.co.gmone.multipack_connected_v2

echo "Done. Check logs above for extracted data."
