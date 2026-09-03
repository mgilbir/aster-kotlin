#!/usr/bin/env python3
"""`SUPPORTED_FEATURES.md`, rendered from a capability source and the test results that prove it.

The document is what an adopter reads before depending on any of this, and nothing checked it. It
drifted the way an unchecked document always drifts: one row said `isDate`, `isRegExp` and `isTuple`
"stay out with reasons" while all three were implemented, and a row seventy lines below listed
`isRegExp()` as supported — the same file disagreeing with itself and with the code (#154). Thirty-two
registered expression functions were named nowhere at all.

So the table is generated. `docs/capabilities.json` holds what a person knows and a machine cannot
infer — the feature, the prose about known differences, the milestone, the *intent* behind a status,
and which tests are the evidence. The status a reader sees is then computed from whether those tests
**actually ran and passed**, merged across every host, because that is the one part of the claim a
test can settle.

### Why intent still lives in the source

Status is not a boolean a test emits. The vocabulary includes `Not planned`, `Deliberate difference`
and `Superseded` — positions, not outcomes, and no run produces them. What a run settles is narrower
and worth stating exactly: whether a row *claiming* support has evidence behind it. A row that says
`Supported` and cites a test that did not run, or that failed, is the failure this exists to catch.

### Why the results have to be merged

No single job sees everything. macOS compiles every Apple target and runs the Swift suite; Linux runs
`linuxX64`; the instrumented suites need an emulator and run in their own jobs. A document generated
from one host would mark every other host's rows unproven, so `--results` takes as many directories
as there are jobs and the union is what gets rendered.

Usage:

    scripts/capabilities.py --check                     # rendered doc matches the source
    scripts/capabilities.py --write --results a b c     # regenerate from merged results
    scripts/capabilities.py --migrate                   # one-off: build the source from the doc
"""

from __future__ import annotations

import argparse
import glob
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / "SUPPORTED_FEATURES.md"
SOURCE = ROOT / "docs" / "capabilities.json"
COVERAGE_INPUT = ROOT / "vega-model" / "build" / "upstream-coverage.json"
COVERAGE_DOC = ROOT / "docs" / "upstream-coverage.md"

# A test class as a row cites one: backticked, containing `Test`.
CITATION = re.compile(r"`([A-Z][A-Za-z0-9]*Tests?[A-Za-z0-9]*)`")

# Statuses that assert the feature works, and are therefore checkable against a run. Everything else
# — `Not planned`, `Deliberate difference`, `Superseded`, `Not implemented` — is a position rather
# than an outcome, and a run has nothing to say about it.
CLAIMS_SUPPORT = ("supported", "verified", "partial", "exact", "yes")

# Statuses that assert the feature works **completely**, and therefore claim no limitation. Note
# `partial` is missing from this list where it is present in the one above: a `Partial` row makes
# both claims at once, and the two halves are checked by different things.
CLAIMS_EVERYTHING = ("supported", "verified", "exact", "yes", "superseded")


def claims_support(status: str) -> bool:
    plain = status.replace("*", "").strip().lower()
    return any(plain.startswith(word) for word in CLAIMS_SUPPORT)


def claims_limit(status: str) -> bool:
    """Whether [status] tells a reader that something does **not** work.

    The half of a row that nothing used to check, and the half that rots. A run settles whether a
    row claiming support has evidence; nothing settled whether a row claiming a *limitation* still
    had one, so ten rows went on describing gaps the code had closed — `Interval selection` said
    "no drag-to-select gesture yet" while a drag brush ran end to end, `A colour ramp over instants`
    said the runtime could not build a temporal colour scale while `ScaleResolver` routed exactly
    that to `buildSequentialColor`, and `Tooltip content and anchor` said "no tooltip rendering yet"
    against two other rows in the same file saying `VegaChartView` draws one.

    Each of those understated the engine, which is the direction nobody reports: an adopter reads a
    limitation and builds around it, and no bug is ever filed for a feature that works.
    """
    plain = status.replace("*", "").strip().lower()
    if not plain or plain[0].isdigit():
        return False
    return not any(plain.startswith(word) for word in CLAIMS_EVERYTHING)


