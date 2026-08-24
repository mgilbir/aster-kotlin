#!/usr/bin/env bash
# The API the two **Android** artifacts expose, as a file you can read in a diff.
#
# The other nine published modules have their surface dumped by Kotlin's ABI validation, and
# `checkKotlinAbi` fails when it moves. These two cannot: ABI validation reads a module's Maven
# publications and does not support an Android library's — KGP has a diagnostic named for it,
# `AbiValidationAndroidPublicationNotSupported`. So `vega-android-canvas` and `vega-compose`, which
# are the two artifacts an Android host actually depends on, were guarded by nothing at all and the
# build file said so: "the one place a consumer has to read the diff by hand".
#
# Reading it by hand is what failed. `vega-compose` shipped 0.2.0 exposing a controller, a modifier
# and one callback while the view underneath had a font resolver, an accessibility threshold and a
# tooltip switch; an adopter found it (#99), not a review. A surface nobody snapshots is a surface
# nobody diffs.
#
# So it is snapshotted the way the Obj-C surface is — see `scripts/foreign-api.sh`, which exists for
# the same reason and was written first. A change is not forbidden, it is *shown*.
#
#   scripts/android-api.sh            compare against the snapshot; fail on a difference
#   scripts/android-api.sh --accept   record the current surface as the new snapshot
#
# `javap` rather than a source scan, because the boundary is the bytecode: a Kotlin default argument
# is a `$default` bridge, a `@Composable` gains a `Composer` and two `int`s, and a parameter added
# in the middle of a list is invisible in source and a different method here. The full parameter
# list of every function is what this records, which is exactly the thing that broke.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-check}"
SNAPSHOT="android-api.txt"
MODULES=(vega-android-canvas vega-compose)

# Always rebuilt, never trusted from a previous commit. A stale classes directory makes this pass
# against a surface nobody is shipping, which is the one failure mode a snapshot gate must not have;
# `foreign-api.sh` learned that by reporting "no change" for a commit that added a dozen symbols.
echo "==> Compiling the Android artifacts, which is where the classes come from"
./gradlew ":${MODULES[0]}:compileDebugKotlin" ":${MODULES[1]}:compileDebugKotlin" -q

CURRENT="$(mktemp)"
trap 'rm -f "$CURRENT"' EXIT

for module in "${MODULES[@]}"; do
  # `compileDebugKotlin`'s own output, and **not** `runtime_library_classes_dir`, which looks like
  # the obvious choice and is a trap: it is written by `bundleLibRuntimeToDirDebug`, which is not in
  # `assembleDebug`'s task graph, so it sits at whatever a previous command left there. Reading it
  # reported "matches the snapshot" for a tree that had just gained a parameter — the exact stale-
  # artefact failure `foreign-api.sh` warns about, found by testing this gate rather than trusting it.
  classes="$module/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
  if [ ! -d "$classes" ]; then
    echo "No classes at $classes; the Android build layout has moved." >&2
    echo "Find the new one with: find $module/build -name '*.class' -path '*dev/aster*'" >&2
    exit 1
  fi
  python3 - "$module" "$classes" >> "$CURRENT" <<'EXTRACT'
import pathlib
import re
import subprocess
import sys

module, root = sys.argv[1], pathlib.Path(sys.argv[2])

# Compiler artifacts, not API. A lambda's class is numbered by the order lambdas appear in the file,
# so keeping them would make an unrelated edit look like a change to the surface — the opposite of
# what a snapshot is for. `WhenMappings` is the same: generated for a `when` over an enum.
ARTIFACT = re.compile(r"\$\d|\$\$|\$lambda|\$inlined|WhenMappings|\$ExternalSyntheticLambda")
# `access$…` is the bridge a lambda uses to reach a private member, numbered the same way.
SYNTHETIC = re.compile(r"\baccess\$")

names = []
for path in sorted(root.rglob("*.class")):
    name = str(path.relative_to(root))[: -len(".class")].replace("/", ".")
    if ARTIFACT.search(name):
        continue
    names.append(name)

if not names:
    raise SystemExit(f"no classes found under {root}")

output = subprocess.run(
    ["javap", "-public", "-classpath", str(root), *names],
    capture_output=True,
    text=True,
    check=True,
).stdout

# `javap` prints a "Compiled from" line per class, which names the source file and says nothing
# about the surface. Everything else is kept verbatim and sorted per class, so a member moving
# within a file is not a diff.
declarations = []
current = None
for line in output.splitlines():
    if line.startswith("Compiled from"):
        continue
    if SYNTHETIC.search(line):
        continue
    if not line.startswith(" "):
        current = [line.rstrip()]
        declarations.append(current)
    elif current is not None:
        current.append(line.strip())

for declaration in sorted(declarations, key=lambda d: d[0]):
    head, members = declaration[0], sorted(declaration[1:])
    print(f"{module}  {head}")
    for member in members:
        if member == "}":
            continue
        print(f"    {member}")
EXTRACT
done

if [ "$MODE" = "--accept" ]; then
  cp "$CURRENT" "$SNAPSHOT"
  echo "==> Recorded $(grep -c . "$SNAPSHOT") lines in $SNAPSHOT"
  exit 0
fi

if [ ! -f "$SNAPSHOT" ]; then
  echo "No snapshot at $SNAPSHOT. Record one with: scripts/android-api.sh --accept" >&2
  exit 1
fi

if diff -u "$SNAPSHOT" "$CURRENT" > /dev/null; then
  echo "==> The Android API matches the snapshot ($(grep -c . "$SNAPSHOT") lines)"
  exit 0
fi

echo "The API the Android artifacts expose has changed:"
echo
diff -u "$SNAPSHOT" "$CURRENT" | tail -n +3
echo
echo "If that is intended, record it with:"
echo "    scripts/android-api.sh --accept"
echo "and let the diff be reviewed as a change to what a host compiles against."
exit 1
