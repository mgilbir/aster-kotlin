#!/usr/bin/env bash
# Full local verification: formatting, the committed ABI dumps, every JVM test, the core compiled for
# every target it claims, Android lint and the demo APK. This is the same set the CI workflow runs.
#
# `checkKotlinAbi` compares the `api/*.api` and `api/*.klib.api` dumps against what the modules now
# expose, so a change to the public surface — a removed function, a widened signature, a new klib
# symbol — fails here rather than in a consumer's build. `./gradlew updateKotlinAbi` rewrites them, and
# that diff is reviewed as a change to what other people compile against.
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

# `--continue` because the inventory is the point: without it the first failing module aborts the
# build, and a run that stopped after vega-dataflow looks much like one that passed the other eight.
# A failing task still fails the build.
./gradlew --continue \
  spotlessCheck \
  checkKotlinAbi \
  test \
  compileKotlinLinuxX64 \
  "${host_tasks[@]}" \
  lint \
  :demo:assembleDebug
