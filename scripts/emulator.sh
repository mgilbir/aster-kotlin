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
# **`-W`**, so this returns when the launch has *completed* rather than when the intent has been
# dispatched. Without it the wait below is the only thing standing between `am start` and a verdict,
# and on a cold first launch after a fresh boot that verdict was wrong: the demo started, the poll
# gave up first, and the script printed "did not start" about an app that was on screen. `-W` also
# prints `Status`, `LaunchState` and `TotalTime`, which say *how* slow a slow start was.
"$ADB" shell am start -W -n dev.aster.vega.demo/.DemoActivity

# Said only if it is true, which is the actual fix: a launch that fails silently is what went wrong.
#
# **The resumed activity, not the process.** `pidof` answers for a process that exists, which is a
# weaker claim than the message makes — an app can be alive and not showing anything. `dumpsys`
# names what is actually in front, so "Demo running" means the demo is what you are looking at.
#
# **Polled, and for long enough.** `am start -W` should make the first read succeed; the loop is
# what covers the launch that is merely slow rather than failed. It was twenty seconds and that was
# not enough for a cold start on a fresh boot — the failure this pair of comments now exists for,
# found by running the script rather than by reasoning about it. Ninety, because the cost of waiting
# is a slower message and the cost of not waiting is a false report.
resumed() {
  "$ADB" shell dumpsys activity activities 2>/dev/null |
    grep -q "topResumedActivity.*dev\.aster\.vega\.demo/\.DemoActivity"
}
for _ in $(seq 1 90); do
  resumed && break
  sleep 1
done
if resumed; then
  echo "Demo running on $AVD. Log: build/emulator.log"
elif "$ADB" shell pidof dev.aster.vega.demo > /dev/null 2>&1; then
  # Alive but not in front: a real state, and a different one from not starting at all. Saying which
  # is the difference between looking at the app and looking at the launcher.
  echo "The demo is running on $AVD but is not the activity in front. Log: build/emulator.log" >&2
  exit 1
else
  echo "The demo was installed on $AVD but did not start. Log: build/emulator.log" >&2
  exit 1
fi