# --------------------------------------------------------------------------------------- results


def read_results(directories: list[str]) -> dict[str, dict]:
    """Every test class seen in any JUnit or xUnit XML under [directories], merged.

    Keyed by the *simple* class name, because that is what a row cites and because the three
    producers decorate it differently: Gradle's JVM suites give
    `dev.aster.vega.svg.SvgRendererTest`, a Kotlin/Native suite prefixes its target
    (`macosArm64Test.dev.…`), and the instrumented runner gives the bare package path. Merging on
    the simple name is what lets one row's evidence come from four jobs.
    """
    seen: dict[str, dict] = defaultdict(
        lambda: {"ran": 0, "failed": 0, "skipped": 0, "hosts": set(), "methods": set()}
    )
    for directory in directories:
        base = pathlib.Path(directory)
        for path in base.rglob("*.xml"):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                classname = case.get("classname") or ""
                simple = classname.split(".")[-1]
                if not simple:
                    continue
                entry = seen[simple]
                entry["ran"] += 1
                if case.find("failure") is not None or case.find("error") is not None:
                    entry["failed"] += 1
                if case.find("skipped") is not None:
                    entry["skipped"] += 1
                entry["hosts"].add(base.name)
                entry["methods"].add(method_name(case.get("name") or ""))
    return seen


# What each producer hangs off a method name: `()`, then a target in brackets for a Kotlin/Native or
# multiplatform suite — `a drag selects nothing()[jvm]`. Stripped so one citation matches every host.
METHOD_DECORATION = re.compile(r"\(\)(\[[^\]]*\])?$")


def method_name(raw: str) -> str:
    return METHOD_DECORATION.sub("", raw.strip()).strip()


def status_of(entry: dict, results: dict[str, dict]) -> tuple[str, str]:
    """The status a reader sees, and the evidence line under it.

    Returns the declared status unchanged where the row does not claim support — there is nothing
    for a run to settle about `Not planned`.
    """
    declared = entry["status"]
    cited = entry["tests"]
    if not claims_support(declared) or not cited:
        return declared, ""

    ran = failed = 0
    missing = []
    hosts: set[str] = set()
    for name in cited:
        found = results.get(name)
        if not found:
            missing.append(name)
            continue
        ran += found["ran"]
        failed += found["failed"]
        hosts |= found["hosts"]

    # **Only the bad news is written into a row.** A count of the cited suites' tests would read as
    # the weight of evidence behind this row and it is not: a row citing `FixtureDifferentialTest`
    # for two named fixtures would claim the suite's 1181 cases, which overstates by three orders of
    # magnitude. What a run can honestly say about a healthy row is "the evidence ran and passed",
    # and `Supported` already says that. So a healthy row is left exactly as authored, and the
    # generated signal is reserved for rows where the claim is not backed.
    if failed:
        return f"**Failing** — {failed} test(s) in the cited suites", ""
    if missing:
        which = ", ".join(f"`{name}`" for name in sorted(missing)[:3])
        more = f" and {len(missing) - 3} more" if len(missing) > 3 else ""
        return declared, f"unproven here: {which}{more} did not run"
    return declared, ""


# --------------------------------------------------------------------------------- doc round trip


def split_row(line: str) -> list[str] | None:
    """A table row's cells, splitting on `|` **only outside backticks**.

    A plain `line.split("|")` is wrong here and was wrong silently. One row documents indirect scale
    references and writes them as `` `{"signal"|"parent"|"datum"|"group"}` `` — literal pipes inside
    a code span, which markdown renders as one cell and a naive split reads as four. That row's
    status came out as `"parent"`, which is how it appeared in the status vocabulary at all.
    """
    if not line.startswith("|"):
        return None
    cells: list[str] = []
    current: list[str] = []
    in_code = False
    for char in line[1:]:
        if char == "`":
            in_code = not in_code
            current.append(char)
        elif char == "|" and not in_code:
            cells.append("".join(current))
            current = []
        else:
            current.append(char)
    # Whatever trails the final `|` is the line ending, not a cell.
    return cells if len(cells) >= 2 else None


