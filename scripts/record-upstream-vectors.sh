#!/usr/bin/env bash
# Regenerates the upstream test vectors under test-fixtures/upstream-vectors/.
#
# 1. read the pinned Vega version from the installed package
# 2. clone that exact tag of the Vega monorepo (its tests are not published to npm)
# 3. run each package's own test files against the installed Vega, recording every call
# 4. write one vector file per package
#
# The vectors are what Vega's own test suite *asks* of Vega, with the answers Vega actually gives —
# see oracle-js/src/record-upstream-tests.mjs for why they are recorded rather than transcribed.
#
# They are **not** checked in, whatever this comment said before: ten megabytes of derived JSON, and
# the argument that keeps the Vega-Lite scenes and rendered goldens out of the repository (see
# docs/adr/0008) keeps these out too. The consequence is the part to remember rather than rediscover:
# **every host that runs the tests records them first**, because the replay guards assert the harness
# has not shrunk and therefore *fail* rather than skip on a fresh clone. Both CI jobs do it, and so
# does the release's publish job — which did not, and that is how the first 0.1.0 attempt died sixteen
# minutes in, on the one host where nothing had recorded them.
#
# This script rewrites them; review the resulting diff the way you would review a golden, because a
# change means either the port or upstream moved.
#
# Upgrading to a new Vega: bump the version in oracle-js/package.json, `npm ci`, run this, read the
# diff. That is the whole procedure — the tag below is not written down anywhere else.
set -euo pipefail
cd "$(dirname "$0")/.."

# A `timeunit` vector is *local*, so what upstream returns depends on the machine's zone. The same
# zone the JVM tests and scripts/oracle.sh use, so a checked-in vector means the same thing anywhere.
export TZ="Europe/Amsterdam"

ROOT="$PWD"
CHECKOUT="$ROOT/build/vega-upstream"

cd oracle-js
npm ci --silent
VERSION="$(node -p "require('./node_modules/vega/package.json').version")"
cd "$ROOT"

echo "==> Vega $VERSION (from the installed package)"

# Shallow, and reused when it is already at the right tag: this is a ~200 MB clone otherwise.
if [ -d "$CHECKOUT/.git" ] && [ "$(git -C "$CHECKOUT" describe --tags --exact-match 2>/dev/null || true)" = "v$VERSION" ]; then
  echo "==> Reusing $CHECKOUT"
else
  echo "==> Cloning vega/vega at v$VERSION"
  rm -rf "$CHECKOUT"
  git clone --quiet --depth 1 --branch "v$VERSION" https://github.com/vega/vega.git "$CHECKOUT"
fi

# Every package whose tests record something. The ones left out record nothing and are not silently
# skipped: vega-util's tests import a built bundle that is not in the checkout, vega-label needs a
# canvas, and vega-cli, vega-loader, vega-parser, vega-runtime and vega-view test a browser or a
# process rather than a computation.
PACKAGES=(
  vega-transforms
  vega-time
  vega-scenegraph
  vega-expression
  vega-statistics
  vega-encode
  vega-scale
  vega-functions
  vega-crossfilter
  vega-event-selector
  vega-format
  vega-geo
  vega-regression
  vega-hierarchy
  vega-voronoi
  vega-force
)

# One package per process, deliberately. Vega numbers tuples from a module-level counter and those
# ids reach the recorded data, so two packages in one process makes the second depend on the first.
echo "==> Recording"
cd oracle-js
for package in "${PACKAGES[@]}"; do
  node src/record-upstream-tests.mjs "$CHECKOUT" "$package"
done
cd "$ROOT"

# An empty file would claim a package was covered when nothing was recorded from it.
# `js-number-strings.json` is not a recording of a package's tests and holds `numbers` rather than
# `calls`; reading `.calls.length` off it threw, and under `set -e` that killed the run *before the
# whole d3 section*. Excluded here as it is in the total below.
find test-fixtures/upstream-vectors -name '*.json' \
  ! -name 'known-divergences.json' ! -name 'js-number-strings.json' -print0 |
  while IFS= read -r -d '' file; do
    count="$(node -p "JSON.parse(require('fs').readFileSync('$file','utf8')).calls.length")"
    # An `if`, not `[ ... ] && rm`: the and-list returns 1 for every file that *does* have vectors,
    # and under `set -e` that ended the script silently — everything after this was never reached.
    if [ "$count" = "0" ]; then
      rm "$file"
      echo "    removed $(basename "$file") (recorded nothing)"
    fi
  done

# ---- d3 ----------------------------------------------------------------------------------------
#
# Most of the *arithmetic* this engine ports is d3's, not Vega's: ticks, scales, curves, arcs, colour,
# number and time formatting, calendar intervals. Each d3 package is its own repository, tagged
# `v<version>`, and its tests are mocha rather than tape — the recorder handles both. Their suites are
# large: `d3-time` alone yields more vectors than all of `vega-transforms`.
#
# The timezone comes from the package's *own* test script (`TZ=America/Los_Angeles mocha ...`), because
# a local interval's answer depends on it, and it is written into the vector file so a replay knows
# which zone produced it.
D3_PACKAGES=(
  d3-array
  d3-time
  d3-time-format
  d3-format
  d3-dsv
  d3-geo
  d3-color
  d3-shape
  d3-scale
  d3-interpolate
  d3-path
  d3-hierarchy
)

echo "==> d3"
for package in "${D3_PACKAGES[@]}"; do
  version="$(node -p "require('./oracle-js/node_modules/$package/package.json').version" 2>/dev/null || true)"
  if [ -z "$version" ]; then
    echo "    $package is not installed; skipping"
    continue
  fi
  clone="$ROOT/build/d3-upstream/$package"
  if [ -d "$clone/.git" ] && [ "$(git -C "$clone" describe --tags --exact-match 2>/dev/null || true)" = "v$version" ]; then
    :
  else
    rm -rf "$clone"
    mkdir -p "$(dirname "$clone")"
    git clone --quiet --depth 1 --branch "v$version" "https://github.com/d3/$package.git" "$clone" ||
      { echo "    $package: no tag v$version upstream; skipping"; continue; }
  fi
  zone="$(node -p "((require('$clone/package.json').scripts||{}).test||'').match(/TZ=([\w\/]+)/)?.[1] || 'Europe/Amsterdam'")"
  (cd oracle-js && TZ="$zone" node src/record-upstream-tests.mjs "$clone" "$package")
done

# Not an upstream test: `String(x)` is a language primitive, and only the language can say what it
# prints. Every number a chart writes as text goes through it.
node oracle-js/src/record-number-strings.mjs

total="$(node -e '
  const fs = require("fs");
  const dir = "test-fixtures/upstream-vectors";
  let n = 0;
  for (const f of fs.readdirSync(dir)) {
    const doc = JSON.parse(fs.readFileSync(`${dir}/${f}`, "utf8"));
    n += (doc.calls || doc.numbers || []).length;
  }
  console.log(n);
')"
echo "==> $total vectors in test-fixtures/upstream-vectors"
echo "    Replayed by UpstreamTimeVectorsTest and UpstreamTransformVectorsTest."
echo "    Review the diff as a golden; a divergence that appears or disappears fails the build."
