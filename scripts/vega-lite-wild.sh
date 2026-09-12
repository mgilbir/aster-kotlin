#!/usr/bin/env bash
# The **wild** sweep: Vega-Lite specifications written by other people, for their own charts.
#
# The gallery sweep (`scripts/vega-lite-gallery.sh`) compiles the 627 examples Vega-Lite ships. Those
# are excellent and they are also *upstream's own*: written to demonstrate features, by the people
# who built the features, in the version that shipped them. A specification someone wrote to draw a
# real chart is a different distribution — older schema versions, defaults left unstated, features
# combined in ways no example demonstrates, and the occasional thing that was never valid at all.
#
# The corpus is `hyungkwonko/chart-llm`'s `docs/data/chart`: 1981 `.vl.json` files collected from
# public GitHub repositories, which its README calls "the largest set of human-generated charts
# obtained from GitHub to date". MIT licensed, and pinned to a commit rather than to `main`: a corpus
# that moves is a comparison whose result cannot be reproduced.
#
# **That directory and not the whole repository**, which holds 3425 `.vl.json` files in all. The other
# 1444 are in `benchmark/vega-lite` and `benchmark/vega-lite-v4`, and their names give them away —
# `airport_connections.vl.json`, `arc_donut.vl.json`. Those are *Vega-Lite's own examples*, which
# `scripts/vega-lite-gallery.sh` already sweeps at the pinned version rather than at whatever this
# corpus froze. Sweeping them here would double-count the easy half and skew the ranking of what to
# fix toward faults the gallery already reports.
#
# **The claim being tested is agreement, not success.** A specification here may be invalid, may use
# v4 syntax, may name a mark this engine does not implement. The question is never "does it compile"
# but "does upstream's compiler and this one make the *same* thing of it" — including agreeing that
# it cannot be compiled. That is why upstream's refusals are recorded rather than dropped.
#
# **A measurement, not yet a gate**, and deliberately. The gallery sweep started the same way and
# earned its place: it went from 124 of 627 matching to all 627, cause by ranked cause, and only then
# became something a branch has to keep green. Wiring a fresh corpus of somebody else's charts
# straight into `check.sh` would make every branch red for reasons that have nothing to do with it.
# `scripts/check.sh` therefore does not call this; run it when you want the number.
#
#   scripts/vega-lite-wild.sh                 fetch, compile with upstream, then compare
#   scripts/vega-lite-wild.sh --references-only    fetch and compile with upstream, then stop
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
# Pinned. `main` would make this unreproducible, and the corpus is a dataset rather than a dependency
# that wants upgrading.
CORPUS_COMMIT="6e3d3e2bf1c30aa6df7b916289f42a6ed7721a24"
CHECKOUT="$ROOT/build/vega-lite-wild-corpus"
SPECS="$CHECKOUT/docs/data/chart"
REFERENCE_DIR="$ROOT/build/vega-lite-wild"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

VERSION="$(node -p "require('./oracle-js/node_modules/vega-lite/package.json').version")"
echo "==> Vega-Lite $VERSION (from the installed package), corpus at ${CORPUS_COMMIT:0:8}"

# Nothing is checked in, for the reason `vega-lite-gallery.sh` gives: 21 MB of somebody else's test
# data is not this repository's to carry, and a stamped extraction is what stops a rerun either
# re-downloading or silently keeping a previous commit's specifications.
STAMP="$CHECKOUT/.commit"
if [[ -f $STAMP && "$(cat "$STAMP")" == "$CORPUS_COMMIT" && -d $SPECS ]]; then
  echo "==> Reusing $SPECS"
else
  echo "==> Fetching chart-llm at ${CORPUS_COMMIT:0:8}"
  rm -rf "$CHECKOUT"
  mkdir -p "$CHECKOUT"
  curl -fsSL "https://codeload.github.com/hyungkwonko/chart-llm/tar.gz/$CORPUS_COMMIT" \
    | tar -xz -C "$CHECKOUT" --strip-components=1 "chart-llm-$CORPUS_COMMIT/docs/data/chart"
  echo "$CORPUS_COMMIT" > "$STAMP"
fi

count="$(find "$SPECS" -name '*.vl.json' | wc -l | tr -d ' ')"
if [[ $count -lt 1900 ]]; then
  echo "Only $count specification(s) under $SPECS; the corpus layout has moved." >&2
  exit 1
fi

# The **same** upstream compiler the gallery uses, on a different directory. It takes any directory
# of `.vl.json` files, so the wild corpus needed no new code on this side at all.
echo "==> Compiling $count specification(s) with upstream Vega-Lite"
rm -rf "$REFERENCE_DIR"
(cd oracle-js && node src/vega-lite-gallery.js "$SPECS" "$REFERENCE_DIR")

if [[ $references_only == true ]]; then
  echo "Wrote references to $REFERENCE_DIR."
  echo "The comparison itself is VegaLiteWildTest."
  exit 0
fi

echo "==> Comparing with the Kotlin compiler"
# `|| true`: this is a measurement, so a difference is the output rather than a failure. The test
# prints the tally and the ranked causes; read those rather than the exit code.
./gradlew --console=plain :vega-lite:jvmTest --tests '*VegaLiteWildTest*' -i \
  > "$ROOT/build/vega-lite-wild.log" 2>&1 || true
sed -n '/==== wild corpus ====/,/==== end ====/p' "$ROOT/build/vega-lite-wild.log" || {
  echo "The comparison produced no report. Details:" >&2
  echo "  $ROOT/build/vega-lite-wild.log" >&2
  exit 1
}
