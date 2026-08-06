#!/usr/bin/env bash
# Installs the pinned Android SDK packages this project needs, without Android Studio.
# Safe to re-run: already-installed packages are skipped.
#
# Verified on macOS 15 (Apple silicon) with Homebrew OpenJDK 21 as the Gradle JVM.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# Pinned command-line tools build. Bump this deliberately, not automatically.
CMDLINE_TOOLS_ZIP="commandlinetools-mac_arm64-15859902_latest.zip"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "==> Installing Android command-line tools into $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo "==> Accepting SDK licenses"
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

# API 37 ships as minor-versioned platforms (android-37.0, android-37.1). AGP resolves
# `compileSdk = 37` against android-37.0, so install both: 37.0 for the build and 37.1 for
# on-device testing against the newest platform.
echo "==> Installing platform-tools, platforms;android-37.0, platforms;android-37.1, build-tools;37.0.0"
"$SDKMANAGER" \
  "platform-tools" \
  "platforms;android-37.0" \
  "platforms;android-37.1" \
  "build-tools;37.0.0"

cat <<MSG

Base SDK installed at $ANDROID_HOME

For emulator tests also run:
  "$SDKMANAGER" "emulator" "system-images;android-37.1;google_apis;arm64-v8a"
  avdmanager create avd --name vega-native-api37 \\
    --package "system-images;android-37.1;google_apis;arm64-v8a" --device pixel_8
  emulator @vega-native-api37
MSG
