#!/usr/bin/env bash
# Full local verification: formatting, every JVM test, the core compiled for every target it claims,
# Android lint and the demo APK. This is the same set a CI job should run.
#
# The four native compiles are what turned "the core is portable" from a claim into a fact. They cost
# well under a minute incrementally and they earn it: a JVM-only API in common code fails *here* and
# nowhere else — a grep for `java.` never saw a `LinkedHashMap` subclassed for its access-order mode,
# and that had been in the text-layout cache since Milestone 1.
#
# Requires the Kotlin/Native toolchain (downloaded once, into ~/.konan) and, for the Apple targets,
# Xcode command-line tools. `linuxX64` cross-compiles from macOS.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew \
  spotlessCheck \
  test \
  compileKotlinMacosArm64 \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinLinuxX64 \
  lint \
  :demo:assembleDebug