def migrate() -> None:
    """Build `docs/capabilities.json` out of the document, once, preserving every cell verbatim."""
    entries = []
    section = ""
    for number, line in enumerate(DOC.read_text().split("\n"), 1):
        if line.startswith("## "):
            section = line[3:].strip()
        cells = split_row(line)
        if not cells or len(cells) < 5:
            continue
        if set("".join(cells).strip()) <= set("- "):
            continue  # the |---|---| separator
        if cells[1].strip().lower() == "status":
            continue  # a header row
        entries.append(
            {
                "line": number,
                "section": section,
                "feature": cells[0].strip(),
                "status": cells[1].strip(),
                "tests": sorted(set(CITATION.findall(cells[2]))),
                "tests_text": cells[2].strip(),
                "notes": cells[3].strip(),
                "milestone": cells[4].strip(),
            }
        )
    SOURCE.parent.mkdir(parents=True, exist_ok=True)
    SOURCE.write_text(json.dumps({"capabilities": entries}, indent=2, ensure_ascii=False) + "\n")
    print(f"migrated {len(entries)} capabilities into {SOURCE.relative_to(ROOT)}")


def render(results: dict[str, dict]) -> str:
    """The document, with every row's Status recomputed from [results].

    **With no results at all, statuses are left as declared**, and that is not a shortcut — it is
    what makes the check runnable off CI. A run this document can be generated from is the union of
    four jobs, and no laptop has all four: without an emulator and without Linux, generating would
    mark two hundred rows unproven and the diff would be noise about the machine rather than about
    the code. So an empty result set checks the half that is machine-independent — that the source
    still reproduces every row's structure and prose — and CI, which has all four, checks the rest.
    """
    if not results:
        return render_with(lambda entry: (entry["status"], ""))
    return render_with(lambda entry: status_of(entry, results))


def render_with(status_for) -> str:
    """The document, rebuilt from the source row by row.

    **Every cell is written from the source, not just the status.** The first version replaced the
    status cell and passed the rest of the line through, which meant the document could drift from
    `docs/capabilities.json` in the prose — the 297KB that is the whole reason a source exists — and
    the check would report a match. Calling that "the document is an output" was overstating it by
    four columns out of five. Rebuilding the row is what makes the claim true.
    """
    source = json.loads(SOURCE.read_text())
    by_line = {entry["line"]: entry for entry in source["capabilities"]}
    out = []
    for number, line in enumerate(DOC.read_text().split("\n"), 1):
        entry = by_line.get(number)
        if entry is None:
            out.append(line)
            continue
        status, evidence = status_for(entry)
        decorated = f"{status}<br/><sub>{evidence}</sub>" if evidence else status
        out.append(
            "| "
            + " | ".join(
                [
                    entry["feature"],
                    decorated,
                    entry["tests_text"],
                    entry["notes"],
                    entry["milestone"],
                ]
            )
            + " |"
        )
    return "\n".join(out)


FUNCTION_SOURCES = [
    ROOT / "vega-expression/src/main/kotlin/dev/aster/vega/expression/Functions.kt",
    ROOT / "vega-expression/src/main/kotlin/dev/aster/vega/expression/Evaluator.kt",
]
REGISTRATION = re.compile(
    r'map\.predicate\("([a-zA-Z_][a-zA-Z0-9_]*)"\)'
    r'|map\["([a-zA-Z_][a-zA-Z0-9_]*)"\]'
    r'|name == "([a-zA-Z_][a-zA-Z0-9_]*)"'
)


