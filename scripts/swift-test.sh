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

if ! command -v swift >/dev/null 2>&1; then
  echo "swift not found — this needs Xcode command-line tools, and only runs on macOS." >&2
  exit 1
fi

# Debug rather than release: these are tests, and a debug framework links in a fraction of the time.
# The Swift package's search path names this exact directory.
./gradlew :vega-runtime:linkDebugFrameworkMacosArm64

cd swift/AsterVegaRender
swift test "$@"
