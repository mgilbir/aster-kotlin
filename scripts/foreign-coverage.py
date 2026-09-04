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
import collections, pathlib, re, sys

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
    # A **top-level extension function** is credited to the type it extends, because Kotlin/Native
    # emits it as an Obj-C category on that type: `Scene.flatten()`. Kotlin's own ABI dump names it
    # after the *file class* instead — `SceneKt.flatten` — so the two disagree about the owner and
    # nothing matches.
    #
    # This used to be papered over by matching bare names anywhere in the file, because the snapshot
    # recorded these mangled (`transformedBy(transform:).flatten()`). Fixing that extractor removed
    # the paper and the gate immediately reported six unexplained members, which is the gate working.
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
        # `SceneKt.flatten` is `Scene.flatten()` across the boundary.
        and not (candidates(m) & objc.get(cls.removesuffix("Kt"), set()))
        and not (cls.endswith("Kt") and candidates(m) & bare)
    )
    if missing:
        holes[cls] = missing

# Every member here carries a **reason**, and a member without one fails the check. The list on
# its own was a flat file to eyeball, which is how a member that a host wanted could sit in it
# looking like all the others.
REASONS = {
    "getPublishesSignal": "internal: @InternalAsterVegaApi, dataflow plumbing a host never builds",
    "activeItem": "internal: @InternalAsterVegaApi, the expression evaluator's scope",
    "encodeItem": "internal: @InternalAsterVegaApi, the expression evaluator's scope",
    "eventPoint": "internal: @InternalAsterVegaApi, the expression evaluator's scope",
    "inScope": "internal: @InternalAsterVegaApi, the expression evaluator's scope",
    "intersect": "internal: @InternalAsterVegaApi, the expression evaluator's scope",
    "intersectLasso": "internal: @InternalAsterVegaApi, the expression evaluator's scope",
    "getExpr": "internal: the compiled expression's own AST",
    "getScopePath": "internal: @InternalAsterVegaApi, which group scope a handler belongs to",
    "getScopedOverrides": "internal: @InternalAsterVegaApi, group-scoped signal values for the next compile",
    "getBins": "reachable: ForeignScale.bins",
    "extentAt": "reachable: ForeignScale.bucketLow / bucketHigh",
    "legendFraction": "reachable: ForeignScale.legendFraction",
    "getBucketRepresentatives": "reachable: ForeignScale.bucketRepresentatives",
    "getLegendMax": "reachable: ForeignScale.legendMax",
    "getLegendValues": "reachable: ForeignScale.legendValues",
    "getLegendExtent": "reachable: ForeignScale.legendExtentLow / legendExtentHigh",
    "getThresholds": "reachable: ForeignScale.thresholds",
}

unexplained, internal, reachable = [], [], []
lines = []
for cls in sorted(holes):
    for member in holes[cls]:
        reason = REASONS.get(member)
        if reason is None:
            unexplained.append(f"{cls}.{member}")
            reason = "!! NO REASON RECORDED"
        elif reason.startswith("internal"):
            internal.append(f"{cls}.{member}")
        else:
            reachable.append(f"{cls}.{member}")
        lines.append(f"{cls}.{member:32} {reason}")

# The count that matters is the last one. "77 members do not cross" reads like a backlog and is
# not: a member reachable through a `Foreign*` accessor is not a hole, it is the answer to one.
print(f"# {len(lines)} public members have no direct foreign counterpart:")
print(f"#   {len(internal):3} engine internals, marked @InternalAsterVegaApi")
print(f"#   {len(reachable):3} reachable through a Foreign* accessor instead")
print(f"#   {len(unexplained):3} unexplained  <- the number this gate is about")
print("#")
for line in lines:
    print(line)

if unexplained:
    print(file=sys.stderr)
    print("These do not reach a foreign host and no reason is recorded:", file=sys.stderr)
    for name in unexplained:
        print(f"  {name}", file=sys.stderr)
    print(file=sys.stderr)
    print("Either a host wants it — expose it through a Foreign* accessor — or it is engine", file=sys.stderr)
    print("internals: mark it @InternalAsterVegaApi and add it to REASONS here.", file=sys.stderr)
    sys.exit(1)