def undocumented_functions(text: str) -> list[str]:
    """Expression functions the engine registers and the document never names.

    The other direction of drift, and the one an adopter cannot work around: a function that ships
    unmentioned cannot be discovered from the document at all. Thirty-two were in this state when
    the check was written — `substring`, `isArray`, `toNumber`, `vlSelectionTest` among them, with
    literally no occurrence in the file.

    Generation alone does not catch this, because the rows are authored from a list of features
    somebody thought of. This reads the registry instead, so the document is answerable to the code
    and not only to itself.
    """
    registered = set()
    for source in FUNCTION_SOURCES:
        if not source.exists():
            raise SystemExit(f"::error::{source.relative_to(ROOT)} is gone; this gate reads it")
        for match in REGISTRATION.finditer(source.read_text()):
            registered.add(next(group for group in match.groups() if group))
    return sorted(name for name in registered if not re.search(rf"`{re.escape(name)}[(`]", text))


GENERATED_EVIDENCE = re.compile(r"<br/><sub>[^<]*</sub>")


def without_evidence(text: str) -> str:
    """The document with the generated status decoration removed.

    **This is what makes the offline check possible at all, and it was missing.** CI renders the
    status from merged results and writes an `unproven here: …` note under any row whose evidence
    did not run. The offline check renders with statuses as declared and writes no note — so the
    committed document could never equal what `--check` produced, and the `capabilities` gate went
    red on `main` the moment CI committed its first regeneration.

    Stripping the note from both sides is the fix rather than teaching `--check` to guess at
    results: what the offline half is *for* is the machine-independent claim — that the source still
    reproduces every row's structure, prose and declared status. Whether a suite ran on some host is
    precisely the part a laptop cannot know, and comparing it here is what made the gate wrong.
    """
    return GENERATED_EVIDENCE.sub("", text)


def render_coverage() -> str | None:
    """`docs/upstream-coverage.md`, from what `UpstreamPropertyCoverageTest` measured.

    How many of upstream's own documented properties this engine consumes, per kind, asked of the
    parser rather than counted from a list. It exists because the prose answer was wrong by fifty in
    one direction — `SUPPORTED_FEATURES.md` said the axis had "forty-odd properties this engine does
    not honour" when the number is zero.

    Returns None when the measurement is absent, which is any checkout where the Gradle gate has not
    run. The document is left alone in that case rather than emptied.
    """
    if not COVERAGE_INPUT.exists():
        return None
    kinds = json.loads(COVERAGE_INPUT.read_text())["kinds"]
    total = sum(k["upstream"] for k in kinds)
    consumed = sum(k["consumed"] for k in kinds)
    lines = [
        "# Upstream property coverage",
        "",
        "Generated by `scripts/capabilities.py` from what `UpstreamPropertyCoverageTest` measured.",
        "Do not edit: run the Gradle gate and regenerate.",
        "",
        "How many of the properties **upstream's own schema** documents this engine consumes, asked",
        "of the parser one property at a time. A property that draws no `PARSE_UNKNOWN_PROPERTY` is",
        "one the parser read.",
        "",
        "What this measures is whether a property *name* is recognised for some plausible value. It",
        "is not a claim that the property is honoured correctly — the differential corpus answers",
        "that, by comparing whole scenes against upstream.",
        "",
        "| Kind | Consumed | Upstream | Not consumed |",
        "| --- | --- | --- | --- |",
    ]
    for k in kinds:
        missing = ", ".join(f"`{p}`" for p in k["unconsumed"]) or "—"
        lines.append(f"| `{k['kind']}` | {k['consumed']} | {k['upstream']} | {missing} |")
    lines += ["", f"**{consumed} of {total}** across the kinds measured.", ""]
    return "\n".join(lines)

