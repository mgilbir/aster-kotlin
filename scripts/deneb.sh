#!/usr/bin/env bash
# The **Deneb** sweep: Vega specifications other people wrote, compared against upstream Vega.
#
# `scripts/oracle.sh` renders the 198 fixtures under `test-fixtures/specs`. Those are this
# repository's own: written to pin down a reading of Vega's semantics, one behaviour at a time, by
# the person implementing it. A specification someone wrote to draw a real chart is a different
# distribution — features combined in ways no fixture combines them, layouts that depend on three
# transforms agreeing, and the occasional thing that only works by accident.
#
# The corpus is `avatorl/Deneb-Vega-Templates`: 63 templates for Deneb, the Vega custom visual for
# Power BI, covering the Financial Times Visual Vocabulary chart types plus a handful of advanced
# ones — force layouts, isocontours, treemaps, voronoi, geographic projections, a sankey. MIT
# licensed, and pinned to a commit rather than to `main`: a corpus that moves is a comparison whose
# result cannot be reproduced.
#
# ### What has to be undone before they can run at all
#
# These are Deneb templates, not plain Vega, and three things in them belong to Power BI.
# `oracle-js/src/deneb-prepare.js` undoes each one in the open — the corpus is only worth having if
# both engines are handed the *same plain Vega*:
#
#   * every root dataset is `{"name": "dataset"}`, injected by Power BI at run time, so rows are
#     synthesised from the column declaration the template carries in `usermeta.dataset`;
#   * `pbiColor(n)` is Deneb's, not Vega's — upstream cannot evaluate these either — so each of the
#     112 calls is replaced by the literal colour it would return;
#   * one template is JSON with `//` comments.
#
# The synthesised rows are plausible rather than meaningful, and that costs coverage: a template
# whose layout collapses on nonsense data exercises less of the renderer than it would on real data.
# It costs nothing in *correctness* of the comparison, which is the point — both engines are given
# the same twelve rows.
#
# ### What cannot be compared, and why it is excluded rather than fudged
#
# A template whose reference this oracle cannot produce **faithfully** is left out, with the reason
# recorded in the manifest, exactly as the wild corpus records upstream's refusals. Two causes:
#
#   * `pbiPatternSVG`, a second Deneb function, in the sankey template. One call site, and unlike
#     `pbiColor` it returns a generated SVG pattern rather than a colour; substituting something for
#     it would be inventing the chart rather than preparing it.
#   * `vega-label`, which places labels by rasterising the marks into a bitmap and therefore needs a
#     real canvas. `oracle-js` deliberately has none — installing `canvas` switches upstream's text
#     measurement and moves *every* reference in the repository — so the label transform silently
#     produces no labels and the reference would be a comparison against the shim rather than
#     against Vega. Six templates use it.
#
# That leaves 56. Worth saying plainly: `label` is the one transform in this corpus that none of the
# 198 fixtures use, and it is the one the oracle cannot reference. The corpus adds combinations, not
# transform types.
#
# **A measurement, not yet a gate**, and deliberately — the same course the gallery and wild sweeps
# took. `scripts/check.sh` does not call this; run it when you want the number.
#
#   scripts/deneb.sh                     fetch, prepare, render with upstream, then compare
#   scripts/deneb.sh --references-only   fetch, prepare and render with upstream, then stop
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
# Pinned, for the reason the wild corpus is pinned: this is a dataset, not a dependency that wants
# upgrading.
CORPUS_COMMIT="2f4a29c1af6556ab0e58e3584a7b7dc54073bfae"
CHECKOUT="$ROOT/build/deneb-corpus"
SPECS="$ROOT/build/deneb-specs"
REFERENCE_DIR="$ROOT/build/deneb-reference"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

VERSION="$(node -p "require('./oracle-js/node_modules/vega/package.json').version")"
echo "==> Vega $VERSION (from the installed package), corpus at ${CORPUS_COMMIT:0:8}"

# Nothing is checked in: 18 MB of somebody else's templates — most of it base64 preview images — is
# not this repository's to carry, and a stamped extraction is what stops a rerun either
# re-downloading or silently keeping a previous commit's templates.
STAMP="$CHECKOUT/.commit"
if [[ -f $STAMP && "$(cat "$STAMP")" == "$CORPUS_COMMIT" ]]; then
  echo "==> Reusing $CHECKOUT"
