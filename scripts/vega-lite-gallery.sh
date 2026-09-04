#!/usr/bin/env bash
# The gallery sweep: every example Vega-Lite ships, through both compilers.
#
# Vega-Lite ships 627 example specifications. Compiling is a **pure function of the specification**
# — no data is fetched and none is needed — so all of them can go through upstream's compiler and
# through this one and the outputs compared property by property, without a byte of the datasets
# they name. That is what makes a corpus this size affordable: the whole sweep is a couple of
# seconds either side.
#
# This was a one-off measurement for a long time, and it did its job: it took the corpus from 124 of
# 627 matching to all 627, cause by ranked cause. Then nothing kept it there. The examples were not
# checked in, no script referenced them and no CI job ran them, so the largest surface this project
# had ever verified was protected by nothing — a regression in any of the 503 that were fixed would
# have gone unnoticed until somebody swept again by hand.
#
# **Nothing is checked in, deliberately.** The examples come from the source tarball at the version
# `oracle-js` has installed, so a version bump sweeps that version's examples rather than a stale
# copy somebody remembered to update; and 627 files of upstream's test data are not this
# repository's to carry. `VegaLiteGalleryTest` asserts the version it swept matches the version it
# was compared against, so the two cannot drift apart silently.
#
# The corpus is therefore absent on a fresh clone, and the suite **fails** rather than skipping when
# it is — see the header of `scripts/vega-lite-oracle.sh` for what a skip costs, and
# `VegaLiteGalleryTest`'s own doc for why there is no `assumeTrue` in it. `scripts/check.sh` runs
# this script before the gradle gate for the same reason it runs `vega-lite-oracle.sh` first.
#
#   --references-only   fetch and compile with upstream, then stop. What check.sh and CI use, since
#                       the comparison itself is a Gradle test they run as part of the whole suite.
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
CHECKOUT="$ROOT/build/vega-lite-upstream"
EXAMPLES="$CHECKOUT/examples/specs"
REFERENCE_DIR="$ROOT/build/vega-lite-gallery"

echo "==> Installing pinned Node dependencies"
(cd oracle-js && npm ci --no-audit --no-fund --silent)

# **From the installed package**, not written down here. `scripts/record-upstream-vectors.sh` takes
# the same approach for Vega and for the same reason: a version in two places is a version that
# disagrees with itself, and upgrading should be a single edit to `oracle-js/package.json`.
VERSION="$(node -p "require('./oracle-js/node_modules/vega-lite/package.json').version")"
echo "==> Vega-Lite $VERSION (from the installed package)"

# A **tarball of one directory**, where `record-upstream-vectors.sh` clones. That script needs the
# packages' test suites and takes a ~200 MB shallow clone to get them; all this needs is
# `examples/specs`, which is 3 MB. The stamp is what makes it reusable: an extracted tree carries no
# tag, so without it a rerun either re-downloads or silently keeps a previous version's examples.
STAMP="$CHECKOUT/.version"
if [[ -f $STAMP && "$(cat "$STAMP")" == "$VERSION" && -d $EXAMPLES ]]; then
  echo "==> Reusing $EXAMPLES"
else
  echo "==> Fetching vega/vega-lite examples at v$VERSION"
  rm -rf "$CHECKOUT"
  mkdir -p "$CHECKOUT"
  curl -fsSL "https://codeload.github.com/vega/vega-lite/tar.gz/refs/tags/v$VERSION" \
    | tar -xz -C "$CHECKOUT" --strip-components=1 "vega-lite-$VERSION/examples/specs"
  echo "$VERSION" > "$STAMP"
fi

# `-maxdepth 1`, and the depth is the whole point: `examples/specs/normalized` holds 183 more
# `.vl.json` files, which are not examples. They are what upstream's *normalizer* produces from some
# of the 627 — its own test data for a stage of its own pipeline — so sweeping them would report a
# corpus of 810 and compare the compiler against its own intermediate form. Counted recursively
# here once, which is how that was found: the script said 810 and the sweep said 627.
count="$(find "$EXAMPLES" -maxdepth 1 -name '*.vl.json' | wc -l | tr -d ' ')"
if [[ $count -lt 600 ]]; then
  echo "Only $count example(s) under $EXAMPLES; the tarball's layout has moved." >&2
  exit 1
fi

# One Node process for all of them. The per-fixture loop in `vega-lite-oracle.sh` is fine for 283
# and is minutes of interpreter startup for 627.
echo "==> Compiling $count example(s) with upstream Vega-Lite"
rm -rf "$REFERENCE_DIR"
(cd oracle-js && node src/vega-lite-gallery.js "$EXAMPLES" "$REFERENCE_DIR")

if [[ $references_only == true ]]; then
  echo "Wrote $count reference(s) to $REFERENCE_DIR."
  echo "The comparison itself is VegaLiteGalleryTest, run by scripts/check.sh."
  exit 0
fi

echo "==> Comparing with the Kotlin compiler"
if ./gradlew --console=plain :vega-lite:jvmTest --tests '*VegaLiteGalleryTest*' \
  > "$ROOT/build/vega-lite-gallery.log" 2>&1; then
  echo "All $count example(s) compile to the specification upstream compiles them to."
else
  echo "The gallery sweep FAILED. Details:" >&2
  echo "  $ROOT/build/vega-lite-gallery.log" >&2
  echo "  HTML report: $ROOT/vega-lite/build/reports/tests/jvmTest/index.html" >&2
  grep -A 20 "AssertionFailedError" "$ROOT/build/vega-lite-gallery.log" | head -60 >&2 || true
  exit 1
fi
