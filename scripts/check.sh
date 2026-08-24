#!/usr/bin/env bash
# **The** local gate. Everything this host can check, and an explicit account of anything it cannot.
#
# Formatting, the committed ABI dumps, every JVM test, the core compiled for every target it claims,
# Android lint, the demo APK, the Swift package, and the two differential comparisons against
# upstream.
#
# It used to be one of five scripts, and the working agreement was to remember the other four. That
# agreement has now failed three times in ways that reached `main` or a release: `ios-demo.sh` was a
# gate nobody ran locally and it broke `main`; a Vega-Lite fixture skipped two comparisons for ten
# days because an assumption is silent; and a Swift test asserting an old locale rule survived a
# change to that rule, because `swift-test.sh` is not this script. Each time the fix was to remember
# harder. Each time that failed, because the honest reading is that a gate you have to remember is
# not a gate.
#
# So this runs them. Two rules follow from that, and they are the whole design:
#
#   1. **Everything this host can run, runs by default.** `--fast` is the deliberate opt-out for the
#      slow half, and it says loudly what it dropped.
#   2. **A gate that did not run is reported by name.** The ledger at the end lists every one as RAN,
#      SKIPPED with a reason, or FAILED. A green tick that quietly covered less than it looks like is
#      the failure this repository keeps having, so it is the one thing this cannot do.
#
# Usage:
#   scripts/check.sh              everything this host can do
#   scripts/check.sh --fast       skip the differential oracles, which regenerate upstream's output
#   scripts/check.sh --list       print the gates and what each needs, without running anything
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

fast=false
list_only=false
for argument in "$@"; do
  case "$argument" in
    --fast) fast=true ;;
    --list) list_only=true ;;
    -h | --help)
      sed -n '2,40p' "$0"
      exit 0
      ;;
    *)
      echo "unknown option: $argument" >&2
      echo "usage: scripts/check.sh [--fast] [--list]" >&2
      exit 2
      ;;
  esac
done

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

# ---------------------------------------------------------------------------------------------
# The ledger.
#
# Every gate records an outcome here and the summary is printed from it, so a gate cannot be added
# to this script and left out of the report — the reporting *is* the running.
# ---------------------------------------------------------------------------------------------
gate_names=()
gate_outcomes=()
gate_notes=()

record() {
  gate_names+=("$1")
  gate_outcomes+=("$2")
  gate_notes+=("${3:-}")
}

# Runs a gate and records what happened, without aborting the run. The inventory is the point here
# for the same reason `--continue` is inside Gradle: knowing the Swift suite also fails is worth more
# than stopping at the first thing.
run_gate() {
  local name="$1"
  shift
  echo
  echo "==================== $name ===================="
  if "$@"; then
    record "$name" RAN
  else
    record "$name" FAILED
    failed=1
  fi
}

skip_gate() {
  record "$1" SKIPPED "$2"
  echo
  echo "==================== $1 ===================="
  echo "skipped: $2"
}

failed=0

if [ "$list_only" = true ]; then
  cat <<'GATES'
Gates, and what each needs:

  gradle              always. Format, ABI, bytecode level, every JVM test, native compiles,
                      lint, the demo APK, and the instrumented suites compiled.
  android-api         always. The surface of the two Android artifacts, which Kotlin's ABI
                      validation cannot dump.
  swift               macOS. The Swift suite, the exported-API snapshot, and the iOS demo's
                      type-check. Not runnable on any other host.
  instrumented        a device or emulator on adb. scripts/emulator.sh --headless starts one.
  oracle              node. Regenerates upstream Vega's own scenes and compares. Slow; --fast
                      skips it.
  vega-lite-oracle    node. The same for Vega-Lite. Slow; --fast skips it.
GATES
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# 1. The Gradle gate.
#
# `--continue` because the inventory is the point: without it the first failing module aborts the
# build, and a run that stopped after vega-dataflow looks much like one that passed the other eight.
# A failing task still fails the build.
# ---------------------------------------------------------------------------------------------
gradle_gate() {
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
}
run_gate "gradle" gradle_gate

# ---------------------------------------------------------------------------------------------
# 2. The Android artifacts' exported surface.
#
# Kotlin's ABI validation covers the nine modules it can and cannot cover an Android library, so
# these two — the artifacts an Android host actually depends on — were guarded by nothing. See
# scripts/android-api.sh.
# ---------------------------------------------------------------------------------------------
run_gate "android-api" ./scripts/android-api.sh

# ---------------------------------------------------------------------------------------------
# 3. The Swift gate: the suite, the exported-API snapshot, and the iOS demo's type-check.
#
# This is the one whose absence was found the hard way, twice. `swift-test.sh` carries the
# `ios-demo.sh --check` step for the same reason.
# ---------------------------------------------------------------------------------------------
if [ "$(uname -s)" = "Darwin" ]; then
  run_gate "swift" ./scripts/swift-test.sh
else
  skip_gate "swift" "this host is $(uname -s); the Swift package needs a Mac"
fi

# ---------------------------------------------------------------------------------------------
# 4. The instrumented suites, if something is listening.
#
# They are compiled above whatever happens. Running them needs a device, and a host with one
# attached should not have to remember a second script to use it.
# ---------------------------------------------------------------------------------------------
adb_path="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}/platform-tools/adb"
[ -x "$adb_path" ] || adb_path="$(command -v adb || true)"
if [ -n "$adb_path" ] && [ -x "$adb_path" ] && "$adb_path" devices | grep -qE '\sdevice$'; then
  instrumented_gate() {
    ./gradlew \
      :vega-android-canvas:connectedDebugAndroidTest \
      :vega-compose:connectedDebugAndroidTest \
      :demo:connectedDebugAndroidTest
  }
  run_gate "instrumented" instrumented_gate
else
  skip_gate "instrumented" "no device on adb; start one with scripts/emulator.sh --headless"
fi

# ---------------------------------------------------------------------------------------------
# 5 and 6. The differential comparisons, which are the point of the project.
#
# They regenerate upstream's own output with the pinned packages and compare against it, so they
# need node and they are not quick. `--fast` is for the edit-run loop; landing anything runs them.
# ---------------------------------------------------------------------------------------------
if [ "$fast" = true ]; then
  skip_gate "oracle" "--fast was given"
  skip_gate "vega-lite-oracle" "--fast was given"
elif ! command -v node >/dev/null 2>&1; then
  skip_gate "oracle" "node is not installed"
  skip_gate "vega-lite-oracle" "node is not installed"
else
  run_gate "oracle" ./scripts/oracle.sh
  run_gate "vega-lite-oracle" ./scripts/vega-lite-oracle.sh
fi

# ---------------------------------------------------------------------------------------------
# The summary. Printed always, and last, because it is the thing being trusted.
# ---------------------------------------------------------------------------------------------
echo
echo "==================== gates ===================="
skipped=0
for index in "${!gate_names[@]}"; do
  outcome="${gate_outcomes[$index]}"
  note="${gate_notes[$index]}"
  printf '  %-18s %s' "${gate_names[$index]}" "$outcome"
  [ -n "$note" ] && printf ' — %s' "$note"
  printf '\n'
  [ "$outcome" = "SKIPPED" ] && skipped=$((skipped + 1))
done

echo
if [ "$failed" -ne 0 ]; then
  echo "FAILED. The gates above say which."
  exit 1
fi
if [ "$skipped" -ne 0 ]; then
  echo "Green, with $skipped gate(s) not run — read the reasons above before landing anything."
else
  echo "Green, and every gate ran."
fi
