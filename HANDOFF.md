# Handoff

Working brief for whoever picks this up next. Delete it when it stops being true.

## Where things stand

Branch `milestone-0-bootstrap`. Working tree clean, both gates green:

- `./scripts/check.sh` — format, all tests, lint, demo APK
- `./scripts/oracle.sh` — regenerates upstream references and runs the differential comparison

**119 differential fixtures pass, all matching upstream exactly.** That is the only number here
that means what it says.

## Read this before trusting the other number

A survey harness (`ExampleTriage`) runs the 93 official Vega examples and reports a "clean" count.
**Do not lead with that figure, and do not treat it as progress.** "Clean" means the compiler
emitted no *error* diagnostics. It does not mean the chart renders correctly, and nothing in that
count has ever been compared against upstream's scene.

The radar chart proved how weak it is, twice over. A specification missing only the top-level
`encode` block scored zero errors — the diagnostic was a warning — and still drew its entire
contents in the wrong corner. And once the errors *were* gone and the differential fixture passed,
the chart was still visibly wrong: the grid lines painted over the data, because mark `zindex` had
been parsed and silently dropped and nothing in the comparison could see paint order.

Two things worth doing about this, in whatever order suits:

- Reclassify diagnostics that change geometry as errors in the triage, or the survey keeps
  flattering itself.
- Prefer adding differential fixtures over raising the clean count — and **look at the rendered SVG
  after a fixture goes green**, because that is what caught both of the above.

## The radar chart is done

`test-fixtures/specs/radar.vg.json` is Vega's own radar chart, and it passes. Getting there needed
four things in the engine and two in the harness; all six are described in STATUS.md under "Known
failing fixtures". In short:

- Top-level `encode` is implemented — the chart's own group item, encoded like any other group mark
  over upstream's two defaults (origin at 0,0, extent of the plotting area).
- `autosize.contains: "padding"` is implemented, and shrinks the `width`/`height` signals.
- A mark can be drawn from another mark's scene items (`"from": {"data": "<mark name>"}`).
- `linear-closed`, `basis-closed` and `cardinal-closed` are implemented.
- A `line` mark is now filled when it encodes a fill.
- Mark `zindex` decides paint order, upstream's way — a **negative** `zindex` raises a mark rather
  than sinking it.

Two new fixtures came with it: `curves-closed`, because the radar only exercises one of the three
closed families, and `autosize-none`, because nothing else covered a `none` chart with padding.

## So is the grouped bar chart

`grouped-bar.vg.json` is Vega's `grouped-bar-chart` example, taken the same way, and it found three
more silences:

- `round: true` on a **continuous** scale was parsed and dropped, so every bar was a fraction of a
  unit wide. It is the output that rounds, not the scale — upstream swaps the range interpolator.
- `mult`, `offset`, `exponent` and `round` were read only on a *scaled* value reference. Upstream
  appends them to every one, and each is itself a value reference, so `{"field": "y", "offset":
  {"field": "height", "mult": 0.5}}` centres a label in a band.
- `contrast()` is implemented. White and black are within 1% of each other against Vega's default
  blue, so this is not a place to approximate.

## And so is the barley trellis

`barley-trellis.vg.json` is Vega's `barley-trellis-plot`, loading its 120 rows from the example's own
`"url": "data/barley.json"` through the file loader. It found four more:

- **`width` and `height` are signals, not just properties.** This chart declares its height as
  `6 * (offset + cellHeight)`; the plotting area is now settled after the signals resolve.
- A mark could be drawn from a plain mark but not from a **group** mark. Six cell titles were
  missing.
- Guide `encode` blocks. Folded into the properties they duplicate — `encode.grid.enter.strokeDash`
  *is* `gridDash` upstream — which is also what makes them participate in measurement.
- A legend over a `stroke` scale was missing upstream's explicit `transparent` fill. The test is on
  the `fill` channel alone, not on whether the legend maps any colour.

And one the comparison still cannot see, found by looking at the SVGs: a `zindex: 1` axis paints
**after** the legends upstream, and was painting before them here.

## And so is the connected scatter plot

`connected-scatter.vg.json` had been waiting only on the loader. With its data it found two more:

- An **ordinal scale with a numeric range** could not position a mark — refused as "no numeric
  range" — which is exactly how a label is nudged clear of its point. Upstream applies the scale and
  uses whatever number comes out; so does this now.
- An axis `format` was reported and ignored, so a price axis read `1.5` for upstream's `$1.50`. The
  currency symbol is its own slot in d3's grammar, and the caption a screen reader hears follows
  **each axis's** format rather than the scale's.

