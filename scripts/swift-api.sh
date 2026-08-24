#!/usr/bin/env bash
# The API the Swift package offers a host, as a file you can read in a diff.
#
# `swift/AsterVegaRender/foreign-api.txt` records what **Kotlin exports to Obj-C**, which is a
# different surface: `VegaChartView.init` and `ChartSession` are Swift source and appear in it
# nowhere. So the half of the Apple API a host actually calls was guarded by `CallShapeTests` alone —
# which pins the call shapes somebody thought to write down, and is therefore exactly as good as
# somebody's foresight was.
#
# It was not good enough twice. A trailing closure rebound to a new parameter and broke `main` (#93),
# and a missing font seam sat unnoticed until an adopter reported it (#106). Neither is a shape a
# hand-written test would have thought to assert; both are one line of a diff here.
#
#   scripts/swift-api.sh            compare against the snapshot; fail on a difference
#   scripts/swift-api.sh --accept   record the current surface as the new snapshot
#
# `swift build -emit-symbol-graph` rather than `swift package dump-symbol-graph`, which cannot see
# the framework: the extractor is invoked without the package's own `-F`, so it fails with "missing
# required module 'AsterVega'". Going through the build gets the flags the package declares.
set -euo pipefail
cd "$(dirname "$0")/../swift/AsterVegaRender"

MODE="${1:-check}"
SNAPSHOT="swift-api.txt"
GRAPHS=".build/symbolgraph"
SCRATCH=".build/symbolgraph-build"

# **Its own scratch path, and the graph deleted first.** A symbol graph is emitted by the *compile*,
# so an incremental build with nothing to do emits nothing — and the second run of this script found
# no file at all where the first had left one. Sharing `.build` with `swift test` makes that the
# normal case rather than the exception: the tests compile the target, and this then asks a build that
# is already up to date for an artefact it only produces while compiling. The scratch path is
# *deleted* rather than merely separated, because keeping it makes the second run incremental too —
# which is how this was found: the guard below fired on the run after the one that recorded the
# snapshot.
#
# The same shape as the stale-classes trap in `scripts/android-api.sh`, and the one `foreign-api.sh`
# warns about in its own header: a snapshot gate that reads a leftover artefact reports "no change"
# for a commit that changed things. A dedicated scratch path costs a compile of one target and cannot
# be stale.
echo "==> Emitting the symbol graph, which is where the surface comes from"
rm -rf "$GRAPHS" "$SCRATCH"
mkdir -p "$GRAPHS"
swift build --target AsterVegaRender \
  --scratch-path "$SCRATCH" \
  -Xswiftc -emit-symbol-graph \
  -Xswiftc -emit-symbol-graph-dir -Xswiftc "$GRAPHS" >/dev/null

if [ ! -f "$GRAPHS/AsterVegaRender.symbols.json" ]; then
  echo "No symbol graph at $GRAPHS after building; the toolchain's flags have moved." >&2
  exit 1
fi

CURRENT="$(mktemp)"
trap 'rm -f "$CURRENT"' EXIT

python3 - "$GRAPHS/AsterVegaRender.symbols.json" > "$CURRENT" <<'EXTRACT'
import json
import sys

graph = json.load(open(sys.argv[1]))

lines = []
for symbol in graph["symbols"]:
    # **Only what this package declares.** A symbol with no source location is inherited or
    # synthesised from somewhere else, and for `VegaChartView` that is 810 SwiftUI `View` defaults —
    # `offset(_:)`, `symbolEffect(_:options:isActive:)` and the rest. They are the SDK's API, not
    # this package's, and they change when Xcode does: keeping them would make a toolchain upgrade
    # look like a change to what a host compiles against, which is the one thing a snapshot must not
    # do.
    if "location" not in symbol:
        continue
    declaration = "".join(
        fragment["spelling"] for fragment in symbol.get("declarationFragments", [])
    )
    # Newlines in a declaration would break one-symbol-per-line, and a long signature is exactly the
    # kind that wraps.
    declaration = " ".join(declaration.split())
    lines.append(f"{'.'.join(symbol['pathComponents'])}  ::  {declaration}")

if not lines:
    raise SystemExit("no symbols with a source location; the graph format has moved")

for line in sorted(set(lines)):
    print(line)
EXTRACT

if [ "$MODE" = "--accept" ]; then
  cp "$CURRENT" "$SNAPSHOT"
  echo "==> Recorded $(grep -c . "$SNAPSHOT") symbols in swift/AsterVegaRender/$SNAPSHOT"
  exit 0
fi

if [ ! -f "$SNAPSHOT" ]; then
  echo "No snapshot at swift/AsterVegaRender/$SNAPSHOT. Record one with: scripts/swift-api.sh --accept" >&2
  exit 1
fi

if diff -u "$SNAPSHOT" "$CURRENT" > /dev/null; then
  echo "==> The Swift API matches the snapshot ($(grep -c . "$SNAPSHOT") symbols)"
  exit 0
fi

echo "The API the Swift package offers has changed:"
echo
diff -u "$SNAPSHOT" "$CURRENT" | tail -n +3
echo
echo "If that is intended, record it with:"
echo "    scripts/swift-api.sh --accept"
echo "and let the diff be reviewed as a change to what a host compiles against."
exit 1
