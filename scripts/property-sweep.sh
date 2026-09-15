#!/usr/bin/env bash
# The **schema** sweep: one chart per property value Vega's own schema declares.
#
# Every other corpus here is a collection of charts somebody drew — the 200 fixtures under
# `test-fixtures/specs`, Vega-Lite's 627 examples (`scripts/vega-lite-gallery.sh`), 1981
# specifications from GitHub (`scripts/vega-lite-wild.sh`), 63 Deneb templates (`scripts/deneb.sh`).
# All four agree with upstream on everything they cover, which is the point at which a corpus of
# *used* features stops being able to find anything. What none of them reaches is a property nobody
# happened to set, or a value of it nobody happened to choose: `tickBand: "extent"`,
# `labelOverlap: "greedy"`, `align: "all"`, `bandPosition: 0`.
#
# `vega/build/vega-schema.json` is the list of those, and it is machine-readable. This walks it and
# writes one small bar chart per (property, value) pair — the same chart every time with one property
# changed, so a difference names its own cause — renders each with upstream, and compares.
#
# The families are `axis`, `legend`, `title`, one per **scale type**, one per **projection type**, the
# **view** itself, the **layout** of a group of groups, the `config` block behind each guide and each
# mark type, a mark's own properties, and one per **mark type** for the encode
# channels every item carries — the widest declared surface there is. A property is swept where the schema says enough to choose values
# honestly (an enum, a boolean, a number, a colour), or where upstream fixes a vocabulary its schema
# leaves open; anything else is skipped **and counted**, with the reason, in the manifest. A scale
# type and a projection type each bring their own base chart, because which properties mean anything
# is the type's own question. See `oracle-js/src/property-sweep.js`.
#
# **A measurement, not a gate**, the same course the gallery and Deneb sweeps took: a sweep of a
# surface nobody has finished porting would paint every branch red for reasons unconnected to it.
# `scripts/check.sh` does not call this; run it when you want the number.
#
#   scripts/property-sweep.sh                     generate, render with upstream, then compare
#   scripts/property-sweep.sh --references-only   generate and render with upstream, then stop
set -euo pipefail

references_only=false
if [[ ${1:-} == "--references-only" ]]; then
  references_only=true
  shift
fi
cd "$(dirname "$0")/.."

# The same zone as every other oracle here: a time scale is local, and a reference generated in
# another zone means something else.
export TZ="Europe/Amsterdam"

ROOT="$PWD"
SWEEP="$ROOT/build/property-sweep"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

VERSION="$(node -p "require('./oracle-js/node_modules/vega/package.json').version")"
echo "==> Vega $VERSION (from the installed package), sweeping its own schema"

# The generator renders as it goes, in one process: 600 charts is 600 node start-ups otherwise, and
# the determinism pin is re-applied per chart so each one draws from the same seed rather than
# continuing the last one's sequence.
(cd oracle-js && node src/property-sweep.js "$SWEEP")

if [[ $references_only == true ]]; then
  echo "Wrote references to $SWEEP/reference."
  echo "The comparison itself is PropertySweepTest."
  exit 0
fi

echo "==> Comparing with the Kotlin runtime"
# `--rerun`, because the charts are **not** an input Gradle knows about: they are written here, into
# `build/`, and nothing in the test's declared inputs mentions them. Without it a sweep run twice
# gets the second answer from the build cache — the task is reported `FROM-CACHE`, the test never
# executes, no report is written, and the tally printed is the *previous* run's. Caught by a widened
# sweep that printed nothing at all.
#
# `|| true`: this is a measurement, so a difference is the output rather than a failure. The test
# prints the tally and the ranked causes, and writes a line per case to $SWEEP/report.tsv.
./gradlew --console=plain :vega-runtime:jvmTest --tests '*PropertySweepTest*' --rerun -i \
  > "$ROOT/build/property-sweep.log" 2>&1 || true
report="$(sed -n '/==== schema property sweep ====/,/==== end ====/p' "$ROOT/build/property-sweep.log")"
# Checked for **content** rather than for sed's exit code, which is zero when it matches nothing.
if [[ -z $report ]]; then
  echo "The comparison produced no report — the test did not run, or it was skipped. Details:" >&2
  echo "  $ROOT/build/property-sweep.log" >&2
  exit 1
fi
printf '%s\n' "$report"
