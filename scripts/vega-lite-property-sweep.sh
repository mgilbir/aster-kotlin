#!/usr/bin/env bash
# The **Vega-Lite schema** sweep: one specification per property value Vega-Lite's own schema declares.
#
# The sister of `scripts/property-sweep.sh`, one layer up. That one sweeps what Vega declares and
# compares the scene; this sweeps what Vega-Lite declares and compares the **Vega it compiles into**,
# because that is where a Vega-Lite defect lives. Vega-Lite's whole value is the defaults it supplies
# — a scale type, a stack transform, a tick count, a label angle, a band size — and every one of them
# is a property of the specification it emits. Comparing the emitted Vega names the rule that
# drifted; comparing the picture would say "some marks moved".
#
# The surface is much larger than Vega's: 458 definitions, a `MarkDef` of 88 properties, an
# `Encoding` of 38 channels, a `Config` of 72. The 283 fixtures cover what people draw; this covers
# what the schema says can be written.
#
# **A measurement, not a gate**, the same course the other sweeps took: `scripts/check.sh` does not
# call this. Run it when you want the number.
set -euo pipefail
cd "$(dirname "$0")/.."

# The same zone as every other oracle here: a time scale is local, and a reference generated in
# another zone means something else.
export TZ="Europe/Amsterdam"

ROOT="$PWD"
SWEEP="$ROOT/build/vega-lite-property-sweep"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

VERSION="$(node -p "require('./oracle-js/node_modules/vega-lite/package.json').version")"
echo "==> Vega-Lite $VERSION (from the installed package), sweeping its own schema"

# Generation and compilation in one process: 8000 specifications is 8000 node start-ups otherwise.
(cd oracle-js && node src/vega-lite-property-sweep.js "$SWEEP")

echo "==> Comparing with the Kotlin compiler"
# `--rerun`, for the reason the Vega sweep needs it: the specifications are written into `build/` and
# nothing in the test's declared inputs mentions them, so a second run would report the first run's
# tally from the build cache without executing anything.
#
# `|| true`: this is a measurement, so a difference is the output rather than a failure.
./gradlew --console=plain :vega-lite:jvmTest --tests '*VegaLitePropertySweepTest*' --rerun -i \
  > "$ROOT/build/vega-lite-property-sweep.log" 2>&1 || true
report="$(sed -n '/==== Vega-Lite property sweep ====/,/==== end ====/p' "$ROOT/build/vega-lite-property-sweep.log")"
# Checked for **content** rather than for sed's exit code, which is zero when it matches nothing.
if [[ -z $report ]]; then
  echo "The comparison produced no report — the test did not run, or it was skipped. Details:" >&2
  echo "  $ROOT/build/vega-lite-property-sweep.log" >&2
  exit 1
fi
printf '%s\n' "$report"
