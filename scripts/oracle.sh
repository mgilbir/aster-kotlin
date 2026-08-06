#!/usr/bin/env bash
# Differential tests against upstream Vega.
#
# 1. install pinned Node dependencies with `npm ci`
# 2. run each fixture through upstream Vega
# 3. export reference scene data and SVG
# 4. canonicalize the output
# 5. compare it with the Kotlin output
# 6. write readable differences under build/oracle-diffs
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT="$PWD"
DIFF_DIR="$ROOT/build/oracle-diffs"
REFERENCE_DIR="$ROOT/build/oracle-reference"
FIXTURES="$ROOT/test-fixtures/specs"

mkdir -p "$DIFF_DIR" "$REFERENCE_DIR"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci)

echo "==> Rendering fixtures with upstream Vega"
shopt -s nullglob
specs=("$FIXTURES"/*.vg.json)
if [[ ${#specs[@]} -eq 0 ]]; then
  echo "No fixtures in $FIXTURES" >&2
  exit 1
fi

for spec in "${specs[@]}"; do
  name="$(basename "$spec" .vg.json)"
  (cd oracle-js && node src/render.js "$spec" "$REFERENCE_DIR/$name")
done

echo "==> Comparing with the Kotlin runtime"
# The Kotlin side cannot consume a Vega specification until Milestone 3, so there is nothing to
# compare yet. Failing loudly is deliberate: a script that exits 0 here would report passing
# differential tests that never ran.
cat >&2 <<'MSG'

Reference output is in build/oracle-reference.

The Kotlin comparison step is not wired up yet: specification parsing arrives in Milestone 3
(PROJECT_BRIEF.md 20). Until then this script produces reference data only and exits non-zero so it
cannot be mistaken for a passing differential test run.
MSG
exit 3
