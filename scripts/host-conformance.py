#!/usr/bin/env python3
"""Every conformance golden is read by every engine that implements its seam.

`test-fixtures/host-conformance` only works if each golden is read on all sides. A golden wired to
one host is worse than no golden: the file exists, the suite is green, and the disagreement it was
written to catch is exactly as invisible as before. Nothing else notices — `scripts/host-parity.py`
reads signatures and a missing *test* has no signature.

So the pairing is checked here: for each `*.txt`, a reader in each engine's test tree that names it.

Three engines, not four. `vega-compose` hosts `VegaChartView` through `AndroidView` rather than
drawing anything itself, so it has no text or image engine of its own to disagree with; the Android
reader covers both surfaces. If that ever stops being true, this list is what has to grow.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GOLDENS = ROOT / "test-fixtures" / "host-conformance"

ENGINES = {
    "Android (AndroidTextEngine, AndroidCanvasSceneRenderer)": (
        "vega-android-canvas/src/androidTest/kotlin/dev/aster/vega/android"
    ),
    "Compose Multiplatform (ComposeTextEngine, DrawScopeTarget)": (
        "vega-compose-multiplatform/src/jvmTest/kotlin/dev/aster/vega/compose/mp"
    ),
    "Apple (CoreTextTextEngine, CoreGraphicsTarget)": (
        "swift/AsterVegaRender/Tests/AsterVegaRenderTests"
    ),
}


def cases(golden: pathlib.Path) -> list[str]:
    """The inputs in a golden, which is also the check that it parses at all."""
    found = []
    for number, line in enumerate(golden.read_text().splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        if " -> " not in line:
            raise SystemExit(
                f"{golden.name}:{number}: no ` -> `, so no reader can parse it: {line}"
            )
        found.append(line.split(" -> ", 1)[0])
    return found



def string_literals(source):
    """
    Every double-quoted string in `source`, with comments removed first.

    The check this feeds used to be `golden.name in text` — a substring match over the whole file —
    so a **comment** mentioning a golden's filename counted as a reader. That is the failure this
    gate exists to catch, in the gate: deleting the assertions from a conformance test while leaving
    the sentence above them saying what they used to check would have kept it green. A file that
    genuinely reads a golden names it in a string, because that is how a path is written.

    A deliberately small scanner rather than a parser: Kotlin and Swift agree on `//`, on `/* */`
    and on `"`, and nothing in `test-fixtures/host-conformance` has a name that could hide in a
    character literal or an interpolation.
    """
    literals = []
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if character == "/" and index + 1 < length:
            following = source[index + 1]
            if following == "/":
                index = source.find("\n", index)
                if index < 0:
                    break
                continue
            if following == "*":
                end = source.find("*/", index + 2)
                index = length if end < 0 else end + 2
                continue
        if character == '"':
            # A raw string, `"""..."""` in both languages, holds no escapes.
            if source.startswith('"""', index):
                end = source.find('"""', index + 3)
                if end < 0:
                    break
                literals.append(source[index + 3 : end])
                index = end + 3
                continue
            cursor = index + 1
            while cursor < length and source[cursor] != '"':
                cursor += 2 if source[cursor] == "\\" else 1
            literals.append(source[index + 1 : cursor])
            index = cursor + 1
            continue
        index += 1
    return literals


def main() -> int:
    goldens = sorted(p for p in GOLDENS.glob("*.txt"))
    if not goldens:
        print(
            "no conformance goldens found;"
            " expected at least one in test-fixtures/host-conformance"
        )
        return 1

    readers = {
        engine: {
            path: string_literals(path.read_text())
            for path in (ROOT / directory).rglob("*")
            if path.is_file() and path.suffix in (".kt", ".swift")
        }
        for engine, directory in ENGINES.items()
    }

    problems = []
    for golden in goldens:
        found = cases(golden)
        if not found:
            problems.append(f"{golden.name} has no cases in it")
        if len(set(found)) != len(found):
            repeated = sorted({c for c in found if found.count(c) > 1})
            problems.append(f"{golden.name} repeats a case: {', '.join(repeated)}")

        line = f"  {golden.name:24}{len(found):4} cases"
        for engine, sources in readers.items():
            reading = [
                p.name
                for p, literals in sources.items()
                if any(golden.name in literal for literal in literals)
            ]
            if reading:
                line += f"  ✓ {engine.split(' (')[0]}"
            else:
                problems.append(f"{golden.name} is not read by any {engine} test")
        print(line)

    for problem in problems:
        print(f"error: {problem}")
    if problems:
        return 1
    print(
        f"{len(goldens)} conformance goldens, each read by all {len(ENGINES)} engines."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
