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
# it agrees.
#
# `iosSimulatorArm64Test` is here for the same reason and used to be only in CI, on the belief that
# this machine had no simulator runtime. It has one, and the whole run costs about thirty seconds.
# Leaving it to CI cost a red run: a zone pin that reached the JVM and a macOS host process reached
# neither the simulator nor Linux, and the local gate was green because it never asked the simulator
# anything. A Mac without an iOS runtime installed will see Xcode refuse this task — install one
# through Xcode's Platforms pane rather than dropping the task, because a target this repository
# ships to is a target something must execute.
#
# `linuxX64Test` is the same argument for the target a Mac cannot run: it was **compiled and never
# executed**, so `linuxX64` shipped with its `commonTest` suites having run on no machine at all.
# That gap is the one this repository keeps finding in another costume — a target a gate names and
# does not exercise — and it matters most for `DeepInputTest`, because a stack overflow on
# Kotlin/Native is a `SIGSEGV` that kills the process rather than a catchable `StackOverflowError`.
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
  iosSimulatorArm64Test
)
if [ "$(uname -s)" = "Darwin" ]; then
  host_tasks=("${apple_tasks[@]}")
else
  # **Named only on Linux**, because a Linux binary is what a Mac cannot run: Kotlin registers
  # `linuxX64Test` everywhere and disables it off Linux, and a disabled task is the silent skip the
  # block above exists to avoid. So it goes in this branch rather than in the unconditional list.
  host_tasks=(linuxX64Test)
  echo "note: this host is $(uname -s), so the Apple targets (${apple_tasks[*]}) are not being built."
  echo "      Only a macOS run covers them; linuxX64Test below runs commonTest on the native target"
  echo "      this host can execute."
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

  gradle              always. Format, ABI, bytecode level, every JVM test, the native compiles,
                      the commonTest suites *run* on the host's native targets (macOS and the
                      iOS simulator on a Mac, linuxX64 on Linux), lint, the demo APK, and the
                      instrumented suites compiled.
  android-api         always. The surface of the two Android artifacts, which Kotlin's ABI
                      validation cannot dump.
  host-parity         always. Every seam checked against every host's recorded surface, so the
                      README's matrix is derived rather than asserted.
  host-conformance    always. Every conformance golden is read by every engine, so a seam wired
                      to one host cannot pass as agreement.
  changelog           always. A branch that moves an API snapshot has to say so in CHANGELOG.md,
                      or mark the commit [api-snapshot-only].
  capabilities        always. SUPPORTED_FEATURES.md still renders from docs/capabilities.json, and
                      every registered expression function is named in it. The status column is
                      generated from merged test results in CI, which is the only place that has
                      every host's.
  swift               macOS. The Swift suite, the exported-API snapshot, and the iOS demo's
                      type-check. Not runnable on any other host.
  ios-ui              macOS with an iOS simulator runtime. The demo's UI tests, which are the only
                      place VoiceOver is checked end to end: labelled elements, described axes, and
                      activation selecting a mark.
  instrumented        a device or emulator on adb. scripts/emulator.sh --headless starts one.
  oracle              node. Regenerates upstream Vega's own scenes and compares. Slow; --fast
                      skips it.
  vega-lite-oracle    node. The same for Vega-Lite. Slow; --fast skips it.
  vega-lite-references
                      node. Renders upstream's Vega-Lite scenes **before** the Gradle gate, which
                      is what arms VegaLiteFixtureDifferentialTest's 1126 cases inside it. Without
                      it that gate skips and this summary still says every gate ran.
  gallery             node. Fetches the 627 examples Vega-Lite ships at the pinned version and
                      compiles them upstream, **before** the Gradle gate, which is what arms
                      VegaLiteGalleryTest inside it. Compiling needs no data, so this is seconds
                      rather than minutes, and --fast does *not* skip it.
GATES
  exit 0
fi

