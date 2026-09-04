#!/usr/bin/env python3
"""How many tests actually ran, per module, and whether any suite assumed itself away.

A green run that quietly skipped a suite is worse than a red one, and nothing in Gradle's output
distinguishes them: it prints a test count only for tasks that *fail*. The first run of the CI
workflow reported two numbers, both from failures, and said nothing whatsoever about the other
eleven test tasks. So the counts are summed from the JUnit XML instead.

Two assertions, and the per-module one is the one that bites. A whole-suite floor would not have
caught what actually happened: `vega-runtime` — holding both differential gates — did not run at
all, because the build stopped at the first failing module. Naming the modules is what makes "the
suite ran" checkable rather than plausible.

**This lives in a script so `check.sh` and CI run the same one.** It was a heredoc inside the
workflow, and that was a hole rather than a detail: `check.sh` is the pre-landing gate, so a rule
only CI knows is a rule that lands broken. It cost three red runs on `main` — a nested-scale test
that skipped 195 of 198 fixtures passed `check.sh` twelve gates green and failed the step that
counts skips, twice, before anybody looked at the workflow.

  scripts/test-counts.py <host> <root>

`host` is `Linux` or `macOS`, which decides whether the Kotlin/Native floor applies. `root` is the
directory holding the module folders — the repository itself locally, or wherever CI collected the
results.
"""
import glob, sys, xml.etree.ElementTree as ET
from collections import Counter

host = sys.argv[1] if len(sys.argv) > 1 else "Linux"
root = sys.argv[2] if len(sys.argv) > 2 else "."
total = skipped = failed = 0
per, per_skip = Counter(), Counter()
for path in glob.glob(f"{root}/*/build/test-results/**/*.xml", recursive=True):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    tests = int(suite.get("tests", 0))
    total += tests
    skipped += int(suite.get("skipped", 0))
    failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    module = path[len(root) :].lstrip("/").split("/")[0]
    per[module] += tests
    per_skip[module] += int(suite.get("skipped", 0))

print(f"{total} tests, {skipped} skipped, {failed} failed")
for module in sorted(per):
    print(f"  {module:34}{per[module]:6} tests, {per_skip[module]:4} skipped")

# Which classes skipped, and why. Reading this out of the log is the difference between
# "some tests skipped" and "the Vega-Lite scene gate is not running".
if skipped:
    where, why = Counter(), {}
    for path in glob.glob(f"{root}/*/build/test-results/**/*.xml", recursive=True):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            for skip in case.iter("skipped"):
                name = case.get("classname", "?").rsplit(".", 1)[-1]
                where[name] += 1
                why.setdefault(name, (skip.get("message") or "").strip()[:160])
    print("skipped by class:")
    for name, count in where.most_common():
        print(f"  {name} ({count}): {why[name]}")

problems = []

# Every host runs the JVM suites, so every host owes these. The counts are what this
# workflow observed, less a margin; they are a shrink detector, not a target to grow.
for module, least in (
    ("vega-runtime", 2500),
    ("vega-lite", 800),
    ("vega-expression", 440),
    ("vega-dataflow", 210),
    ("vega-scene", 170),
    ("vega-model", 70),
    ("vega-svg", 25),
    ("vega-loader", 9),
    ("vega-compose-multiplatform", 20),
):
    if per[module] < least:
        problems.append(f"{module} ran {per[module]} tests, expected at least {least}")

# macOS additionally runs the commonTest suites on Kotlin/Native.
if host == "macOS":
    native = sum(
        int(ET.parse(p).getroot().get("tests", 0))
        for p in glob.glob(f"{root}/*/build/test-results/*Arm64Test/**/*.xml", recursive=True)
    )
    if native < 20:
        problems.append(f"only {native} tests ran on Kotlin/Native, expected at least 20")

# The replays guard their own size internally (`replayed >= 60`, `>= 9750`), so a skip here
# means a suite decided its fixtures were absent. Two are legitimately conditional.
if skipped > 10:
    problems.append(f"{skipped} tests skipped; suites are assuming themselves away")

for problem in problems:
    print(f"::error::{problem}" if len(sys.argv) > 3 else f"  {problem}")
if problems:
    sys.exit(1)
print(f"{total} tests ran on {host}, {skipped} skipped, every expected module present.")