## The loader is done, and it is what was blocking the rest

The engine had a file loader and an HTTP loader and no way to compose them, and
`VegaChartController` had no seam to pass either — so no host could opt in to loading at all. Both
are fixed:

- `FallbackDataLoader` (common Kotlin) tries loaders in order, first one that serves the URI wins.
  Only a `LoadDeniedException` moves on; a broken socket propagates.
- `VegaDataLoaders.directoryThenNetwork(dir)` is the arrangement a corpus needs: read `data/x.json`
  from `dir` if it is there, fetch it from `https://vega.github.io/vega/` if it is not, and with
  `cacheDownloads = true` write it to `dir` so the second run is offline. The base URL's host is the
  allowlist by default.
- `VegaChartController(loader = ...)`. Still `DenyLoader` unless a host says otherwise.

**The fixture harness uses the file half only.** A green run must not depend on a connection, so
`test-fixtures/data/` is checked in beside `test-fixtures/reference/` and `scripts/oracle.sh` fetches
what is missing as a deliberate step. The oracle resolves a fixture's `url` against the spec's own
directory and then the corpus root, which is what `oracle-js/src/file-loader.js` does — the two
engines have to read the same bytes.

`ExampleTriage` uses the network half with caching, so a corpus of bare specifications fills itself
in on the first run. It is a survey run by hand, not a gate.

The **demo** wires it too — `directoryThenNetwork(cacheDir, cacheDownloads = true)` — so pasting a
gallery example with `"url": "data/..."` draws the chart. It needs `INTERNET` in the manifest, and
`DemoActivityTest.aPastedSpecificationLoadsItsDataFromTheGallery` proves it on the device over a real
socket, which is the only place a missing permission would show up.

**Pre-existing, unrelated:** `DemoActivityTest.theClipboardIsReadBackAsTextWhileTheAppHasFocus` fails
on this emulator — the clipboard needs foreground focus on Android 10 and later, which the run does
not reliably give. It fails identically on the commit before the loader work, and connected tests are
not part of `check.sh`.

## And so is the Wilkinson dot plot

`dot-plot-wilkinson.vg.json` needed three things, and the third took two wrong guesses to find:

- A top-level signal calling `scale()` — `scale('x', step) - scale('x', 0)`, a step in data units
  turned into pixels. Signal-free scales are now built before the signals.
- A dataset of bare numbers, wrapped as `{"data": value}` the way upstream's `ingest` does.
- **One epsilon.** A value landing exactly on a bin boundary divides to a whole number only in exact
  arithmetic: `(9.1 - 1.95) / 0.65` is 10.999999999999998 in doubles, so flooring it put the row one
  column to the left. Upstream adds `1e-14` inside the floor. Three of the 48 points sit on a
  boundary — enough to make the tallest column one dot short and the chart 9.75 units too short, and
  nothing else in the chart was wrong.

Both wrong guesses are worth knowing: `dotbin` is innocent (identical output, smoothed and not) and
so is `nice` (already defaulting to true). Both are pinned now, so the next person does not re-check
them. What found it was printing every signal from both engines side by side —
`size`, `ddext`, `hdext`, `ddh`, `hdh`, `height` — and seeing that only `hdext` differed, by one.
That technique is cheap and worked immediately where reading the transforms did not.

## The compile phases are gone, and so is `probability-density`

The last big structural difference from upstream is closed. The engine ran three fixed phases — all
data, then all signals, then all scales — and `probability-density` cannot be resolved by any fixed
order of them: `xscale`'s domain names the `points` dataset, and the `density` dataset's `extent` is
`{"signal": "domain('xscale')"}`. `DataflowOrder` replaces the phases with one dependency ranking
over all three kinds, which is what upstream gets from putting every operator into a single dataflow.

Worth knowing before touching it:

- Edges come from where upstream's come from. `Expression` now reports `dataDependencies` and
  `scaleDependencies` **by name**, read off the string literal a `data()` or `scale()` is handed,
  exactly as `vega-functions`' `dataVisitor` and `scaleVisitor` do at parse time.
- Ties break towards signals, then datasets, then scales, and that is load-bearing rather than
  cosmetic. It is what keeps a signal reading no dataset ahead of every dataset — the property the
  old seeding pass existed for — and it means every signal that has *become* resolvable resolves
  before the next dataset runs.