# ---------------------------------------------------------------------------------------------
# 0. Upstream's Vega-Lite scenes, which **arm** a gate that runs inside step 1.
#
# `VegaLiteFixtureDifferentialTest` — 1126 cases, and the gate that catches a specification which
# matches upstream property for property and still draws the wrong chart — answers a missing
# reference with an assumption. The references are gitignored by design, being sixteen megabytes of
# derived output nobody reads a diff of. So on a fresh clone the whole gate skipped, silently, and
# the summary at the bottom of this script said "Green, and every gate ran".
#
# It used to be rendered at step 9, *after* the tests that need it, which armed the gate for the
# **next** run and never for the one printing the summary. So a single run could not arm it at all,
# and a release could not either — `release.yml`'s verify job never rendered them.
#
# Skipped with `--fast`, which is the edit-run loop, and skipped without node. Both are reported.
# ---------------------------------------------------------------------------------------------
if [ "$fast" = true ]; then
  skip_gate "vega-lite-references" "--fast was given; the Vega-Lite scene comparison is unarmed"
elif ! command -v node >/dev/null 2>&1; then
  skip_gate "vega-lite-references" "node is not installed; the Vega-Lite scene comparison is unarmed"
else
  run_gate "vega-lite-references" ./scripts/vega-lite-oracle.sh --references-only
fi

# ---------------------------------------------------------------------------------------------
# 0b. The gallery, which **arms** the other gate that runs inside step 1.
#
# Vega-Lite ships 627 examples, and a sweep of all of them against upstream's compiler took the
# corpus from 124 matching to every one. That sweep was a *measurement* and not a gate for a long
# time: the examples are not checked in, nothing referenced them, and no CI job ran them — so the
# largest surface this project had ever verified was protected by nothing, and a regression in any
# of the 503 that were fixed would have gone unnoticed until somebody swept again by hand.
#
# Beside `vega-lite-references` rather than at the end for the same reason that one moved:
# `VegaLiteGalleryTest` runs inside the Gradle gate, and a reference written after it arms the
# **next** run and never the one printing the summary.
#
# **Not skipped by `--fast`**, unlike the two oracles either side of it, and the exception is the
# point. Compiling is a pure function of the specification, so no data is fetched and the whole
# sweep is a few seconds against their several minutes — `--fast` exists for the slow ones. It
# would also be the wrong skip to make: CI runs `check.sh --fast`, so skipping here would leave the
# gate unarmed in the one place it matters most, and `VegaLiteGalleryTest` **fails** rather than
# assuming itself away, so it would go red rather than quiet.
#
# Which is what happens without node, and is stated rather than worked around: the gate is reported
# skipped and the Gradle gate then fails, naming the script to run. Node is not optional for this
# repository's verification — every differential corpus in it is generated by upstream's own code.
# ---------------------------------------------------------------------------------------------
if ! command -v node >/dev/null 2>&1; then
  skip_gate "gallery" "node is not installed; the Vega-Lite gallery sweep is unarmed"
else
  run_gate "gallery" ./scripts/vega-lite-gallery.sh --references-only
fi

# ---------------------------------------------------------------------------------------------
# 1. The Gradle gate.
#
# `--continue` because the inventory is the point: without it the first failing module aborts the
# build, and a run that stopped after vega-dataflow looks much like one that passed the other eight.
# A failing task still fails the build.
#
# `compileCommonMainKotlinMetadata` is here because **compiling every target is not the same as
# compiling the common source set**. A `commonMain` file that names something only the JVM and
# Native standard libraries have — `OutOfMemoryError` is the one that got through — compiles for
# every target and fails the metadata compilation, which is what a consumer of the multiplatform
# artifact actually resolves against. Two commits shipped exactly that defect while this gate said
# green.
# ---------------------------------------------------------------------------------------------
gradle_gate() {
  ./gradlew --continue \
    spotlessCheck \
    checkKotlinAbi \
    checkBytecodeLevel \
    test \
    compileKotlinLinuxX64 \
    compileCommonMainKotlinMetadata \
    "${instrumented_tasks[@]}" \
    "${host_tasks[@]}" \
    lint \
    :demo:assembleDebug
}
run_gate "gradle" gradle_gate

