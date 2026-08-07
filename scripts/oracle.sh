#!/usr/bin/env bash
# Differential tests against upstream Vega.
#
# 1. install pinned Node dependencies with `npm ci`
# 2. run each fixture through upstream Vega
# 3. export reference scene data and SVG
# 4. canonicalize the output
# 5. compare it with the Kotlin output
# 6. write readable differences under build/oracle-diffs
#
# The reference files under test-fixtures/reference/ are checked in, so the JVM differential tests run
# without Node or a network. This script regenerates them; review the resulting diff the way you would
# review a golden, because a change here means either the port or upstream moved.
set -euo pipefail
cd "$(dirname "$0")/.."

# A `time` scale is local, so upstream's output depends on the machine's zone. Pinned to the same
# zone the JVM tests use (build.gradle.kts), so a checked-in reference means the same thing on any
# machine — and so a fixture can cross a daylight-saving boundary on purpose.
export TZ="Europe/Amsterdam"

ROOT="$PWD"
DIFF_DIR="$ROOT/build/oracle-diffs"
REFERENCE_DIR="$ROOT/test-fixtures/reference"
SCENE_DIR="$ROOT/build/oracle-reference"
FIXTURES="$ROOT/test-fixtures/specs"

mkdir -p "$DIFF_DIR" "$REFERENCE_DIR" "$SCENE_DIR"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund)

shopt -s nullglob
specs=("$FIXTURES"/*.vg.json)
if [[ ${#specs[@]} -eq 0 ]]; then
  echo "No fixtures in $FIXTURES" >&2
  exit 1
fi

echo "==> Rendering ${#specs[@]} fixture(s) with upstream Vega"
for spec in "${specs[@]}"; do
  name="$(basename "$spec" .vg.json)"
  # The comparison reference the JVM tests read.
  (cd oracle-js && node src/reference.js "$spec" "$REFERENCE_DIR/$name.reference.json")
  # Human-readable scene summary and SVG, for eyeballing a disagreement.
  (cd oracle-js && node src/render.js "$spec" "$SCENE_DIR/$name")
done

echo "==> Comparing with the Kotlin runtime"
if ./gradlew --console=plain :vega-runtime:test --tests '*Differential*' > "$DIFF_DIR/differential.log" 2>&1; then
  echo "Differential tests passed for ${#specs[@]} fixture(s)."
  echo "Reference data: $REFERENCE_DIR"
  echo "Upstream scenes and SVG: $SCENE_DIR"
else
  echo "Differential tests FAILED. Details:" >&2
  echo "  $DIFF_DIR/differential.log" >&2
  echo "  HTML report: $ROOT/vega-runtime/build/reports/tests/test/index.html" >&2
  # Surface the assertion text directly; the log is long and the useful part is the diff list.
  grep -A 30 "AssertionFailedError" "$DIFF_DIR/differential.log" | head -60 >&2 || true
  exit 1
fi
