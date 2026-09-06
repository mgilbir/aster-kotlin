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

# **Name servers, stated.** The emulator does not reliably inherit macOS's resolver configuration, and
# an emulator that cannot resolve a name is not obviously broken — it boots, it draws, and only the
# handful of tests that fetch from the gallery fail, with a message about the private-network rule
# that reads like a policy decision rather than a missing route. `DemoActivityTest` lost two tests to
# that, and the tempting fix was to skip them: a gate that goes green having quietly stopped
# exercising the network is the failure this repository keeps having, so the emulator gets DNS
# instead.
#
# Google's and Cloudflare's, in that order, because they are reachable from anywhere this is likely to
# run. `EMULATOR_DNS` overrides for a network that blocks them.
DNS=(-dns-server "${EMULATOR_DNS:-8.8.8.8,1.1.1.1}")

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
# nohup + disown so the emulator outlives this script's shell; otherwise it dies with the terminal or
# CI step that started it, which is confusing when the window simply vanishes.
mkdir -p build
nohup "$EMULATOR" "@$AVD" "${WINDOW[@]}" "${DNS[@]}" > build/emulator.log 2>&1 &
disown

"$ADB" wait-for-device
echo "==> Waiting for boot to complete"
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
"$ADB" shell input keyevent 82 || true  # dismiss the lock screen

echo "==> Installing the demo"
./gradlew --console=plain :demo:assembleDebug
"$ADB" install -r demo/build/outputs/apk/debug/demo-debug.apk
# `am start` and not `monkey`. Monkey resolves the launcher intent itself and, on this image, prints
# its arguments back and leaves the home screen in front — so this script reported "Demo running"
# three separate times while the demo was not running, and the next thing anyone did was debug an app
# that had never started. Naming the activity is both more direct and checkable.
"$ADB" shell am start -n dev.aster.vega.demo/.DemoActivity

# Said only if it is true, which is the actual fix: a launch that fails silently is what went wrong.
#
# **Polled, not asked once.** `am start` returns when the intent is dispatched rather than when the
# process is up, so a single `pidof` straight after it reports failure for an app that is starting
# perfectly well — which is what the first version of this check did, and what testing caught.
for _ in $(seq 1 20); do
  "$ADB" shell pidof dev.aster.vega.demo > /dev/null 2>&1 && break
  sleep 1
done
if "$ADB" shell pidof dev.aster.vega.demo > /dev/null 2>&1; then
  echo "Demo running on $AVD. Log: build/emulator.log"
else
  echo "The demo was installed on $AVD but did not start. Log: build/emulator.log" >&2
  exit 1
fi
