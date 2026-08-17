#!/usr/bin/env bash
# Builds the iOS demo app and, if a simulator is available, installs and launches it.
#
# The app links `AsterVega.xcframework`, which Kotlin/Native produces and Xcode knows nothing about, so
# Gradle goes first. The XCFramework carries both slices — device and simulator — because a single
# framework directory cannot hold both and an app that picks between two by SDK links the wrong one
# eventually.
#
#   scripts/ios-demo.sh                 build for the simulator, then install and launch
#   scripts/ios-demo.sh --device        build for a connected device (needs a signing team)
#   scripts/ios-demo.sh --check         compile only, no simulator needed
#
# `--check` is the one that runs anywhere: it type-checks the app for both slices without asking Xcode
# for a destination, which is what a machine with no simulator *runtime* installed can still do.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:---simulator}"
PROJECT="swift/AsterVegaDemo/AsterVegaDemo.xcodeproj"
XCFRAMEWORK="vega-runtime/build/XCFrameworks/debug/AsterVega.xcframework"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild not found — this needs Xcode, and only runs on macOS." >&2
  exit 1
fi

echo "==> Building the framework Kotlin exports"
./gradlew :vega-runtime:assembleAsterVegaDebugXCFramework

if [ "$MODE" = "--check" ]; then
  # Type-checking both slices directly. No destination, no `.app`, no simulator runtime: this is the
  # most a machine without the iOS platform installed can verify, and it does catch real errors — the
  # first run of it found a SwiftUI overload resolving to `List`'s editable form.
  echo "==> Type-checking for the simulator and for a device"
  for pair in "iphonesimulator arm64-apple-ios16.0-simulator ios-arm64-simulator" \
              "iphoneos arm64-apple-ios16.0 ios-arm64"; do
    set -- $pair
    sdk_name="$1"; triple="$2"; slice="$3"
    sdk_path="$(xcrun --sdk "$sdk_name" --show-sdk-path)"
    echo "    $triple"
    xcrun swiftc -typecheck \
      -sdk "$sdk_path" \
      -target "$triple" \
      -swift-version 6 \
      -F "$PWD/$XCFRAMEWORK/$slice" \
      swift/AsterVegaDemo/Sources/*.swift \
      swift/AsterVegaRender/Sources/AsterVegaRender/*.swift
  done
  echo "==> Both slices type-check"
  exit 0
fi

if [ "$MODE" = "--device" ]; then
  echo "==> Building for a connected device"
  # No signing identity is assumed: pass DEVELOPMENT_TEAM=… if the build asks for one.
  xcodebuild -project "$PROJECT" -scheme AsterVegaDemo \
    -destination 'generic/platform=iOS' -configuration Debug build
  echo "==> Built. Install it from Xcode, or with `xcrun devicectl device install app`."
  exit 0
fi

# --- Simulator ---------------------------------------------------------------

RUNTIMES="$(xcrun simctl list runtimes 2>/dev/null | grep -c "iOS" || true)"
if [ "$RUNTIMES" -eq 0 ]; then
  cat >&2 <<'MESSAGE'
No iOS simulator runtime is installed, so there is nothing to run the app on. Xcode lists the SDK but
refuses every destination without the matching platform, so even `xcodebuild build` cannot proceed.

Install it once (several GB, and it is Apple's download rather than this repository's):

    xcodebuild -downloadPlatform iOS

or Xcode > Settings > Components > iOS. Then run this script again.

Meanwhile `scripts/ios-demo.sh --check` compiles the app for both slices and needs no runtime.
MESSAGE
  exit 1
fi

DEVICE_NAME="AsterVega-iPhone"
if ! xcrun simctl list devices | grep -q "$DEVICE_NAME"; then
  echo "==> Creating a simulator called $DEVICE_NAME"
  RUNTIME="$(xcrun simctl list runtimes | grep "iOS" | tail -1 | sed -E 's/.*(com\.apple\.CoreSimulator\.SimRuntime\.[^ ]*).*/\1/')"
  DEVICE_TYPE="$(xcrun simctl list devicetypes | grep -E "iPhone 1[5-9]" | tail -1 | sed -E 's/.*\((com\.apple[^)]*)\).*/\1/')"
  xcrun simctl create "$DEVICE_NAME" "$DEVICE_TYPE" "$RUNTIME"
fi

echo "==> Booting the simulator"
xcrun simctl boot "$DEVICE_NAME" 2>/dev/null || true
open -a Simulator

echo "==> Building"
DERIVED="build/ios-demo"
xcodebuild -project "$PROJECT" -scheme AsterVegaDemo \
  -destination "platform=iOS Simulator,name=$DEVICE_NAME" \
  -derivedDataPath "$DERIVED" \
  -configuration Debug build

APP="$DERIVED/Build/Products/Debug-iphonesimulator/AsterVegaDemo.app"
echo "==> Installing $APP"
xcrun simctl install "$DEVICE_NAME" "$APP"
xcrun simctl launch "$DEVICE_NAME" dev.aster.vega.demo
echo "==> Launched. Every chart in the list is compiled by this engine and drawn by the Swift renderer."
