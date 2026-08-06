#!/usr/bin/env bash
# Full local verification: formatting, every JVM test, Android lint and the demo APK.
# This is the same set a CI job should run.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew \
  spotlessCheck \
  test \
  lint \
  :demo:assembleDebug
