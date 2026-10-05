# Shared sandbox plus a JDK for the Android build. The Android SDK itself is
# several GB and lives in a volume (setup-android-sdk.sh), since this layer is
# rebuilt with every daily build of the base image.
FROM local/devcontainer-sandbox:latest
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip \
    && rm -rf /var/lib/apt/lists/* \
    # Mount points of the volumes, owned by vscode so new volumes are too.
    && mkdir -p /opt/android-sdk /home/vscode/.gradle /home/vscode/.android \
    && chown vscode:vscode /opt/android-sdk /home/vscode/.gradle /home/vscode/.android