# **How many tests actually ran**, which is the gate CI had and this did not.
#
# `check.sh` is what somebody runs before landing, so a rule only the workflow knows is a rule that
# lands broken — and it did: a nested-scale comparison that skipped 195 of 198 fixtures passed all
# twelve gates here and failed CI's count step twice before the workflow was read. Same script, same
# thresholds, so the two cannot drift.
counts_gate() {
  python3 scripts/test-counts.py "$(uname -s | sed 's/Darwin/macOS/')" .
}
run_gate "test-counts" counts_gate

# **The publication**, which is the other pair of checks CI had and this did not.
#
# Same reasoning as `test-counts` and found the same way: `check.sh` is the pre-landing gate, so a
# rule only the workflow knows is a rule that lands broken. `verifyPublishedVariants` catches a
# declared target with no publication behind it and `verifyCentralBundle` a publication missing from
# the bundle — both far cheaper on an ordinary run than on the one run that uploads it.
#
# macOS only, and deliberately: Kotlin creates no publication for a target the host cannot compile,
# so on Linux these are *expected* to fail and say nothing useful.
publication_gate() {
  ./gradlew verifyPublishedVariants
  ./gradlew centralBundle verifyCentralBundle --no-configuration-cache
}
if [ "$(uname -s)" = "Darwin" ]; then
  run_gate "publication" publication_gate
else
  skip_gate "publication" "this host is $(uname -s); Kotlin publishes no macOS or iOS variant here"
fi

# ---------------------------------------------------------------------------------------------
# 2. The Android artifacts' exported surface.
#
# Kotlin's ABI validation covers the nine modules it can and cannot cover an Android library, so
# these two — the artifacts an Android host actually depends on — were guarded by nothing. See
# scripts/android-api.sh.
# ---------------------------------------------------------------------------------------------
run_gate "android-api" ./scripts/android-api.sh

# ---------------------------------------------------------------------------------------------
# 3. Every seam, on every host.
#
# The README's matrix of what each surface exposes was written by hand, which made it a claim. The
# two seams an adopter reported missing were both absent from a host while that table said the shape
# was deliberate. This derives it from the recorded surfaces instead.
# ---------------------------------------------------------------------------------------------
run_gate "host-parity" python3 ./scripts/host-parity.py

# ---------------------------------------------------------------------------------------------
# 4. Every conformance golden, read by every engine.
#
# host-parity checks a seam *exists* on each surface and cannot check that two engines agree about
# what to do with it — a signature does not say how a CSS font stack is read, and three engines read
# one three different ways (#123) behind a green matrix. `test-fixtures/host-conformance` is where
# that agreement is written down, and it only works if every golden is read on every side: a golden
# wired to one host leaves the disagreement exactly as invisible as before, with a file on disk
# suggesting otherwise. Nothing else can notice, because a missing test has no signature.
#
# The readers themselves run inside the gradle, swift and instrumented gates. This checks the
# pairing.
# ---------------------------------------------------------------------------------------------
run_gate "host-conformance" python3 ./scripts/host-conformance.py

# ---------------------------------------------------------------------------------------------
# 5. A branch that changes the public surface says so in the changelog.
#
# `changelog-section.sh` checks a section *exists* for the version being released and cannot check
# that it is complete. 0.4.0's was not: two of five commits had entries, so a source-breaking change
# would have shipped unmentioned, and rewriting a pull request description is what caught it.
# ---------------------------------------------------------------------------------------------
run_gate "changelog" python3 ./scripts/changelog-gate.py

# ---------------------------------------------------------------------------------------------
# 5b. SUPPORTED_FEATURES.md is answerable to the code and to the tests.
#
# The document is what an adopter reads before depending on any of this, and nothing checked it. It
# drifted the way an unchecked document does: one row said `isDate`, `isRegExp` and `isTuple` "stay
# out with reasons" while all three were implemented, and a row seventy lines below listed
# `isRegExp()` as supported — the file disagreeing with itself *and* with the code (#154).
# Thirty-two registered expression functions were named nowhere in it at all.
#
# **Two halves, and only one of them runs here.** The half that is machine-independent runs
# everywhere: the capability source still reproduces every row, and every function the engine
# registers is named. The other half — a row claiming support whose cited tests did not run or did
# not pass — needs the union of four CI jobs, because no single host runs the emulator, `linuxX64`
# and the Swift suite. So the *status* column is generated in CI and checked there, and this gate
# deliberately does not attempt it: generating from one laptop would mark two hundred rows unproven
# and the diff would be about the machine rather than about the code.
# ---------------------------------------------------------------------------------------------
capabilities_gate() {
  # Two halves. `--selftest` exercises the limitation rule itself on constructed rows, because a rule
  # that nothing checks is the very thing it exists to prevent. `--check` then applies it to the
  # document: every row claiming a limitation names a test that asserts it, or a scope reason.
  python3 ./scripts/capabilities.py --selftest
  python3 ./scripts/capabilities.py --check
}
run_gate "capabilities" capabilities_gate

