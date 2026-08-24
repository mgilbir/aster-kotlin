#!/usr/bin/env bash
# Full local verification: formatting, the committed ABI dumps, every JVM test, the core compiled for
# every target it claims, Android lint and the demo APK. This is the same set the CI workflow runs.
#
# `checkKotlinAbi` compares the `api/*.api` and `api/*.klib.api` dumps against what the modules now
# expose, so a change to the public surface — a removed function, a widened signature, a new klib
# symbol — fails here rather than in a consumer's build. `./gradlew updateKotlinAbi` rewrites them, and
# that diff is reviewed as a change to what other people compile against.
#
# `checkBytecodeLevel` reads the class files themselves, because the configuration cannot be trusted to
# describe them: 0.1.0 shipped its jars at Java 17 and its Android AAR at Java 21, since the pin named a
# Kotlin target *type* and the AGP Kotlin Multiplatform Android target is not one. A consumer who reads
# the jars and pins 17 is then fine until an Android compilation resolves the AAR. One release, one
# level, asserted against the bytes.
#
# The four native compiles are what turned "the core is portable" from a claim into a fact. They cost
# well under a minute incrementally and they earn it: a JVM-only API in common code fails *here* and
# nowhere else — a grep for `java.` never saw a `LinkedHashMap` subclassed for its access-order mode,
# and that had been in the text-layout cache since Milestone 1.
#
# `macosArm64Test` goes further and *runs* the `commonTest` suites on Kotlin/Native: the decimal
# expansion, a specification's own regular expressions, and the two LRU caches that only exist in
# their present form because of these targets. Compiling proves the code links; running is what says
# it agrees. `iosSimulatorArm64Test` would be the natural addition on a machine with the iOS platform
# installed — this one has no simulator runtime, so Xcode refuses the task outright.
#
# Requires the Kotlin/Native toolchain (downloaded once, into ~/.konan) and, for the Apple targets,
# Xcode command-line tools. `linuxX64` cross-compiles from macOS.
set -euo pipefail
cd "$(dirname "$0")/.."

# The upstream-vector replays are conditional on data this repository does not carry, so say when
# they are not running rather than letting a green tick imply they did.
if [ -z "$(ls test-fixtures/upstream-vectors/vega-*.json 2>/dev/null)" ]; then
  echo "note: no upstream vectors present, so UpstreamTimeVectorsTest and UpstreamTransformVectorsTest"
  echo "      will be skipped. Run scripts/record-upstream-vectors.sh to replay Vega's own tests."
fi

# The Apple targets cannot be built anywhere but a Mac. Naming them on Linux does not fail — Kotlin
# registers the tasks and then disables them — so the run goes green having quietly skipped the four
# compiles and the native test task that are the whole point of asking. Say which set is running.
apple_tasks=(
  compileKotlinMacosArm64
  compileKotlinIosArm64
  compileKotlinIosSimulatorArm64
  macosArm64Test
)
if [ "$(uname -s)" = "Darwin" ]; then
  host_tasks=("${apple_tasks[@]}")
else
  host_tasks=()
  echo "note: this host is $(uname -s), so the Apple targets (${apple_tasks[*]}) are not being built."
  echo "      Only a macOS run covers them; linuxX64 below is the portability check available here."
fi

# The instrumented suites are **compiled** here, though they are run on a device. They were compiled
# by nothing: `test` covers the JVM source sets and `:demo:assembleDebug` covers the demo's `main`,
# so four `androidTest` source sets could stop compiling and every gate stayed green until somebody
# attached a device. That also made a whole class of guard useless — a call-shape assertion is a
# file that must *compile*, and one living in a source set nobody compiles asserts nothing.
# `CallShapeTest` in `:vega-compose` is the first of them.
#
# `:benchmark` is release-only, hence the different variant.
instrumented_tasks=(
  :vega-compose:compileDebugAndroidTestKotlin
  :vega-android-canvas:compileDebugAndroidTestKotlin
  :demo:compileDebugAndroidTestKotlin
  :benchmark:compileReleaseAndroidTestKotlin
)

# `--continue` because the inventory is the point: without it the first failing module aborts the
# build, and a run that stopped after vega-dataflow looks much like one that passed the other eight.
# A failing task still fails the build.
./gradlew --continue \
  spotlessCheck \
  checkKotlinAbi \
  checkBytecodeLevel \
  test \
  compileKotlinLinuxX64 \
  "${instrumented_tasks[@]}" \
  "${host_tasks[@]}" \
  lint \
  :demo:assembleDebug
