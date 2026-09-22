#!/usr/bin/env bash
# The **signal** sweep: what a chart becomes after something is written into it.
#
# Every other corpus here compares a chart **as first drawn**. The fixtures under
# `test-fixtures/specs`, Vega-Lite's gallery, the 1981 wild specifications, both schema sweeps and
# the value sweep all build a chart, draw it once and compare what came out. Not one of them ever
# changes something and looks again — so half of what this engine does has never been compared with
# anything.
#
# `VegaChartController.setSignal` pins a value, cascades it through every signal sourced on it and
# compiles the specification again. That is what a slider, a dropdown or a fired handler does.
# `SignalInputTest` asserts by hand what it should produce; nothing had asked upstream what it does.
#
# One chart per **thing a signal can reach** — a scale's domain, a scale's range, a mark's own
# property, an axis's tick count, a title's words, a transform's parameter, and a signal *derived*
# from the one being written, which tests the cascade rather than the write. The values are
# deliberately not all sensible, for the reason the value sweep exists: a binding is a door a host
# writes through, and what arrives is whatever its control produced.
#
# Upstream renders, sets the signal and renders **again**; the second render is what is compared.
# See `oracle-js/src/signal-sweep.js` and `SignalSweepTest`.
#
# **A measurement, not a gate**, as every sweep here is. `scripts/check.sh` does not call it; run it
# when you want the number.
#
#   scripts/signal-sweep.sh                     generate, render with upstream, then compare
#   scripts/signal-sweep.sh --references-only   generate and render with upstream, then stop
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
SWEEP="$ROOT/build/signal-sweep"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

VERSION="$(node -p "require('./oracle-js/node_modules/vega/package.json').version")"
echo "==> Vega $VERSION (from the installed package), sweeping what a written signal reaches"

(cd oracle-js && node src/signal-sweep.js "$SWEEP")

if [[ $references_only == true ]]; then
  echo "Wrote references to $SWEEP/reference."
  echo "The comparison itself is SignalSweepTest."
  exit 0
fi

echo "==> Comparing with the Kotlin runtime"
# `--rerun`, for the reason the value sweep gives: the charts are written into `build/` and are not
# an input Gradle knows about, so without it a second run is served from the cache and the tally
# printed is the previous run's.
#
# `|| true`: this is a measurement, so a difference is the output rather than a failure.
./gradlew --console=plain :vega-runtime:jvmTest --tests '*SignalSweepTest*' --rerun -i \
  > "$ROOT/build/signal-sweep.log" 2>&1 || true
report="$(sed -n '/==== signal sweep ====/,/==== end ====/p' "$ROOT/build/signal-sweep.log")"
if [[ -z $report ]]; then
  echo "The comparison produced no report — the test did not run, or it was skipped. Details:" >&2
  echo "  $ROOT/build/signal-sweep.log" >&2
  exit 1
fi
printf '%s\n' "$report"