- **A transform can *publish* a signal**, and reading one is an edge to the dataset that writes it.
  `{"type": "extent", "signal": "vals"}` is the shape. This is required, not an improvement: the old
  phases resolved every signal again after all the data, so a wrong early value was overwritten;
  resolving each signal exactly once removes that safety net. `dot-plot-wilkinson` covered the
  pattern only by luck (its `ddh` reaches the data through `size` and a scale), so
  `published-signals` isolates it — it fails without the edge.
- `dataFreeSignals` and `ScaleSpec.isSignalFree` are gone. Do not reintroduce a predicate of that
  shape; the general order subsumes both, and `isSignalFree` had a hole (it never looked inside a
  range array) that only showed up once the graph did the same job properly.
- **The residual gap is transform expression parameters.** `filter`'s `expr`, `formula`'s `expr` and
  `cross`'s `filter` are the only three parameters upstream declares as `type: 'expr'` (probe it with
  `T.Definition.params` over `vega.transforms`). They are per-row expressions rather than
  `{"signal": ...}` references, so they are *not* edges, and a dataset carrying one is not held back
  for the signal it reads. It usually works anyway because of the tie-break; when it does not, the
  warning in `DataResolver` is the only thing that says so. Adding those three as edges is the
  obvious next increment and is small — the risk is that a new edge turns a chart that happened to
  work into a reported cycle, so do it behind the gate.
- **A group mark still has three phases**: its own data, then its own signals, then its own scales.
  Nothing in the corpus needs them interleaved, because the enclosing scope is entirely settled by
  the time a group is reached. If an example ever does, `DataflowOrder` is reusable as-is —
  `ScopeCompiler.nest` is where it would go.

## Pick the next example the same way

The method that worked seven times: take one real example, add it as a differential fixture *first*, let
it fail, fix what it names, then open `build/fixture-svg/<name>.ours.svg` next to
`build/oracle-reference/<name>.svg` and look at them. The fixture tells you the geometry is right;
only the SVG tells you the chart is. Note that upstream draws a `rect` mark as an SVG `<path>` and
scatters zero-extent `class="background"` and `class="foreground"` paths through its output — strip
those before comparing, they paint nothing.

**Data is no longer a reason to skip an example.** Copy the spec in, run `./scripts/oracle.sh`, and
its datasets are fetched into `test-fixtures/data/` and committed. Discount every loader diagnostic
when judging how far an example is from passing.

**Where the corpus stands, from `ExampleTriage` rather than from memory: 91 of the 93 compile
clean, 2 report errors.** Read the *movement* rather than the number: 70 → 79 as the stochastic and
crossfilter work landed, **79 → 75 when mark-level `transform` was implemented** — the survey
becoming honest, because five charts had been dropping a whole block silently — and 75 → 80 as the
raster family and `force` landed.

The 2 that remain **cannot be verified against upstream at all**, and one of them upstream itself
refuses. There is no category of outstanding work left in the corpus.

**`time-units` is done** and is a fixture; STATUS.md describes the five things it needed. The
handoff's prediction was right as far as it went — the domain field is a `FieldRef` now — but the two
"small residuals" were not small and were not what they looked like. The missing tick and the wrong
width were **one** defect each, and neither was arithmetic on ticks: the tick came back the moment the
domain was no longer empty, and the width was a floating-point crumb in a rotated text mark's bounds
that `Math.ceil` turned into a whole unit of plotting area. Read the STATUS section before assuming
anything similar is a rounding tolerance.

**`calendar-view` is done** too, and the old note above it was wrong in an instructive way: the
marks were not transposed, the *facets* were in the opposite order because a mark `sort` over
`datum.year` could only read `x` and `y` and silently tied. And `timeOffset` was implemented but
returning its argument. STATUS.md has the six things it needed.

**`crossfilter-flights` is done** and is a fixture. The note that used to sit here said it could not
be one because it draws 600,098 scene nodes; **upstream draws 171**. The 600,098 was this engine
drawing every unfiltered row three times over because the two transforms were missing. A triage node
count measures how wrong we are, not how big the chart is — do not size a decision off one. The test
heap is pinned at 2 GB now, for the unrelated reason that 200,000 rows through three `bin`
transforms is a large live set when transforms copy rather than mutate.

## The three refusals are lifted; two are finished and one is not

The owner asked for all three of the brief's scope refusals to be overturned and for every example to
pass. **PROJECT_BRIEF.md §3.3 and §18.2 are stale on this point** and should be amended when the geo
work lands; until then, read them as history rather than as policy.

