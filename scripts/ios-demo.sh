#!/usr/bin/env bash
# Builds the iOS demo app and, if a simulator is available, installs and launches it.
#
# The app links `AsterVega.xcframework`, which Kotlin/Native produces and Xcode knows nothing about, so
# Gradle goes first. The XCFramework carries both slices — device and simulator — because a single
# framework directory cannot hold both and an app that picks between two by SDK links the wrong one
# eventually.
#
#   scripts/ios-demo.sh                 build for the simulator, then install and launch
#   scripts/ios-demo.sh --device        build, install and launch on a connected iPhone
#   scripts/ios-demo.sh --device-build  build the real device app, unsigned — no Apple ID needed
#   scripts/ios-demo.sh --test          run the UI tests on a simulator
#   scripts/ios-demo.sh --check         compile only, no simulator needed
#
# `SIMULATOR_DEVICE_TYPE` picks the simulated hardware, so a screenshot can match a real phone:
#
#   SIMULATOR_DEVICE_TYPE="iPhone 16 Pro Max" scripts/ios-demo.sh
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
  for pair in "iphonesimulator arm64-apple-ios17.0-simulator ios-arm64-simulator" \
              "iphoneos arm64-apple-ios17.0 ios-arm64"; do
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

if [ "$MODE" = "--test" ]; then
  # The UI tests, which are the only place the accessibility wiring is checked end to end: the rules live
  # in the core and are tested there, but whether SwiftUI actually exposes them to VoiceOver can only be
  # asked of a running app. They caught two real defects on the way in — an activation that applied the fit
  # scale twice, and elements with no frame at all.
  echo "==> Building the framework Kotlin exports"
  ./gradlew :vega-runtime:assembleAsterVegaDebugXCFramework

  RUNTIMES="$(xcrun simctl list runtimes 2>/dev/null | grep -c "iOS" || true)"
  if [ "$RUNTIMES" -eq 0 ]; then
    echo "No iOS simulator runtime installed; see --check, or install with:" >&2
    echo "    xcodebuild -downloadPlatform iOS" >&2
    exit 1
  fi

  WANTED_TYPE="${SIMULATOR_DEVICE_TYPE:-iPhone 15 Pro Max}"
  DEVICE_NAME="AsterVega-${WANTED_TYPE// /-}"
  if ! xcrun simctl list devices | grep -q "$DEVICE_NAME"; then
    RUNTIME="$(xcrun simctl list runtimes | grep "iOS" | tail -1 | sed -E 's/.*(com\.apple\.CoreSimulator\.SimRuntime\.[^ ]*).*/\1/')"
    DEVICE_TYPE="$(xcrun simctl list devicetypes | grep -F "$WANTED_TYPE (" | head -1 | sed -E 's/.*\((com\.apple[^)]*)\).*/\1/')"
    xcrun simctl create "$DEVICE_NAME" "$DEVICE_TYPE" "$RUNTIME"
  fi

  echo "==> Running the UI tests on $DEVICE_NAME"
  # `set -e` is on, so the exit code is captured rather than allowed to end the script: the JUnit
  # XML below has to be written for a *failing* run too, or a red suite renders in
  # SUPPORTED_FEATURES.md as "unproven" instead of "failing" — which reads as a missing gate rather
  # than a broken one.
  # **Cleared first.** `xcodebuild` refuses to write a result bundle over an existing one — "Existing
  # file at -resultBundlePath" — so leaving the previous run's bundle in place makes the gate fail on
  # every second invocation, which is exactly how this was found.
  rm -rf build/ios-ui-tests
  mkdir -p build/ios-ui-tests

  set +e
  xcodebuild -project "$PROJECT" -scheme AsterVegaDemo \
    -destination "platform=iOS Simulator,name=$DEVICE_NAME" \
    -derivedDataPath build/ios-demo \
    -resultBundlePath build/ios-ui-tests/result.xcresult \
    test
  TEST_STATUS=$?
  set -e

  # **JUnit XML, so the feature table can count these.** `xcodebuild` writes an `.xcresult` and
  # nothing else; `scripts/capabilities.py` merges JUnit and xUnit XML across every CI job and keys
  # on the suite's simple class name. Without this the three tests run, pass, and the row citing
  # them still generates as unproven — a gate whose result reaches nothing.
  echo "==> Writing JUnit XML for the capability table"
  xcrun xcresulttool get test-results tests \
    --path build/ios-ui-tests/result.xcresult --compact > build/ios-ui-tests/tests.json
  python3 - build/ios-ui-tests/tests.json build/ios-ui-tests/ios-ui-tests.xml <<'PYEOF'
import json, sys, xml.etree.ElementTree as ET

source, target = sys.argv[1], sys.argv[2]
nodes = json.load(open(source)).get("testNodes") or []
cases = []

def walk(items, suite=None):
    for node in items or []:
        kind, name = node.get("nodeType"), node.get("name") or ""
        if kind == "Test Suite":
            suite = name
        elif kind == "Test Case":
            cases.append((suite or "UITests", name.removesuffix("()"), node.get("result")))
        walk(node.get("children"), suite)

walk(nodes)
root = ET.Element("testsuite", name="AsterVegaDemoUITests", tests=str(len(cases)))
failures = 0
for suite, name, result in cases:
    case = ET.SubElement(root, "testcase", classname=f"AsterVegaDemoUITests.{suite}", name=name)
    if result != "Passed":
        failures += 1
        ET.SubElement(case, "failure", message=str(result))
root.set("failures", str(failures))
ET.ElementTree(root).write(target, encoding="utf-8", xml_declaration=True)
print(f"    {len(cases)} case(s), {failures} failing -> {target}")
PYEOF
  exit $TEST_STATUS
fi

if [ "$MODE" = "--device-build" ]; then
  # Everything a device build does except signing it.
  #
  # Installing on hardware needs a signed app and there is no way round that — it is Apple's rule, not
  # this repository's. What does not need an Apple ID is *building* the thing, and that is worth having
  # on its own: it compiles and links the arm64 iOS binary, bundles the resources, and produces a real
  # `.app`. If this passes, the only thing between the app and the phone is a signature.
  echo "==> Building the device app, unsigned"
  DERIVED="build/ios-demo-device"
  xcodebuild -project "$PROJECT" -scheme AsterVegaDemo \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$DERIVED" \
    -configuration Debug \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
    build

  APP="$DERIVED/Build/Products/Debug-iphoneos/AsterVegaDemo.app"
  echo "==> Built $APP"
  echo "    architecture: $(lipo -archs "$APP/AsterVegaDemo")"
  echo "    datasets bundled: $(ls "$APP/data" 2>/dev/null | wc -l | tr -d ' ')"
  echo "    unsigned, so it cannot be installed. See --device for what that needs."
  exit 0
fi

if [ "$MODE" = "--device" ]; then
  # A device build has to be signed, and signing needs a development team — which means an Apple ID
  # signed into Xcode. A free one is enough for this: it issues a development certificate, the app
  # installs, and the provisioning lasts a week. There is nothing this script can do about that step,
  # so it says so precisely rather than failing inside xcodebuild.
  TEAM="${DEVELOPMENT_TEAM:-}"
  if [ -z "$TEAM" ]; then
    TEAM="$(defaults read com.apple.dt.Xcode IDEProvisioningTeams 2>/dev/null \
      | sed -nE 's/.*"teamID" = "([A-Z0-9]+)".*/\1/p' | head -1 || true)"
  fi
  if [ -z "$TEAM" ]; then
    cat >&2 <<'MESSAGE'
No development team is available, so the app cannot be signed for a device.

Add an Apple ID to Xcode once — Xcode > Settings > Accounts > + > Apple ID. A free account works;
it issues a development certificate and the app installs for seven days at a time.

Then run this again. To use a particular team explicitly:

    DEVELOPMENT_TEAM=ABCDE12345 scripts/ios-demo.sh --device

The simulator path needs none of this: scripts/ios-demo.sh
MESSAGE
    exit 1
  fi

  DEVICE_ID="$(xcrun devicectl list devices 2>/dev/null \
    | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1 || true)"
  if [ -z "$DEVICE_ID" ]; then
    echo "No device found. Plug the iPhone in, unlock it, and trust this Mac." >&2
    exit 1
  fi

  # Two switches on the phone itself that no amount of Mac-side configuration replaces, and whose
  # absence xcodebuild reports as a destination timeout rather than as a setting:
  #
  #   Developer Mode — Settings > Privacy & Security > Developer Mode. The phone reboots.
  #   Trust this Mac — the prompt on unlocking while plugged in.
  #
  # `devicectl` reports "connected (no DDI)" until the device is prepared, which is worth reading as
  # "not ready yet" rather than "broken".
  STATE="$(xcrun devicectl list devices 2>/dev/null | grep "$DEVICE_ID" || true)"
  case "$STATE" in
    *"no DDI"*)
      echo "note: the device is connected but not prepared for development yet." >&2
      echo "      If the build times out, enable Settings > Privacy & Security > Developer Mode." >&2
      ;;
  esac

  echo "==> Building for device $DEVICE_ID with team $TEAM"
  DERIVED="build/ios-demo-device"
  # `-allowProvisioningUpdates` lets Xcode register the device and issue the profile itself, which is
  # what makes a first run on a new phone work without a trip through the developer portal.
  xcodebuild -project "$PROJECT" -scheme AsterVegaDemo \
    -destination "id=$DEVICE_ID" \
    -derivedDataPath "$DERIVED" \
    -configuration Debug \
    -allowProvisioningUpdates \
    DEVELOPMENT_TEAM="$TEAM" \
    build

  APP="$DERIVED/Build/Products/Debug-iphoneos/AsterVegaDemo.app"
  echo "==> Installing $APP"
  xcrun devicectl device install app --device "$DEVICE_ID" "$APP"
  xcrun devicectl device process launch --device "$DEVICE_ID" dev.aster.vega.demo
  echo "==> Launched on the device."
  echo "    First run with a free account: trust the developer in"
  echo "    Settings > General > VPN & Device Management, then launch it again."
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

# Named after the hardware it simulates, so a screenshot taken here can be compared with a real phone
# of the same model — and so two models can coexist rather than one silently standing in for the other.
WANTED_TYPE="${SIMULATOR_DEVICE_TYPE:-iPhone 15 Pro Max}"
DEVICE_NAME="AsterVega-${WANTED_TYPE// /-}"
if ! xcrun simctl list devices | grep -q "$DEVICE_NAME"; then
  echo "==> Creating a simulator called $DEVICE_NAME"
  RUNTIME="$(xcrun simctl list runtimes | grep "iOS" | tail -1 | sed -E 's/.*(com\.apple\.CoreSimulator\.SimRuntime\.[^ ]*).*/\1/')"
  DEVICE_TYPE="$(xcrun simctl list devicetypes | grep -F "$WANTED_TYPE (" | head -1 | sed -E 's/.*\((com\.apple[^)]*)\).*/\1/')"
  if [ -z "$DEVICE_TYPE" ]; then
    echo "No simulator device type called '$WANTED_TYPE'. See: xcrun simctl list devicetypes" >&2
    exit 1
  fi
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
