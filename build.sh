#!/bin/bash
# Build the APK inside the pinned Android build container (see build-env/Containerfile).
# The debug signing key lives in the voice-android-keys volume so app updates keep the same signature.
# Usage: ./build.sh            -> app/build/outputs/apk/release/app-release.apk
#        ./build.sh <gradle args>
set -e
cd "$(dirname "$0")"

# sherpa-onnx runtime for the in-app Piper voice: too large for the repository, fetched once and checked.
AAR=app/libs/sherpa-onnx-1.13.8.aar
AAR_SHA256=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
if [ ! -f "$AAR" ]; then
    mkdir -p app/libs
    curl -fL -o "$AAR.part" https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
    echo "$AAR_SHA256  $AAR.part" | sha256sum -c - >/dev/null
    mv "$AAR.part" "$AAR"
fi

IMAGE=voice-android-build
if ! podman image exists "$IMAGE"; then
    podman build -t "$IMAGE" -f build-env/Containerfile build-env
fi

podman run --rm \
    -v "$PWD":/work \
    -v voice-android-gradle:/root/.gradle \
    -v voice-android-keys:/root/.android \
    -e GRADLE_OPTS="-Dorg.gradle.daemon=false" \
    "$IMAGE" ./gradlew --no-daemon "${@:-assembleRelease}"