def unpinned_limits(
    results: dict[str, dict],
    entries: list[dict] | None = None,
) -> list[str]:
    """Rows that tell a reader something does not work, without a test that says so.

    **Not yet wired into [main].** It is switched on at the top of the stack that pins the existing
    rows, because a gate that lands red is a gate somebody turns off. Until then this is callable
    and tested, and every row it would flag is being worked through one group at a time.


    **The rule.** A row claiming a limitation names a `limit.test` — one test method, as
    `ClassName.the method's own name` — whose job is to *assert the limitation*. Not to demonstrate
    the feature: to fail the day the gap closes. `Interval selection` claiming no drag-to-select
    gesture has to be backed by a test that drags and finds nothing, so that implementing the drag
    turns the suite red and whoever implemented it has to come back here and say so.

    That is the same shape as every other gate in this repository, and it is here for the same
    reason: the status column has been derived from a run since #154, and the *prose beside it* has
    not, so the prose is where the drift went. Ten rows were found describing gaps the code had
    closed, every one of them understating what the engine does.

    **Two things checked, and the second needs a run.** That the row names a test at all — which
    holds offline, so the shape of the source is checkable without building anything. And that the
    method actually ran, which is what stops a citation pointing at a method somebody renamed.
    A method that *failed* is not reported here: the ordinary test run already fails on it, and it
    failing is precisely the signal this exists to produce.

    **The escape hatch, deliberately narrow.** `limit.scope` stands where there is no behaviour to
    test because the row states what the project does not build — a WebView backend, an NDK
    renderer. It carries a reason and is listed on every run, because an escape hatch nobody counts
    becomes the default answer.
    """
    problems: list[str] = []
    scoped: list[str] = []
    if entries is None:
        entries = json.loads(SOURCE.read_text())["capabilities"]
    for entry in entries:
        if not claims_limit(entry["status"]):
            continue
        where = f"{entry['section']} / {entry['feature']}"
        limit = entry.get("limit")
        if not limit:
            problems.append(
                f"{where} is `{entry['status']}` but pins its limitation to nothing. Add "
                '`"limit": {"test": "SomeTest.the method", "claim": "…"}` naming a test that '
                "asserts what does not work, so closing the gap turns the suite red — or "
                '`"limit": {"scope": "…"}` where there is no code path to test.'
            )
            continue
        if limit.get("scope"):
            scoped.append(f"{where}: {limit['scope']}")
            continue
        citation = limit.get("test", "")
        class_name, dot, method = citation.partition(".")
        if not dot or not method.strip():
            problems.append(
                f"{where} pins its limitation to `{citation}`, which is not `ClassName.the method`. "
                "A class alone does not say which assertion holds the limitation."
            )
            continue
        found = results.get(class_name)
        if results and not found:
            problems.append(
                f"{where} pins its limitation to `{citation}`, and `{class_name}` ran nowhere in "
                "this run, so nothing checked that the limitation is still real."
            )
        elif found and method not in found["methods"]:
            problems.append(
                f"{where} pins its limitation to `{citation}`, and `{class_name}` ran without a "
                f"method called `{method}`. It was renamed or removed; the row is now unpinned."
            )
    if scoped:
        print(f"{len(scoped)} limitation(s) stand on scope rather than a test:")
        for line in sorted(scoped):
            print(f"  {line}")
    return problems


DOC_REFERENCE = re.compile(
    r"`([A-Za-z0-9_./-]+\.(?:md|kt|kts|json|sh|py|swift|toml|yml))`"
    r"|\]\(([A-Za-z0-9_./-]+\.md)\)"
    r"|(?<![`/\w])([A-Z][A-Za-z0-9_-]*\.md)(?![`\w])"
)


