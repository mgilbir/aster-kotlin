#!/usr/bin/env python3
"""Every public Kotlin member that does **not** reach a foreign host, as a list to diff.

`foreign-api.txt` records what crossed. This records what did not, which is the half that goes
unnoticed: a type can cross while the part of it worth reading stays behind, and nothing fails.
Both boundary defects found by adopters were that shape — `SceneNodeId` inside an optional, and
`VegaValue.Obj.fields` behind a value class.

Run by `scripts/foreign-api.sh`. A change here is not forbidden, it is *shown*: a new line means a
member stopped crossing, and the question is whether a host wanted it.

Everything currently listed is engine-internal — `getPublishesSignal` on each transform, the
discretizing scales' legend arithmetic, the expression evaluator's scope, and one AST accessor —
public for cross-module use in Kotlin and of no use to a host.
"""
import re, pathlib, collections

kotlin, enums = collections.defaultdict(set), set()
for api in pathlib.Path(".").glob("vega-*/api/*.api"):
    cls = None
    for line in api.read_text().splitlines():
        m = re.match(r"public\s+[\w\s]*?(?:class|interface)\s+([\w/$]+)(.*)", line)
        if m:
            cls = m.group(1).split("/")[-1].split("$")[-1]
            if "java/lang/Enum" in m.group(2):
                enums.add(cls)
            continue
        if cls and line.startswith("\t"):
            g = re.search(r"\bfun\s+(\w+)", line) or re.search(r"\bfield\s+(\w+)", line)
            if g:
                kotlin[cls].add(g.group(1))

objc, present, bare = collections.defaultdict(set), set(), set()
for line in pathlib.Path("swift/AsterVegaRender/foreign-api.txt").read_text().splitlines():
    line = line.strip()
    if not line: continue
    # A **top-level extension function** is listed unqualified — `toVega(json:...)`,
    # `toSvg(options:)` — and `foreign-api.sh` credits any symbol after it to it as an owner, so
    # `transformedBy(transform:).flatten()` really means `flatten()` crossed as a free function.
    # Both are names a host can call, and neither is `Class.member`. Missing this read `toVega` as
    # absent while `ChartSession` calls it three lines away.
    if "(" in line.split(".")[0]:
        for segment in line.split("."):
            name = segment.split("(")[0].rstrip("_").strip()
            if name:
                bare.add(name.lower())
    head = line.split(".")[0]
    present.add(head)
    if "." in line:
        objc[head].add(line.split(".", 1)[1].split("(")[0].rstrip("_").lower())

def candidates(name):
    out = {name}
    for p in ("get", "set", "is"):
        if name.startswith(p) and len(name) > len(p) and name[len(p)].isupper():
            out.add(name[len(p)].lower() + name[len(p) + 1:])
    return {c.lower() for c in out}

SKIP = {"equals", "hashcode", "tostring", "copy", "valueof", "values", "getentries", "compareto"}
holes = {}
for cls, members in kotlin.items():
    if cls not in present or cls in enums:
        continue
    missing = sorted(
        m for m in members
        if m.lower() not in SKIP
        and not m.startswith(("component", "copy$", "access$", "<", "$"))
        and not m.isupper()                       # enum entries / constants
        and not (candidates(m) & objc[cls])
        and not (candidates(m) & bare)
    )
    if missing:
        holes[cls] = missing

for cls in sorted(holes):
    for member in holes[cls]:
        print(f"{cls}.{member}")