**1. `random()` and `now()` — done.** `RandomStream` is upstream's `randomLCG` and
`oracle-js/src/determinism.js` puts the same generator and a stopped clock into upstream, so these
charts can have references at all. `clock`, `watch`, `error-bars`, `hypothetical-outcome-plots`,
`pi-monte-carlo`, `serpentine-timeline` and `bar-line-toggle` are all fixtures and all pass.

**2. The raster family — done.** `volcano-contours`, `density-heatmaps` and `contour-plot` are
fixtures and match exactly. `isocontour`, `geopath` without a projection, `kde2d`, `heatmap`, a
raster payload on `ImageNode`, a PNG encoder and mark-level `transform` all landed with them. The
parked `worktree-agent-a9f49f94103bacad5` branch is **superseded** for `isocontour` — this port is
verified against upstream where that one never was — and should be dropped rather than merged.

The harness was strengthened first, as the previous handoff insisted: `normalize.js` now takes an
FNV-1a digest of `getImageData` on the upstream side and the Kotlin side hashes its own pixels, so an
image mark is compared by what it *draws*. A blank image cannot pass.

**3. Force layout — done, and it was never the irreproducible thing it is assumed to be.**
`force-directed`, `beeswarm` and `packed-bubble` are fixtures. d3-force seeds a fixed LCG rather than
reaching for `Math.random`, and a node with no position starts on a phyllotaxis spiral; what is left
is arithmetic. See SUPPORTED_FEATURES.md for the three behaviours that had to come from upstream
rather than from its schema — in particular that **an omitted force parameter falls to d3's default,
not the one Vega documents**, because Vega only forwards the parameters a specification wrote.

## What is left: two examples, and neither can be verified

**119 differential fixtures pass. 91 of the 93 examples compile clean.** Everything that can be
checked against upstream has been.

### `projections` — upstream refuses it too

It names `airy`, `armadillo`, `baker`, `berghaus`, `bottomley`, `collignon`, `eckert1`, `guyou`,
`hammer`, `littrow`, `wagner6`, `wiechel`, `winkel3` and the interrupted and polyhedral families —
and `vega-projection` imports exactly **one** projection from `d3-geo-projection`, `geoMollweide`,
which this engine has. Running the example through the pinned oracle gives `Error: Unrecognized
projection type: airy`. The Vega website registers those types itself before rendering that page; a
bare Vega cannot draw it, and our diagnostic says what upstream's says. **Do not read its error count
as outstanding work.** If the extended family is ever wanted, `Projections.byName` is where a type is
added and `GeoProjectionTypesTest` is how it is proved.

### `word-cloud` — the one thing that was not attempted, and why

`labeled-scatter-plot` and `word-cloud` used to be a pair: both transforms reach for a `<canvas>`
Node has not got. They are **not** the same case, and the difference is what decided one and not the
other.

`label` rasterises the marks it must avoid — circles, a line — and asks only whether a pixel got *any*
coverage. That question has a geometric answer: does the shape overlap the pixel's square? So the
transform is implemented, with the occupancy computed analytically, the two halves that *can* be
pinned pinned (`BitmapTest`), and a warning on every use naming the one step that is not upstream's.
The two answers differ only on pixels a shape barely grazes.

`wordcloud` rasterises **glyphs**. `cloudSprite` sets a font, calls `fillText`, and reads the pixel
mask back; the packing then slides each word along an Archimedean spiral until its *mask* stops
colliding with the masks already placed. Words interlock into each other's gaps — a descender under a
crossbar — and that interlocking is the whole visual character of a word cloud. There is no geometric
answer to "which pixels does the word 'GRAMMARS' cover in 36px sans-serif": it depends on the font's
outlines and the rasteriser's hinting.

Substituting bounding boxes would produce a chart that looks like a word cloud and is not Vega's — a
visibly looser packing, and unlike `label` there is no pinned half to stand behind it. Everything that
determines the picture would be the invented part: the spiral is four lines and the generator is
already shared. **So it is not implemented, and the transform is reported by name.**

What would change that, in order of cost:

1. Install the native `canvas` package in `oracle-js`. That gives upstream a real oracle for both
   transforms — and costs the guarantee that references can be regenerated offline from a checked-out
   tree, which PROJECT_BRIEF.md §21 asks for. An owner's call, not a mechanical one.
2. With an oracle in hand, `label`'s analytic occupancy could be *measured* against a real
   rasteriser's rather than reasoned about, and its warning either removed or made precise.
