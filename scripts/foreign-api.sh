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

# **Always**, not only when the header is missing. A header left over from another commit makes this
# check pass against a surface nobody is shipping, which is the one failure mode a snapshot gate must not
# have — and it is not hypothetical: comparing against a stale header reported "no change" for a commit
# that had added a dozen symbols. Gradle is incremental, so a link with nothing to do costs a second.
echo "==> Linking the framework, which is where the header comes from"
./gradlew :vega-runtime:linkDebugFrameworkMacosArm64

if [ ! -f "$HEADER" ]; then
  echo "No header at $HEADER even after linking; something is wrong with the framework task." >&2
  exit 1
fi

# Every exported symbol, qualified by the type that owns it. The header's own `swift_name` attributes are
# the authority — they are what Swift actually sees, including the full parameter list of every
# initialiser, which is the thing that breaks.
CURRENT="$(mktemp)"

# Every exported symbol, qualified by the type that owns it. The header's own `swift_name` attributes are
# the authority: they are what Swift actually sees, including each initialiser's full parameter list, which
# is the part that breaks.
python3 - "$HEADER" > "$CURRENT" <<'EXTRACT'
import pathlib
import re
import sys

NAME = re.compile(r'swift_name\("([^"]+)"\)')
# A category — `@interface AsterVegaScene (Extensions)` — is how Kotlin/Native emits a top-level
# extension function. It carries no `swift_name` of its own; its members belong to the type it
# extends.
TYPE = re.compile(r"^@(?:interface|protocol)\s+(\w+)(\s*\(\s*(\w+)\s*\))?")
END = re.compile(r"^@end")
# An attribute-only line: what a type's `swift_name` sits on, above its `@interface`.
BARE_ATTRIBUTE = re.compile(r"^\s*__attribute__")

lines = pathlib.Path(sys.argv[1]).read_text(errors="ignore").splitlines()

# A type's own `swift_name` sits on the attribute line *above* its `@interface`, so a naive scan
# credits it to the type before it as if it were a member.
#
# The scan must also stop at anything that is not a bare attribute. It did not, and a category
# declared a few lines below a method picked that **method's** name up as its own: the snapshot
# recorded `transformedBy(transform:).flatten()`, meaning `flatten()` was credited to a function.
# Harmless to the diff and not to a reader — it cost an audit a wrong conclusion, reading `toVega`
# as absent while `ChartSession` calls it three lines away.
type_name_line = {}
for index, line in enumerate(lines):
    declaration = TYPE.match(line)
    if not declaration or declaration.group(3):
        continue  # a category takes its owner from the type it extends, below
    for back in range(index - 1, max(index - 6, -1), -1):
        above = lines[back]
        if not above.strip():
            continue
        if not BARE_ATTRIBUTE.match(above):
            break  # a declaration, not this type's name
        named = NAME.search(above)
        if named:
            type_name_line[back] = named.group(1)
            break

# Obj-C class name to the name Swift sees, so a category can be credited to the right type.
swift_name_of = {}
for index, line in enumerate(lines):
    declaration = TYPE.match(line)
    if declaration and not declaration.group(3):
        above = [name for at, name in type_name_line.items() if index - 6 < at < index]
        swift_name_of[declaration.group(1)] = above[-1] if above else declaration.group(1)

owner = None
symbols = set()
for index, line in enumerate(lines):
    if index in type_name_line:
        continue  # consumed as the name of the type declared just below
    declaration = TYPE.match(line)
    if declaration:
        objc = declaration.group(1)
        owner = swift_name_of.get(objc, objc)
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

# The other half of the same question, and the half that goes unnoticed: what did **not** cross.
# A type can be exported while the part of it worth reading stays behind — a value class inside an
# optional, a `Map`'s keys — and nothing fails. Both boundary defects an adopter reported were that
# shape. `scripts/foreign-coverage.py` lists every public Kotlin member with no foreign counterpart;
# a new line means one stopped crossing.
COVERAGE="swift/AsterVegaRender/foreign-coverage.txt"
COVERAGE_NOW="$(mktemp)"
trap 'rm -f "$CURRENT" "$COVERAGE_NOW"' EXIT
python3 scripts/foreign-coverage.py > "$COVERAGE_NOW"

if [ "$MODE" = "--accept" ]; then
  cp "$COVERAGE_NOW" "$COVERAGE"
  echo "==> Recorded $(grep -c . "$COVERAGE") members that do not cross, in $COVERAGE"
fi

if [ "$MODE" != "--accept" ] && ! diff -u "$COVERAGE" "$COVERAGE_NOW" > /dev/null; then
  echo "What reaches a foreign host has changed:"
  echo
  diff -u "$COVERAGE" "$COVERAGE_NOW" | tail -n +3
  echo
  echo "A line **added** means a public member stopped crossing — ask whether a host wanted it."
  echo "A line **removed** means one started, which is usually the point of the change."
  echo "Record it with: scripts/foreign-api.sh --accept"
  exit 1
fi

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
