#!/usr/bin/env bash
# Microbenchmarks. Emulator numbers are not authoritative (PROJECT_BRIEF.md 18.6): release
# thresholds must be validated on physical hardware.
set -euo pipefail
cd "$(dirname "$0")/.."

adb wait-for-device

if adb shell getprop ro.kernel.qemu | grep -q 1; then
  echo "WARNING: running on an emulator. Results are indicative only; do not record them as" >&2
  echo "         performance results in STATUS.md." >&2
fi

./gradlew :benchmark:connectedReleaseAndroidTest