3. `wordcloud` would still need a glyph rasteriser on the Kotlin side to match, which is a font engine
   and not a port of Vega.

  > Owner's decision on `wordcloud` and on adding a native canvas to the oracle: _not yet made._

### Where the numeric fidelity is hard-won, for whoever changes it next

Four places carry arithmetic that cannot be simplified without breaking a chart, and each has its
reasoning in the code rather than here:

- `Orient2d` — Shewchuk's adaptive predicate. `voronoi` is a sequence of orientation decisions and a
  single wrong sign changes the whole diagram.
- `Adder` — the same idea for `polygonContains`, where the sign of a sum around 1e-12 decides whether
  a continent is filled or left as a hole.
- `ResampleStream` — the guard is `!(d2 > 4 * delta2)` because `d2` is **NaN** for the first point of
  every line, and `NaN <= x` is false too.
- `Delaunator.quicksort` and `RandomStream` — two places where the *order* of operations is part of
  the answer, not an implementation detail.

Two of the 60 projection vectors are compared **within one printed digit** rather than exactly.
`azimuthalEqualArea` and `azimuthalEquidistant` clip at 179.999 degrees, where their scale factor is
114,591 and its derivative is 3.8e14 — so a one-ulp difference in `cos` between V8 and the JVM, which
neither runtime promises to avoid, moves a coordinate by 1.3e-4 of a pixel. That is enough to cross a
rounding boundary in the third decimal and nothing more. The arithmetic is in the test's comment.

And one **deliberate difference** in the comparison, stated so it is not mistaken for a tolerance: a
reference carrying a `strokeWidth` with no `stroke` colour describes an outline that is never
painted, and this engine records no stroke at all for it. A reference carrying a stroke colour still
demands a stroke of that width.

## Possible future work: a timer used as a `for` loop

`donut-chart-labelled` passes the differential and still looks wrong in the demo: its three most
crowded labels — United States, France, Germany — are drawn on top of each other, where the gallery
shows them spread down the page.

**The fixture is not lying.** Upstream's static scene stacks them too; the reference has all three at
`y = -9.501909, x = 228`. What the gallery shows is a *later frame*.

The reason there are frames at all is worth understanding before anyone touches it: the timer is not
animating anything, it is standing in for a loop Vega's expression language cannot express.

| signal | role |
| --- | --- |
| `shiftArray` | how far each label must move to clear the one above it |
| `counter` | `counter < length(data('labelPositions')) ? counter + 1 : counter`, fired by a `{"type": "timer"}` — the loop variable |
| `p1` | on each `counter` change, `shiftArray[counter-1] + p1` clamped at 0 — the accumulator |
| `p2` | on each `p1` change, `p1 + ',' + p2` — the output array, built by string concatenation |
| `shiftArrayRunning` | `reverse(split(p2, ','))` — that string turned back into an array |
| `labelPositionsFinal` | `shiftArrayRunning[index-1] + bin` — each label's final `y` |

Each label's offset is the running total of every overlap above it: a prefix sum. The giveaway that
this is a workaround rather than a design is `p2` accumulating an array by joining with commas and
splitting again, because there is no append either — and `p1` carrying `"force": true`, which a loop
needs and an animation would not.

**So this probably does not need a scheduler.** The loop has a termination condition: `counter` stops
at 33 and everything downstream stops with it. A bounded iteration with a fixed point can be run to
convergence at *compile* time — fire the `on` handlers whose source is a signal or a timer, repeatedly,
until nothing changes — with no wall clock, no repainting, and the chart still a pure function of the
specification.

Three things to weigh first, none of them checked:

- **Not every timer loop converges.** `clock` and `watch` read `now()` and never settle. They are
  already refused, but a convergence pass needs an iteration cap so a non-converging specification
  stops at an arbitrary frame rather than hanging.
- **The differential harness cannot verify the settled state.** The oracle captures upstream after
  `runAsync`, which is the unshifted frame. Pinning the converged layout needs evidence of another
  kind — and `runAsync` on this specification never returns, because the timer keeps the dataflow
  alive.
- It would change what a compile *is*. Today a signal's `on` handlers are applied only by the
  interaction layer, on a real event; running them at compile time until they settle is a different
  contract, and STATUS's "Next three tasks" item 1 describes the neighbouring gaps in the same
  machinery.

## Unfinished work parked elsewhere

`kde2d` + `isocontour` are checkpointed at `6ef5428` on branch
`worktree-agent-a9f49f94103bacad5`. **Deliberately not merged.** Status:

