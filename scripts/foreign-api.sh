#!/usr/bin/env bash
# The API this engine exports to hosts that are not Kotlin, as a file you can read in a diff.
#
# Kotlin's source compatibility is not the boundary's. Giving a parameter a default is invisible to every
# Kotlin caller and **breaks every Swift one**, because a default argument has no Obj-C representation and
# Swift must name every parameter. Nothing on the Kotlin side reports that: the module compiles, the JVM
# tests pass, and the break surfaces the next time somebody builds the Swift package — which, once, was
# after the change had been merged.
#
# So the exported surface is snapshotted the way the differential fixtures snapshot upstream's answers: a
# change is not forbidden, it is *shown*. A boundary that grew a parameter, lost a method or renamed a type
# appears in the review as a diff somebody chose to accept.
#
#   scripts/foreign-api.sh            compare against the snapshot; fail on a difference
#   scripts/foreign-api.sh --accept   record the current surface as the new snapshot
#
# Requires Xcode, because the header only exists once the framework is linked. `scripts/swift-test.sh` runs
# this, so the Apple gate covers it.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-check}"
HEADER="vega-runtime/build/bin/macosArm64/debugFramework/AsterVega.framework/Headers/AsterVega.h"
SNAPSHOT="swift/AsterVegaRender/foreign-api.txt"

if [ ! -f "$HEADER" ]; then
  echo "==> Linking the framework, which is where the header comes from"
  ./gradlew :vega-runtime:linkDebugFrameworkMacosArm64
fi

# Every exported symbol, qualified by the type that owns it. The header's own `swift_name` attributes are
# the authority — they are what Swift actually sees, including the full parameter list of every
# initialiser, which is the thing that breaks.
CURRENT="$(mktemp)"
trap 'rm -f "$CURRENT"' EXIT

# Every exported symbol, qualified by the type that owns it. The header's own `swift_name` attributes are
# the authority: they are what Swift actually sees, including each initialiser's full parameter list, which
# is the part that breaks.
python3 - "$HEADER" > "$CURRENT" <<'EXTRACT'
import pathlib
import re
import sys

NAME = re.compile(r'swift_name\("([^"]+)"\)')
TYPE = re.compile(r"^@(?:interface|protocol)\s+(\w+)")
END = re.compile(r"^@end")

lines = pathlib.Path(sys.argv[1]).read_text(errors="ignore").splitlines()

# A type's own `swift_name` sits on the attribute line *above* its `@interface`, so a naive scan credits it
# to the type before it as if it were a member. The declarations are found first, and the attribute lines
# they consume are then skipped.
type_name_line = {}
for index, line in enumerate(lines):
    if not TYPE.match(line):
        continue
    for back in range(index - 1, max(index - 6, -1), -1):
        named = NAME.search(lines[back])
        if named:
            type_name_line[back] = named.group(1)
            break

owner = None
symbols = set()
for index, line in enumerate(lines):
    if index in type_name_line:
        continue  # consumed as the name of the type declared just below
    declaration = TYPE.match(line)
    if declaration:
        above = [name for at, name in type_name_line.items() if index - 6 < at < index]
        owner = above[-1] if above else declaration.group(1)
        continue
    if END.match(line):
        owner = None
        continue
    named = NAME.search(line)
    if named:
        symbols.add(f"{owner}.{named.group(1)}" if owner else named.group(1))

# The type names themselves, so a removed class shows up as a removed line.
symbols.update(type_name_line.values())

for symbol in sorted(symbols):
    print(symbol)
EXTRACT

if [ "$MODE" = "--accept" ]; then
  cp "$CURRENT" "$SNAPSHOT"
  echo "==> Recorded $(wc -l < "$SNAPSHOT" | tr -d ' ') exported symbols in $SNAPSHOT"
  exit 0
fi

if [ ! -f "$SNAPSHOT" ]; then
  echo "No snapshot at $SNAPSHOT. Record one with: scripts/foreign-api.sh --accept" >&2
  exit 1
fi

if diff -u "$SNAPSHOT" "$CURRENT" > /dev/null; then
  echo "==> The exported API matches the snapshot ($(wc -l < "$SNAPSHOT" | tr -d ' ') symbols)"
  exit 0
fi

echo "The API exported to foreign hosts has changed:" >&2
echo >&2
diff -u "$SNAPSHOT" "$CURRENT" | sed -n '3,60p' >&2
echo >&2
cat >&2 <<'MESSAGE'
A removed or altered line is a break for every Swift and Obj-C caller, including ones outside this
repository. A *changed initialiser* usually means a Kotlin parameter gained a default, which is
source-compatible in Kotlin and breaking here.

If the change is intended, record it and it becomes part of the review:

    scripts/foreign-api.sh --accept
MESSAGE
exit 1
