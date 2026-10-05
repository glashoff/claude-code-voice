#!/bin/bash
# Installs the pinned Android SDK components into the voice-android-sdk volume.
# Runs on every container creation; does nothing once everything is there.
# Keep the versions in line with build-env/Containerfile and app/build.gradle.kts.
set -e

CMDLINE_TOOLS=commandlinetools-linux-11076708_latest.zip
CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
PACKAGES=(
    "platform-tools"
    "platforms;android-35"
    "build-tools;35.0.0"
    "ndk;27.2.12479018"
    "cmake;3.22.1"
)

: "${ANDROID_HOME:?}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
    tmp=$(mktemp -d)
    curl -fsSL "https://dl.google.com/android/repository/$CMDLINE_TOOLS" -o "$tmp/tools.zip"
    echo "$CMDLINE_TOOLS_SHA256  $tmp/tools.zip" | sha256sum -c - >/dev/null
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
fi

missing=()
for p in "${PACKAGES[@]}"; do
    [ -f "$ANDROID_HOME/${p//;//}/package.xml" ] || missing+=("$p")
done
if [ ${#missing[@]} -gt 0 ]; then
    echo "Installing Android SDK components: ${missing[*]}"
    yes | "$SDKMANAGER" --licenses >/dev/null
    "$SDKMANAGER" --install "${missing[@]}"
    rm -rf "$ANDROID_HOME/.temp"
fi