def dangling_references() -> list[str]:
    """Files the documentation names that exist nowhere in the repository.

    `PROJECT_BRIEF.md` was deleted and six documents went on naming it, including four of the very
    records written to replace it. `STATUS.md` kept a live pointer — "the note in HANDOFF.md on
    `donut-chart-labelled` is still the interesting case" — to a file that had gone, and named a
    fixture that was never bundled.

    Resolution is by **basename anywhere in the tree**, deliberately. Prose says `Dataflow.kt` and
    `check.sh` without a path all the time and that is fine; what is not fine is naming something a
    reader will go looking for and not find. Narrowing the check to that is what keeps it quiet
    enough to stay on.
    """
    tracked = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout.split()
    names = {pathlib.PurePath(path).name for path in tracked}
    # **Only the documents that describe the present.**
    #
    # A record of the past may name what was there at the time, and rewriting it to erase a since
    # deleted file would falsify it: `docs/audits/codebase-audit-2026-08-27.md` audited `HANDOFF.md`
    # and saying so is the record's whole value. `CHANGELOG.md` and `STATUS.md` are the same kind of
    # thing — a release history and a running log — and their mentions are narrative, not pointers.
    #
    # What must not dangle is a document a reader consults to find out how things *are*. Those are
    # the ones below.
    documents = [ROOT / name for name in ("README.md", "CONTRIBUTING.md", "SUPPORTED_FEATURES.md",
                                          "THIRD-PARTY-NOTICES.md")]
    documents += sorted((ROOT / "docs" / "adr").rglob("*.md"))
    documents += [ROOT / "docs" / "upstream-coverage.md"]
    problems = []
    for document in documents:
        if not document.exists():
            continue
        seen = set()
        for match in DOC_REFERENCE.finditer(document.read_text()):
            reference = next(g for g in match.groups() if g)
            if reference in tracked or pathlib.PurePath(reference).name in names:
                continue
            if reference in seen:
                continue
            seen.add(reference)
            problems.append(
                f"{document.relative_to(ROOT)} names `{reference}`, which is not in this "
                "repository. Reword it or restore the file: a reader who goes looking finds nothing."
            )
    return problems