else
  echo "==> Fetching Deneb-Vega-Templates at ${CORPUS_COMMIT:0:8}"
  rm -rf "$CHECKOUT"
  mkdir -p "$CHECKOUT"
  curl -fsSL "https://codeload.github.com/avatorl/Deneb-Vega-Templates/tar.gz/$CORPUS_COMMIT" \
    | tar -xz -C "$CHECKOUT" --strip-components=1
  echo "$CORPUS_COMMIT" > "$STAMP"
fi

echo "==> Preparing the templates as plain Vega"
rm -rf "$SPECS"
(cd oracle-js && node src/deneb-prepare.js "$CHECKOUT" "$SPECS")

count="$(find "$SPECS" -name '*.vg.json' | wc -l | tr -d ' ')"
if [[ $count -lt 50 ]]; then
  echo "Only $count template(s) prepared; the corpus layout has moved." >&2
  exit 1
fi

# **Rendered one at a time, and a refusal is recorded rather than fatal.** The claim being tested is
# agreement; a template upstream cannot render here is not this engine's problem to solve, but it
# has to be counted or the match rate is over a corpus nobody can name.
echo "==> Rendering $count template(s) with upstream Vega"
rm -rf "$REFERENCE_DIR"
mkdir -p "$REFERENCE_DIR"
rendered=0
declare -a refused=()
for spec in "$SPECS"/*.vg.json; do
  name="$(basename "$spec" .vg.json)"
  log="$REFERENCE_DIR/$name.stderr"
  (cd oracle-js && node src/reference.js "$spec" "$REFERENCE_DIR/$name.reference.json") \
      >/dev/null 2>"$log" && ok=1 || ok=0
  # The canvas shim first, whether or not the render came back: `vega-label` rasterises the marks to
  # place its labels, so without a canvas it either throws or quietly places none. Either way the
  # reference has a piece missing and comparing against it measures the shim.
  if grep -q "canvas-shim" "$log"; then
    rm -f "$REFERENCE_DIR/$name.reference.json"
    refused+=("$name	needs a real canvas: vega-label rasterises the marks to place labels")
  elif [[ $ok -eq 1 ]]; then
    rendered=$((rendered + 1))
  else
    # `reported an error while running` is a view that threw **mid-run**: the operator that threw
    # produced nothing and everything after it never ran, so the scene it left is a crash snapshot
    # rather than a drawing. See `oracle-js/src/reference.js`.
    reason="$(grep -m1 -oE 'reported an error while running: .*|Unrecognized function: [A-Za-z]+|Error: .*' "$log" | head -1 | cut -c1-90)"
    refused+=("$name	${reason:-upstream refused it}")
  fi
  rm -f "$log"
done

echo "==> upstream rendered $rendered, refused ${#refused[@]}"
{
  printf 'name\treason\n'
  for row in "${refused[@]+"${refused[@]}"}"; do printf '%s\n' "$row"; done
} > "$REFERENCE_DIR/refused.tsv"
printf '{"templates": %d, "rendered": %d, "refused": %d, "vegaVersion": "%s", "commit": "%s"}\n' \
  "$count" "$rendered" "${#refused[@]}" "$VERSION" "$CORPUS_COMMIT" \
  > "$REFERENCE_DIR/manifest.json"

if [[ $references_only == true ]]; then
  echo "Wrote references to $REFERENCE_DIR."
  echo "The comparison itself is DenebCorpusTest."
  exit 0
fi

echo "==> Comparing with the Kotlin runtime"
# `|| true`: this is a measurement, so a difference is the output rather than a failure. The test
# prints the tally and the ranked causes; read those rather than the exit code.
./gradlew --console=plain :vega-runtime:jvmTest --tests '*DenebCorpusTest*' -i \
  > "$ROOT/build/deneb.log" 2>&1 || true
sed -n '/==== deneb corpus ====/,/==== end ====/p' "$ROOT/build/deneb.log" || {
  echo "The comparison produced no report. Details:" >&2
  echo "  $ROOT/build/deneb.log" >&2
  exit 1
}
