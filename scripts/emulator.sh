#!/usr/bin/env bash
# Starts the project AVD with a visible window, so the demo can be looked at rather than only asserted
# about. `scripts/test-android.sh` works against a headless emulator; this is for eyeballing.
#
# Usage: scripts/emulator.sh [--headless]
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
AVD="${AVD_NAME:-vega-native-api37}"

WINDOW=(-no-boot-anim -gpu swiftshader_indirect)
[[ "${1:-}" == "--headless" ]] && WINDOW+=(-no-window -no-audio)

if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
  echo "No AVD named '$AVD'. Create one with scripts/setup-android-sdk.sh's instructions." >&2
  exit 1
fi

# Only one emulator can hold a given AVD's lock, so replace whatever is running.
if "$ADB" devices | grep -q emulator; then
  echo "==> Stopping the running emulator"
  "$ADB" emu kill || true
  # Wait for the lock to clear rather than racing it.
  for _ in $(seq 30); do
    "$ADB" devices | grep -q emulator || break
    sleep 1
  done
fi

echo "==> Starting $AVD"
"$EMULATOR" "@$AVD" "${WINDOW[@]}" > build/emulator.log 2>&1 &

"$ADB" wait-for-device
echo "==> Waiting for boot to complete"
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
"$ADB" shell input keyevent 82 || true  # dismiss the lock screen

echo "==> Installing the demo"
./gradlew --console=plain :demo:assembleDebug
"$ADB" install -r demo/build/outputs/apk/debug/demo-debug.apk
"$ADB" shell monkey -p dev.aster.vega.demo -c android.intent.category.LAUNCHER 1

echo "Demo running on $AVD. Log: build/emulator.log"
