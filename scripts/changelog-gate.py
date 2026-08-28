#!/usr/bin/env python3
"""A branch that changes the public surface says so in the changelog.

`changelog-section.sh` checks that a section **exists** for the version being released. It cannot
check that the section is complete, and 0.4.0's was not: entries had been written for two of the
five commits in a stack, so `@InternalAsterVegaApi` — a source-breaking change — and a whole new
`ForeignScale` would have shipped unmentioned. The release page is assembled from that section
verbatim, so an entry nobody writes is a change nobody is told about. It was caught by rewriting a
pull request description, which is not a process.

The surface is already snapshotted for other reasons, so "did the API change" is answerable: if any
of those files moved and `CHANGELOG.md` did not, this fails and says which.

Two ways out, and the second is the honest one for a real case:

  * add the entry — usually right;
  * put `[api-snapshot-only]` in a commit message on the branch, for a snapshot re-recorded when the
    surface did not really change. `scripts/foreign-api.sh` re-attributing an extension to the type
    it extends moved eleven lines and changed nothing a host can call.

Compares the branch as a whole against its merge-base with `origin/main`, uncommitted work included,
so it answers before a commit rather than after one. On `main` there is nothing to compare and it
passes.
"""
import pathlib
import subprocess
import sys

SNAPSHOTS = (
    "android-api.txt",
    "swift/AsterVegaRender/foreign-api.txt",
    "swift/AsterVegaRender/swift-api.txt",
)
SNAPSHOT_SUFFIXES = (".api", ".klib.api")
MARKER = "[api-snapshot-only]"


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=False
    ).stdout.strip()


def is_snapshot(path: str) -> bool:
    return path in SNAPSHOTS or (
        path.startswith("vega-") and path.endswith(SNAPSHOT_SUFFIXES) and "/api/" in path
    )


base = git("merge-base", "HEAD", "origin/main") or git("merge-base", "HEAD", "main")
if not base:
    print("changelog: no origin/main to compare against, so nothing to check")
    sys.exit(0)

if base == git("rev-parse", "HEAD") and not git("status", "--porcelain"):
    print("changelog: on the base commit with nothing changed")
    sys.exit(0)

# Committed on this branch, plus anything still in the working tree.
changed = set(git("diff", "--name-only", base).splitlines())
changed |= {
    line[3:].strip() for line in git("status", "--porcelain").splitlines() if len(line) > 3
}
changed = {path for path in changed if path}

moved = sorted(path for path in changed if is_snapshot(path))
if not moved:
    print("changelog: this branch changes no API snapshot")
    sys.exit(0)

if "CHANGELOG.md" in changed:
    print(f"changelog: {len(moved)} API snapshot(s) changed, and the changelog with them")
    sys.exit(0)

# **The marker exempts the commit that carries it, not the branch.**
#
# It used to be a substring search over every message on the branch, so one `[api-snapshot-only]`
# anywhere — a commit that re-recorded a snapshot for a genuinely cosmetic reason — waved through
# every other snapshot change on the branch, including a real API break in a later commit. That is
# the opposite of what the marker is for: it is a claim about *one* change, made by the person
# making it.
#
# So each snapshot has to be traced to the commits that touched it, and every one of those has to
# carry the marker.
unexplained = []
for path in moved:
    messages = git("log", "--format=%B%x00", f"{base}..HEAD", "--", path).split("\0")
    touching = [m for m in messages if m.strip()]
    if touching and all(MARKER in m for m in touching):
        continue
    unexplained.append(path)

if not unexplained:
    print(f"changelog: {len(moved)} API snapshot(s) changed, each marked {MARKER}")
    sys.exit(0)
moved = unexplained

print("This branch changes what a host compiles against and says nothing in the changelog:")
print()
for path in moved:
    print(f"  {path}")
print()
print("The release page is assembled from CHANGELOG.md verbatim, so an entry nobody writes is a")
print("change nobody is told about — which is how 0.4.0 nearly shipped a source-breaking change")
print("unmentioned. Add the entry.")
print()
print(f"If the surface did not really change — a snapshot re-recorded for another reason — put")
print(f"{MARKER} in the message of **every commit that touches that snapshot**, and say there why.")
print("One marked commit no longer exempts the rest of the branch: it used to, so a cosmetic")
print("re-record in one commit waved through a real break in another.")
sys.exit(1)