def selftest() -> int:
    """Exercises the limitation rule on constructed rows, before it is wired into anything.

    The rule exists to stop a claimed limitation going unchecked, and it lands one stack ahead of
    the enforcement so the rows can be pinned a group at a time without a red gate sitting on
    `main`. That gap is exactly where a rule quietly stops working, so it is checked from the day it
    arrives rather than the day it is switched on — the thing this whole mechanism is about is a
    gate that cannot fail, and an unwired, untested rule is one.

    Constructed rows rather than the real source, so this says what the rule *does* and not what the
    document currently happens to contain.
    """
    failures: list[str] = []

    def check(what: str, got, want) -> None:
        if got != want:
            failures.append(f"{what}: expected {want!r}, got {got!r}")

    # Which statuses claim a limitation at all. The vocabulary is open — a row may say
    # `**Supported**, with a scheduler` — so this is a prefix rule and the boundary cases matter.
    for status in ("Partial", "**Partial**", "Not planned", "Not implemented", "Planned",
                   "Known difference", "Deliberate difference", "Not compared",
                   "Consumed, deliberately inert", "Partial — **not verified against upstream**"):
        check(f"claims_limit({status!r})", claims_limit(status), True)
    for status in ("Supported", "**Supported**", "**Verified**", "**Exact**", "**Yes**",
                   "Superseded", "**Supported**, with a scheduler", "**188**, past the target",
                   "26"):
        check(f"claims_limit({status!r})", claims_limit(status), False)

    row = lambda **kw: {"section": "S", "feature": "F", "status": "Partial", **kw}
    ran = {"T": {"ran": 1, "failed": 0, "skipped": 0, "hosts": {"jvm"}, "methods": {"the gap holds"}}}

    # No pin at all.
    check("unpinned", len(unpinned_limits({}, [row()])), 1)
    # A pin naming a method that ran.
    check("pinned and run",
          unpinned_limits(ran, [row(limit={"test": "T.the gap holds"})]), [])
    # A pin naming a class that ran without that method — renamed or removed.
    check("method renamed",
          len(unpinned_limits(ran, [row(limit={"test": "T.a method nobody wrote"})])), 1)
    # A pin naming a class that ran nowhere.
    check("class absent",
          len(unpinned_limits(ran, [row(limit={"test": "Absent.the gap holds"})])), 1)
    # A class with no method is not a pin: it does not say which assertion holds the limitation.
    # The *message* is asserted, not just the count — treating it as a renamed method would report
    # one problem too, and would send a reader looking for a method rather than writing a citation.
    class_only = unpinned_limits(ran, [row(limit={"test": "T"})])
    check("class only count", len(class_only), 1)
    check("class only reason", "not `ClassName.the method`" in class_only[0], True)
    empty_method = unpinned_limits(ran, [row(limit={"test": "T."})])
    check("trailing dot", len(empty_method), 1)
    check("trailing dot reason", "not `ClassName.the method`" in empty_method[0], True)
    # Scope is accepted and counted.
    check("scope", unpinned_limits(ran, [row(limit={"scope": "no code path"})]), [])
    # A row that claims no limitation needs no pin.
    check("supported row", unpinned_limits(ran, [row(status="**Supported**")]), [])
    # With no results at all the offline half still holds: a missing pin is still a missing pin,
    # and a present one is not judged against a run that did not happen.
    check("offline, unpinned", len(unpinned_limits({}, [row()])), 1)
    check("offline, pinned", unpinned_limits({}, [row(limit={"test": "T.the gap holds"})]), [])

    # The decoration each producer hangs off a method name.
    check("method_name jvm", method_name("the gap holds()[jvm]"), "the gap holds")
    check("method_name native", method_name("the gap holds()[macosArm64]"), "the gap holds")
    check("method_name bare", method_name("the gap holds()"), "the gap holds")
    check("method_name plain", method_name("the gap holds"), "the gap holds")

    for problem in failures:
        print(f"::error::selftest: {problem}")
    if failures:
        return 1
    print("capabilities.py: the limitation rule behaves as documented")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--migrate", action="store_true")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--results", nargs="*", default=[])
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    if args.migrate:
        migrate()
        return 0

    results = read_results(args.results) if args.results else {}
    rendered = render(results)

    # The coverage document, handled in both directions before the early return below.
    coverage = render_coverage()
    coverage_stale = False
    if coverage is not None:
        if args.write:
            COVERAGE_DOC.parent.mkdir(parents=True, exist_ok=True)
            COVERAGE_DOC.write_text(coverage)
            print(f"wrote {COVERAGE_DOC.relative_to(ROOT)}")
        elif not COVERAGE_DOC.exists() or COVERAGE_DOC.read_text() != coverage:
            coverage_stale = True

    if args.write:
        DOC.write_text(rendered)
        print(f"wrote {DOC.relative_to(ROOT)} from {len(results)} test classes")
        return 0
    problems = []
    if coverage_stale:
        problems.append(
            "docs/upstream-coverage.md is not what UpstreamPropertyCoverageTest measured; "
            "regenerate with scripts/capabilities.py --write"
        )
    committed = DOC.read_text()
    # With no results, the run-dependent decoration is not comparable; see `without_evidence`.
    if not results:
        rendered, committed = without_evidence(rendered), without_evidence(committed)
    if rendered != committed:
        problems.append(
            "SUPPORTED_FEATURES.md is not what docs/capabilities.json renders. Edit the source and "
            "regenerate; the document is an output."
        )
    problems.extend(dangling_references())

    for name in undocumented_functions(DOC.read_text()):
        problems.append(
            f"the expression function `{name}` is registered and named nowhere in "
            "SUPPORTED_FEATURES.md, so an adopter cannot discover it."
        )
    for problem in problems:
        print(f"::error::{problem}")
    if problems:
        return 1
    print("SUPPORTED_FEATURES.md matches its source, and every registered function is named.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
