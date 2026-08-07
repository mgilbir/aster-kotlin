# Working on aster-kotlin

This is how the engine gets built, as distinct from what it contains. `STATUS.md` says where the work
is; `SUPPORTED_FEATURES.md` says what works; `PROJECT_BRIEF.md` is the specification. This file is the
method, and following it is what has kept the port honest.

## The one rule: probe upstream, never guess

Vega's behaviour is full of decisions that look arbitrary until you see the source, and a reasonable
implementation gets them wrong. Before implementing anything, run it through upstream and read what
comes out. Every reference vector in the test suite was obtained this way, and the comments say so.

A partial list of things that were *not* what a careful reader would have assumed, each caught by
doing this rather than by reasoning:

- `size` on a symbol is the squared extent, not the area — upstream ships its own symbol table and
  does not use d3-shape's
- `range: "height"` descends for a continuous scale and **ascends** for a discrete one
- `reverse` flips the range, not the domain, so getting it wrong leaves a right-looking chart with a
  backwards axis
- a `null` in a series does *not* break a line; `defined` is what breaks it
- `parent` inside a facet is the group's datum, not the group item, so `parent.width` is undefined
- `width` and `height` are inherited by a group's subscope, so a nested `"height"` range spans the
  whole chart
- `timeunit` *builds* its floor from the units present and defaults the year to 2012, which is how a
  seasonal profile is requested
- a stroked path reserves four stroke widths for a miter join, not the ten a canvas defaults to

The probes under `oracle-js/src/` exist for this. See `oracle-js/README.md`.

## Differential tests are the gate

`test-fixtures/specs/*.vg.json` are compiled by both engines and compared mark by mark, including the
surface size. `FixtureDifferentialTest` discovers them from the directory, so adding one is a single
file plus its reference.

```bash
# 1. write the fixture
$EDITOR test-fixtures/specs/my-feature.vg.json

# 2. generate its reference from upstream
cd oracle-js && node src/reference.js ../test-fixtures/specs/my-feature.vg.json \
    ../test-fixtures/reference/my-feature.reference.json

# 3. run the gate
./gradlew :vega-runtime:test --tests '*FixtureDifferential*'
```

**Expect it to fail.** Of the fixtures added so far, most failed on arrival, and every one of those
failures was a real defect rather than a fixture mistake — an upside-down trellis, a whole wrong symbol
table, nine units of phantom chart, gridlines running off the edge. A fixture that passes immediately
has told you less than one that does not.

`./scripts/oracle.sh` regenerates every reference and runs the comparison. Review a changed reference
the way you would review a golden: it means either this port or upstream moved.

### When the harness is what is wrong

Sometimes both sides agree with each other and neither with the renderer. That has happened three
times, and each time the fix belonged in `oracle-js/src/normalize.js` as much as in the engine:

- text `dx`/`dy` were folded into the anchor *before* rotation on both sides, so a rotated label
  passed the comparison and drew in the wrong place
- a symbol's `size` channel was compared but never its outline, hiding a wrong shape table
- fill and stroke opacity were never compared at all

If a difference is visible in the SVG but the comparison passes, suspect the normalizer.

## Look at the output

Numbers agreeing is not the same as a chart looking right.

```bash
./gradlew :vega-runtime:test --tests '*FixtureSvgTest*'   # writes build/fixture-svg/*.ours.svg
cd oracle-js && node src/render.js ../test-fixtures/specs/pie.vg.json ../build/fixture-svg/pie.upstream
```

Then open the two side by side. On macOS, `qlmanage -t -s 900 -o <dir> <file>.svg` rasterises one
quickly. The rotated-text bug above was found exactly this way — the numbers matched and the picture
did not.

For the device, `./scripts/emulator.sh` starts the project AVD **with a window** and installs the demo
(`--headless` for the CI-style run). The demo's `Spec:` entries compile bundled fixtures on a
background thread, so they exercise the real path a user takes.

## Nothing is silently ignored

`PROJECT_BRIEF.md` §3.3 and §14. Every unsupported construct produces a diagnostic naming itself and
saying why. This is the discipline that makes a partial implementation usable instead of mysterious,
and it is worth more than the feature you were tempted to approximate:

> a slice that is visibly the wrong shape is worse than one that is honestly missing

When something is left out, report it *by name*, say what a specification should do instead, and add a
test asserting the report. When it is left out because reproducing it needs machinery that does not
exist yet, say that too — `padAngle` needs a pad radius, `timeParse` needs a `strptime`, continuous
colour ramps need d3's interpolator tables and a scheme extent.

## The core stays portable

`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene`, `vega-runtime` and `vega-svg` are plain
Kotlin and are meant to move to Kotlin Multiplatform unchanged. No Android types, and no JVM-only APIs:

- calendar work goes through `kotlinx-datetime`, never `java.time` or `Calendar`
- rounding goes through `roundHalfUp`, which is also more faithful to d3 than `Math.round` — d3 rounds
  the way JavaScript does, halves toward positive infinity, and both Java's and Kotlin's differ from
  that on negatives
- `PlatformDecimals` is the single exception and documents why; it becomes the `expect` when the core
  goes multiplatform

`NoAndroidTypesTest` enforces both rules and permits that one file. `minSdk` is 26 because
`kotlinx-datetime` is implemented on `java.time`.

## Conventions

- **Work on a branch.** Never commit to `main`.
- **No AI attribution** anywhere in a commit message, PR body, tag or release note.
- Comments explain *why*, especially where the code reproduces something surprising. A comment that
  restates the code is noise; a comment recording what upstream does and how it was verified is the
  most valuable thing in the file.
- Update `STATUS.md` and `SUPPORTED_FEATURES.md` in the same commit as the change they describe,
  including the next-three list. A status file that lags is worse than none.
- `./scripts/check.sh` must be green before committing: format, all tests, lint, demo APK.
