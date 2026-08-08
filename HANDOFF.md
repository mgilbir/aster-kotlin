# Handoff

Working brief for whoever picks this up next. Delete it when it stops being true.

## Where things stand

Branch `milestone-0-bootstrap`. Working tree clean, both gates green:

- `./scripts/check.sh` — format, all tests, lint, demo APK
- `./scripts/oracle.sh` — regenerates upstream references and runs the differential comparison

**95 differential fixtures pass, all matching upstream exactly.** That is the only number here
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

**Where the corpus stands, from `ExampleTriage` rather than from memory: 70 of the 93 compile clean,
23 still report errors.** Twenty-two of those 23 are refused by design — eleven geo/topojson, eight
needing `random()` or `now()`, three needing the raster family. **`crossfilter-flights` is the only
non-refused example still reporting an error.**

Two more compile clean but do not yet *match*, and are parked in the scratchpad hold directory with
their data already fetched:

- **`time-units`** — down to one gap and it is a specific one: `"domain": {"data": "flights",
  "field": {"signal": "measure"}}`. A scale domain whose **field name comes from a signal**.
  `DomainSpec.FromField` holds `field: String`, so the object does not parse into a name, the domain
  comes out empty, and every bar is drawn at zero height. Widening that field to a `FieldRef` the way
  `ChannelValue.Scaled.field` already is should be the whole of it. After that, two small residuals:
  one axis tick short of upstream's fourteen, and a surface 3.5 units wide of 600.
- **`calendar-view`** — further out. Its `y` scale domain is a list of `datetime(...)` values and the
  marks come out transposed against upstream's (`x` and `y` swapped), so start by comparing one
  rect's encode against what upstream places. It also wants `timeOffset`, which is now implemented,
  so re-read its diagnostics before assuming anything from this note.

- **`crossfilter-flights`** — needs the `crossfilter` and `resolvefilter` transforms, and **it cannot
  be a fixture as the harness stands**: it draws 600,098 scene nodes and the differential run dies
  with `Java heap space`. Raising the test JVM's heap is a decision for whoever takes it, not
  something to do silently — and its reference JSON would be enormous besides.

**Refused, not missing**, and reported as such: `error-bars`, `bar-line-toggle`, `clock`, `watch`,
`word-cloud`, `serpentine-timeline`, `hypothetical-outcome-plots` and `pi-monte-carlo` all need
`random()` or `now()`. `error-bars` is the subtle one — its `ci0`/`ci1` *look* like ordinary summary
statistics and are a bootstrap over 1,000 random resamples. `serpentine-timeline` is worth naming
too: its scale domain is derived from `now()`, so any reference generated for it would be correct for
one day only.

The scouting trick: copy the candidates into `test-fixtures/specs/` with a `scout-` prefix, generate
references, run the differential once, read the distinct diagnostics, then delete them all. Much
faster than reading specs.

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
- Out of reach by design, so do not count them as targets: ~14 geo/projections/topojson, 3 force
  layout, 4 using `now()`/`random()` (deliberately refused for reproducibility), plus the raster
  family above.
