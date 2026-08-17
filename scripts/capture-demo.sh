#!/usr/bin/env bash
# Screenshots whatever is currently on the device, for visual review of the demo.
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p build/artifacts
adb exec-out screencap -p > build/artifacts/demo.png
echo "Wrote build/artifacts/demo.png"
