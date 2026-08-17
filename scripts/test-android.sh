#!/usr/bin/env bash
# Instrumented tests. Requires a running emulator or a connected device.
set -euo pipefail
cd "$(dirname "$0")/.."

adb wait-for-device

./gradlew \
  :vega-android-canvas:connectedDebugAndroidTest \
  :vega-compose:connectedDebugAndroidTest \
  :demo:connectedDebugAndroidTest