# ---------------------------------------------------------------------------------------------
# 6. The Swift gate: the suite, the exported-API snapshot, and the iOS demo's type-check.
#
# This is the one whose absence was found the hard way, twice. `swift-test.sh` carries the
# `ios-demo.sh --check` step for the same reason.
# ---------------------------------------------------------------------------------------------
if [ "$(uname -s)" = "Darwin" ]; then
  run_gate "swift" ./scripts/swift-test.sh

  # ---------------------------------------------------------------------------------------------
  # 6b. The iOS demo's UI tests, which are the only place VoiceOver is checked end to end.
  #
  # The accessibility *rules* live in the core and are tested there; whether SwiftUI actually
  # exposes them to a reader can only be asked of a running app. Three tests: every bar is its own
  # labelled element, the axes are described, and activating an element selects the mark it stands
  # for.
  #
  # **They ran nowhere until now, and that cost a real defect.** The row in
  # `SUPPORTED_FEATURES.md` claiming a SwiftUI chart view cited them as evidence, the generated
  # status column said "unproven here", and the reason was worse than a missing gate: the Xcode
  # project had two duplicated object ids and `xcodebuild` refused to open it at all. Behind that,
  # the accessibility overlay positioned its elements with `.offset` — a render transform, which
  # leaves the accessibility frame where layout put it — so all forty-eight touch targets were
  # stacked on the first mark and every tap landed on the y-axis.
  #
  # Needs a simulator *runtime*, which not every Mac has; `--check` inside the swift gate is what
  # runs without one. Skipped rather than failed where it is absent, and said so.
  # ---------------------------------------------------------------------------------------------
  if [ "$(xcrun simctl list runtimes 2>/dev/null | grep -c "iOS")" -gt 0 ]; then
    run_gate "ios-ui" ./scripts/ios-demo.sh --test
  else
    skip_gate "ios-ui" "no iOS simulator runtime; install with: xcodebuild -downloadPlatform iOS"
  fi
else
  skip_gate "swift" "this host is $(uname -s); the Swift package needs a Mac"
fi

# ---------------------------------------------------------------------------------------------
# 7. The instrumented suites, if something is listening.
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

  # **A skipped instrumented test is reported by name.** Gradle prints a count and the build stays
  # green, so a suite that quietly stopped exercising something looks exactly like one that checked
  # it — which is how a Vega-Lite fixture skipped two comparisons for ten days. The commonest cause
  # here is a device with no route out: `scripts/emulator.sh` starts the AVD with name servers so the
  # tests that fetch actually run, and if one skips anyway that is worth seeing rather than passing.
  python3 - <<'SKIPS'
import glob, xml.etree.ElementTree as ET

skipped = []
for path in glob.glob("*/build/outputs/androidTest-results/**/*.xml", recursive=True):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in suite.iter("testcase"):
        for skip in case.iter("skipped"):
            why = (skip.get("message") or "no reason given").strip()
            skipped.append(f"  {case.get('name')}: {why}")

if skipped:
    print()
    print(f"NOTE: {len(skipped)} instrumented test(s) skipped, which the build does not fail for:")
    for line in skipped:
        print(line)
SKIPS
else
  skip_gate "instrumented" "no device on adb; start one with scripts/emulator.sh --headless"
fi

# ---------------------------------------------------------------------------------------------
# 8 and 9. The differential comparisons, which are the point of the project.
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
