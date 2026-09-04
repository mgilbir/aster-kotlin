#!/usr/bin/env bash
# The Swift renderer, built and tested against a freshly linked framework.
#
# The Swift package has no way to build its own dependency: it links `AsterVega.framework`, which
# Kotlin/Native produces, and SwiftPM knows nothing about Gradle. So the link comes first here. Run
# `swift test` by hand in a clean checkout and the compile fails with a missing-module error that says
# nothing about why — this script is the answer to that, and the reason `Package.swift` points at a
# build directory rather than a checked-in binary.
#
# The tests need no simulator and no pixels. They compile real specifications through the engine and
# assert the sequence of draw calls the walk produces, which is what a renderer can get wrong.
#
# Requires the Kotlin/Native toolchain and Xcode command-line tools; macOS only, which is also true of
# anything that could run these tests.
set -euo pipefail
cd "$(dirname "$0")/.."
# Captured before the `cd` into the package below, so the demo check at the end can be found again.
ROOT="$PWD"

if ! command -v swift >/dev/null 2>&1; then
  echo "swift not found — this needs Xcode command-line tools, and only runs on macOS." >&2
  exit 1
fi

# Debug rather than release: these are tests, and a debug framework links in a fraction of the time.
# The Swift package's search path names this exact directory.
./gradlew :vega-runtime:linkDebugFrameworkMacosArm64

# What the boundary exports, checked before anything is compiled against it.
#
# The other half of the same lesson: `swift test` catches a break in the API *this repository* uses, and
# says nothing about the rest of the surface a host outside it depends on. The snapshot covers all of it.
./scripts/foreign-api.sh

# And that the snapshot mechanism itself settles in one accept, which it did not: the coverage list
# is computed *from* the API snapshot, and they were written in the other order, so accepting a
# member that had just started crossing left this gate red until somebody ran accept again. Cheap
# to hold onto, and it was mistaken for a stale artefact both times it happened.
./scripts/foreign-api.sh --selftest

# And the other Apple surface, which that one does not cover: `foreign-api.txt` is what Kotlin exports
# to Obj-C, while `VegaChartView.init` and `ChartSession` are Swift source and appear in it nowhere.
# `CallShapeTests` pins the shapes somebody thought to write down; this pins the rest.
./scripts/swift-api.sh

cd swift/AsterVegaRender

# Discard SwiftPM's build when the framework has changed underneath it.
#
# This is not housekeeping. SwiftPM tracks its own sources but not a framework handed to it through
# `-F`, so a rebuilt `AsterVega.framework` leaves the previously compiled test module in place and
# `swift test` reports success against the *old* headers. That is not hypothetical: adding a parameter
# to `TextRun` broke every Swift caller of it, this gate reported 62 passing, and the break was found
# only after the change had been merged.
#
# The stamp is the framework header's fingerprint. Same header, incremental build; changed header, a
# clean one — which costs a few seconds and is the difference between a gate and a formality.
HEADER="../../vega-runtime/build/bin/macosArm64/debugFramework/AsterVega.framework/Headers/AsterVega.h"
STAMP=".build/aster-framework.stamp"
if [ -f "$HEADER" ]; then
  FINGERPRINT="$(shasum -a 256 "$HEADER" | cut -d' ' -f1)"
  if [ ! -f "$STAMP" ] || [ "$(cat "$STAMP")" != "$FINGERPRINT" ]; then
    echo "==> The exported framework changed; rebuilding the Swift package from scratch"
    rm -rf .build
    mkdir -p .build
    printf '%s' "$FINGERPRINT" > "$STAMP"
  fi
fi

# **`--parallel`, and it is not for speed.** `--xunit-output` writes the *swift-testing* results on
# its own, and this suite is XCTest — so without `--parallel` the file it produces holds zero cases
# and the Apple half of `SUPPORTED_FEATURES.md` would generate as unproven for ever. With it, the
# XCTest cases land in the named file and `scripts/capabilities.py` can merge them with Gradle's and
# the emulator's. `SWIFT_XUNIT_OUTPUT` is where CI asks for them; a local run does not need them and
# does not pay for the file.
if [ -n "${SWIFT_XUNIT_OUTPUT:-}" ]; then
  swift test --parallel --xunit-output "$SWIFT_XUNIT_OUTPUT" "$@"
else
  swift test "$@"
fi

# The demo app, type-checked for both iOS slices.
#
# A separate SwiftPM package, so `swift test` above says nothing about it — and that gap cost a red
# `main`. Three closure parameters were added to `VegaChartView.init`, all defaulted and all additive
# to `foreign-api.txt`, and they moved where Swift's forward scan lands a **trailing** closure: the
# `VegaChartView(scene:session:) { placement in … }` in both demo screens rebound to the new image
# resolver. Nothing here noticed, because nothing here used that idiom. `CallShapeTests` pins the
# idiom now; this pins the whole package, which has other code that can break the same way.
#
# `--check` needs Xcode and no simulator runtime, so it runs wherever `swift test` does. It is the same
# command CI runs as its own step; running it here means "the Swift gate" means all of it.
"$ROOT/scripts/ios-demo.sh" --check
