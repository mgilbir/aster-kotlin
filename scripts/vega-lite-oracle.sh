#!/usr/bin/env bash
# Differential tests for the Vega-Lite compiler, against upstream Vega-Lite.
#
# Two references per fixture, because there are two ways to be wrong:
#
#   1. the Vega specification upstream compiles the fixture into — compared property by property, so
#      a defaulting rule that drifts is caught at the rule
#   2. the scene upstream then renders from it — compared mark by mark, so a specification that is
#      differently phrased but draws the same chart still passes, and one that is identically phrased
#      but draws the wrong chart still fails
#
# Both are checked in, so the JVM tests need neither Node nor a network. This regenerates them;
# review the resulting diff the way you would review a golden.
set -euo pipefail
cd "$(dirname "$0")/.."

# Same zone as the Vega oracle and the JVM tests: a time scale is local, so a reference generated in
# another zone means something else.
export TZ="Europe/Amsterdam"

ROOT="$PWD"
FIXTURES="$ROOT/test-fixtures/vega-lite"
REFERENCE_DIR="$ROOT/test-fixtures/vega-lite-reference"
# Directly under test-fixtures, because the oracle's loader resolves a fixture's `data/x.json`
# against the spec's own directory and then the one above it — so a compiled spec has to sit one
# level under the corpus root to see the same bytes the Vega fixtures read.
COMPILED_DIR="$ROOT/test-fixtures/vega-lite-compiled"

mkdir -p "$REFERENCE_DIR" "$COMPILED_DIR"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund)

shopt -s nullglob
specs=("$FIXTURES"/*.vl.json)
if [[ ${#specs[@]} -eq 0 ]]; then
  echo "No Vega-Lite fixtures in $FIXTURES" >&2
  exit 1
fi

# A fixture may name its data the way Vega-Lite's own examples do, by relative URL; the bytes are the
# ones the Vega fixtures already use, so both engines read the same file.
echo "==> Checking fixture datasets"
(cd oracle-js && node src/fetch-data.js "$ROOT/test-fixtures")

echo "==> Compiling ${#specs[@]} fixture(s) with upstream Vega-Lite"
for spec in "${specs[@]}"; do
  name="$(basename "$spec" .vl.json)"
  (cd oracle-js && node src/vega-lite-reference.js "$spec" "$REFERENCE_DIR/$name.vega.json")
  cp "$REFERENCE_DIR/$name.vega.json" "$COMPILED_DIR/$name.vg.json"
  # The scene reference, rendered from upstream's own output by the same script the Vega fixtures
  # use, so the two comparisons stay in step.
  (cd oracle-js && node src/reference.js "$COMPILED_DIR/$name.vg.json" "$REFERENCE_DIR/$name.reference.json")
done

echo "==> Comparing with the Kotlin compiler"
if ./gradlew --console=plain :vega-lite:test > "$ROOT/build/vega-lite-differential.log" 2>&1; then
  echo "Vega-Lite differential tests passed for ${#specs[@]} fixture(s)."
  echo "References: $REFERENCE_DIR"
else
  echo "Vega-Lite differential tests FAILED. Details:" >&2
  echo "  $ROOT/build/vega-lite-differential.log" >&2
  echo "  HTML report: $ROOT/vega-lite/build/reports/tests/test/index.html" >&2
  grep -A 40 "AssertionFailedError" "$ROOT/build/vega-lite-differential.log" | head -80 >&2 || true
  exit 1
fi
