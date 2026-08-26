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

### Vega-Lite fixtures are compared as *specifications*

`test-fixtures/vega-lite/*.vl.json` go through `./scripts/vega-lite-oracle.sh`, which writes two
references per fixture: the Vega that upstream's compiler produces, and the scene upstream's renderer
then draws from it.

```bash
$EDITOR test-fixtures/vega-lite/my-chart.vl.json
./scripts/vega-lite-oracle.sh
```

The first reference is the one to read a failure from. Vega-Lite is almost entirely defaults — a
scale type, a stack transform, a tick count, a label angle — and each of those is one property of the
emitted specification, so comparing the specification names the rule that drifted where comparing the
picture would only say that some marks moved. The second reference is what proves the emitted Vega
actually draws upstream's chart, and it is where the two can disagree in the other direction.

Port the rules from upstream's own TypeScript, which ships inside the pinned package at
`oracle-js/node_modules/vega-lite/src`. Every defaulting rule in `vega-lite/` cites the file it came
from, and the ones that read as arbitrary — a stacked area imputing its gaps to zero, a nominal
horizontal axis turning its labels to 270 degrees — are exactly the ones that were not guessable.

### When the harness is what is wrong

Sometimes both sides agree with each other and neither with the renderer. That has happened three
times, and each time the fix belonged in `oracle-js/src/normalize.js` as much as in the engine:

- text `dx`/`dy` were folded into the anchor *before* rotation on both sides, so a rotated label
  passed the comparison and drew in the wrong place
- a symbol's `size` channel was compared but never its outline, hiding a wrong shape table
- fill and stroke opacity were never compared at all
- a dash pattern was never compared, so a dashed gridline and a solid one were the same mark

If a difference is visible in the SVG but the comparison passes, suspect the normalizer.

### The two walks are compared against each other

There are two traversals of a scene — `vega-compose-multiplatform`'s `SceneWalk` and the Swift
package's — and each says in its own header that it emits "the same calls in the same order" as the
other. Nothing checked that, and it cost a defect that shipped: the Swift walk had no zero-opacity
guard, so a label an axis had deliberately hidden was painted black, and on one committed fixture it
emitted 104 draw calls where the Compose walk emitted 80. Both renderers' own tests passed throughout,
because each asserts about itself.

`test-fixtures/scene-walk/*.calls.txt` is the comparison. `SceneWalkGoldenTest` writes each file from
the Compose walk; `SceneWalkParityTests` reads the same file and asserts the Swift walk reproduces it,
naming the first line that differs.

```bash
# the Kotlin half, and how to regenerate after a deliberate change
./gradlew :vega-compose-multiplatform:jvmTest -PupdateGoldens=true --rerun-tasks
# the Swift half
./scripts/swift-test.sh
```

Two things to know before touching it. The **format is a contract between two languages**:
`CanonicalCalls` exists once per side and its only job is to be byte-identical, which is why it writes
every field always and rounds by hand rather than through `%f` — Java rounds a tie up and C rounds it
to even. And the **scene has to be identical by construction** or the comparison is about two
compilers rather than two walks, so every compile input is spelled out on both sides, `timeZone`
included: a `time` scale is local, the JVM tests pin `Europe/Amsterdam` and `swift test` pins nothing.

### The same idea, one level up: host conformance

The scene walk compares two renderers' output. `test-fixtures/host-conformance` compares what three
engines do with the same input, for the seams a host plugs into — a font resolver, an image resolver,
the placement a host is told about. Same arrangement, same reason: one golden between them rather than
each against the others, a reader written once per side.

```bash
./gradlew :vega-compose-multiplatform:jvmTest --tests '*ConformanceTest*'   # Compose Multiplatform
./gradlew :vega-android-canvas:connectedDebugAndroidTest                    # Android, needs a device
./scripts/swift-test.sh                                                     # Apple
python3 scripts/host-conformance.py                                         # every golden read by every engine
```

These goldens are **written by hand, not generated** — the opposite of the scene walk, deliberately.
There is no `-PupdateGoldens` for them, because the whole value is that a line only moves when someone
decides an engine should behave differently and writes down what it should do. Adding a seam means
adding a golden *and* a reader on all three engines; the last gate above is what makes the second half
non-optional.

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

`vega-model`, `vega-expression`, `vega-dataflow`, `vega-scene`, `vega-runtime`, `vega-lite` and
`vega-svg` are plain Kotlin and are meant to move to Kotlin Multiplatform unchanged. No Android types, and no JVM-only APIs:

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
- `./scripts/check.sh` must be green before committing. It is **the** gate: format, the ABI
  dumps, every JVM test, the native compiles, lint, the demo APK, the instrumented suites compiled
  and — where the host allows — the Swift package, the instrumented suites *run*, and the two
  differential comparisons against upstream.

  It prints a ledger of every gate as RAN, SKIPPED with a reason, or FAILED. **Read it.** A run can
  be green having skipped something, and the reason is always actionable — start an emulator, install
  node, use a Mac. `--fast` drops the oracles for the edit loop; landing anything runs them.

  It was five scripts and an agreement to remember the other four. That agreement failed three times
  in ways that reached `main` or a release, so it is one script now.

- **A change to the public surface belongs in `CHANGELOG.md`, in the same branch.** The release
  workflow assembles the release page from that section verbatim, so an entry nobody writes is a
  change nobody is told about — which is how 0.4.0 nearly shipped a source-breaking one. The
  `changelog` gate fails a branch that moves an API snapshot and says nothing. Where a snapshot was
  re-recorded and the surface did not really change, put `[api-snapshot-only]` in a commit message
  and say there why.
