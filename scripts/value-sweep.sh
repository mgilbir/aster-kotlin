#!/usr/bin/env bash
# The **value** sweep: one chart per (chart, column) pair, varying what the data holds.
#
# Every other corpus here varies the **specification** and holds the data still: the fixtures under
# `test-fixtures/specs` are charts somebody drew, the schema sweep (`scripts/property-sweep.sh`) is
# one chart with one property changed, the Vega-Lite sweep is one encoding changed, and the gallery
# and wild corpora are charts from the world. The data in all of them is tidy — numbers where numbers
# go and words where words go — so none of them reaches a value nobody meant to put there.
#
# Real data is not tidy, and that is where the differences have been: `+""` is 0 where `+"null"` is
# NaN, `quantize` does not coerce at all, a stack's groups are keyed by `JSON.stringify`, an axis
# joins its ticks by value. Not one of those was reachable by changing a property.
#
# So this holds the specification still and varies the **column**. The columns are reasons rather
# than a random spread — a null beside the word for it, an empty cell, a flag, a number beside its
# own text, a negative zero, a value past where integers stop being exact, the notation thresholds,
# hexadecimal and exponent text, a list inside a cell, letters outside the Latin block, a word far
# wider than its neighbours. The charts are **journeys** rather than a gallery: one per scale family
# (band, point, linear, log, pow, symlog, time, ordinal, quantize, quantile, threshold), one per
# guide that reads a value (a discrete legend, a gradient legend, an axis), and one per transform
# that accumulates or orders or formats one (stack, window, pie, aggregate, bin, collect, format).
# See `oracle-js/src/value-sweep.js`.
#
# A specification upstream refuses to render is recorded as a refusal rather than dropped: "upstream
# will not draw this either" is an agreement.
#
# **A measurement, not a gate**, the same course the gallery and Deneb sweeps took: a sweep of a
# surface nobody has finished porting would paint every branch red for reasons unconnected to it.
# `scripts/check.sh` does not call this; run it when you want the number.
#
#   scripts/value-sweep.sh                     generate, render with upstream, then compare
#   scripts/value-sweep.sh --references-only   generate and render with upstream, then stop
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
SWEEP="$ROOT/build/value-sweep"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

VERSION="$(node -p "require('./oracle-js/node_modules/vega/package.json').version")"
echo "==> Vega $VERSION (from the installed package), sweeping what a column can hold"

# The generator renders as it goes, in one process: 600 charts is 600 node start-ups otherwise, and
# the determinism pin is re-applied per chart so each one draws from the same seed rather than
# continuing the last one's sequence.
(cd oracle-js && node src/value-sweep.js "$SWEEP")

if [[ $references_only == true ]]; then
  echo "Wrote references to $SWEEP/reference."
  echo "The comparison itself is ValueSweepTest."
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
./gradlew --console=plain :vega-runtime:jvmTest --tests '*ValueSweepTest*' --rerun -i \
  > "$ROOT/build/value-sweep.log" 2>&1 || true
report="$(sed -n '/==== value sweep ====/,/==== end ====/p' "$ROOT/build/value-sweep.log")"
# Checked for **content** rather than for sed's exit code, which is zero when it matches nothing.
if [[ -z $report ]]; then
  echo "The comparison produced no report — the test did not run, or it was skipped. Details:" >&2
  echo "  $ROOT/build/value-sweep.log" >&2
  exit 1
fi
printf '%s\n' "$report"
