#!/bin/bash
# Stop the app on the phone, install the APK built by build.sh and start it again.
# Runs on the host: the dev container cannot reach the phone on the local network.
# Needs adb connected to the phone (README "Installing over Wi-Fi").
# Usage: ./install.sh              -> the only connected device
#        ./install.sh <IP:port>    -> that device
set -e
cd "$(dirname "$0")"

APK=app/build/outputs/apk/release/app-release.apk
PACKAGE=dev.claudecodevoice

[ -f "$APK" ] || { echo "No APK at $APK, run ./build.sh first." >&2; exit 1; }
if [ -n "$1" ]; then
    export ANDROID_SERIAL="$1"
    adb connect "$1" >/dev/null
fi

adb shell am force-stop "$PACKAGE"
adb install -r "$APK"
adb shell am start -n "$PACKAGE/.MainActivity"