- Compiles cleanly under `allWarningsAsErrors`, both transforms registered, `check.sh` passes.
- **Numerically unverified.** No fixture, no upstream vectors, no number ever compared. The only
  test added is a registry completeness assertion listing the two names.
- **It unblocks zero examples.** `contour-plot` also needs `heatmap` (rasterises a grid to an
  image; no raster path exists here) *and* `geopath` (geo projections, out of scope per
  PROJECT_BRIEF). `density-heatmaps` needs `heatmap`. Both are mark-level `transform` blocks —
  check `marks[].transform`, not just `data[].transform`, before scoping any example.
- Consequently these two transforms **cannot currently be differentially verified at all**,
  because nothing in the engine can draw their output. Do not merge until that changes.

## Rules that are not negotiable

- **Probe upstream before implementing; never guess.** Pinned Vega is in
  `oracle-js/node_modules/vega-*/src/`. To run it, put a `.mjs` inside `oracle-js/` and run from
  there — the package is ESM, `require` fails, a script outside cannot resolve `vega`, and the
  default export does not exist (`import * as vega from 'vega'`). Delete probe files afterwards.
- **Both gates green before every commit.**
- **A differential fixture is the only real evidence.** A unit test asserting your own reading of
  the spec is not. A new fixture is *expected* to fail first — that failure is the information.
  Read differences from the JUnit XML at `vega-runtime/build/test-results/test/*.xml`; it is far
  more useful than the HTML report.
- **Never weaken the comparison harness to make a fixture pass.** If a tolerance is genuinely
  needed, say so with magnitude and reason. Strengthening it is welcome and shows up as a purely
  additive diff to the checked-in references — the `closed` channel added 25 lines and changed none.
- **If a difference is visible in rendered SVG while the comparison passes, suspect
  `oracle-js/src/normalize.js` before the engine.** This has now happened six times.
- **Nothing silently ignored** (PROJECT_BRIEF §3.3): every unsupported construct gets a named
  diagnostic. Equally, remove the diagnostic when you implement the thing — stale "not
  implemented" messages on working features have shipped twice. And note the third failure mode
  `zindex` showed: a property parsed into the model, never read, and never reported either.
- **Core stays KMP-portable**: no Android types, no JVM-only APIs. `NoAndroidTypesTest` enforces
  it. `kotlinx-datetime` for calendars, `roundHalfUp` for rounding, `PlatformDecimals` is the one
  documented exception.
- **Update `STATUS.md` and `SUPPORTED_FEATURES.md` in the same commit as the change they
  describe.** Both carry a fixture count; reconcile against
  `ls test-fixtures/specs/*.vg.json | wc -l`.
- **Never add AI/Claude attribution to a commit, PR, tag or release note.** Absolute.
- Work on the branch. Never commit to `main`; do not push or open a PR unless asked.

## If you delegate to worktree agents

- Worktrees here are created on an unrelated near-empty `first commit`. Tell every worker to
  check and `git reset --hard milestone-0-bootstrap` before starting.
- Every worker adds a fixture, so every worker edits the same counts in `STATUS.md` and
  `SUPPORTED_FEATURES.md`. Those conflict on merge by construction — reconcile yourself.
- Tell workers to commit in stages. Two agents were lost mid-task in one session with everything
  uncommitted.
- Verify their claims by reading the diff, especially any change to `Differential.kt` or
  `normalize.js`. One worker's harness change looked like a loosened tolerance and was in fact a
  tightening; that was only knowable by reading it.

## Corpus and tooling

- 93 official examples: `<scratchpad>/examples/`. Re-fetch from
  <https://github.com/vega/vega/tree/main/docs/examples> if the scratchpad is gone.
- Survey: `./gradlew :vega-runtime:test --tests '*ExampleTriage*' -Dexamples.dir=<dir> --rerun-tasks -q`,
  writes `triage-report.txt` into that directory. `--rerun-tasks` matters; Gradle will otherwise
  call it up to date.
- **Everything is a target now.** The categories that used to be out of reach — geo/projections/
  topojson, the raster family, `now()`/`random()` — were reopened by the owner; see "The three
  refusals are lifted" above for where each stands. Force layout is the one thing nobody has ruled
  on either way: `force-directed-layout` compiles clean today because its layout transform is
  reported and the marks still draw, so it is *quietly* wrong rather than refused.
- **Do not size a decision off a triage node count.** `crossfilter-flights` was written off as
  unfixturable because it "draws 600,098 scene nodes"; upstream draws 171, and the 600,098 was this
  engine drawing every unfiltered row because two transforms were missing. The count measures how
  wrong we are.
