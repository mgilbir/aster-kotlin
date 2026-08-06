#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :demo:assembleDebug
adb install -r demo/build/outputs/apk/debug/demo-debug.apk
