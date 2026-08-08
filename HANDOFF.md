# Handoff

Working brief for whoever picks this up next. Delete it when it stops being true.

## Where things stand

Branch `milestone-0-bootstrap`. Working tree clean, both gates green:

- `./scripts/check.sh` — format, all tests, lint, demo APK
- `./scripts/oracle.sh` — regenerates upstream references and runs the differential comparison

**72 differential fixtures pass, all matching upstream exactly.** That is the only number here
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

`barley-trellis.vg.json` is Vega's `barley-trellis-plot`, with the 120-row dataset inlined because
the fixture harness has no loader (and neither does the oracle). It found four more:

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

## Pick the next example the same way

The method that worked three times: take one real example, add it as a differential fixture *first*, let
it fail, fix what it names, then open `build/fixture-svg/<name>.ours.svg` next to
`build/oracle-reference/<name>.svg` and look at them. The fixture tells you the geometry is right;
only the SVG tells you the chart is. Note that upstream draws a `rect` mark as an SVG `<path>` and
scatters zero-extent `class="background"` and `class="foreground"` paths through its output — strip
those before comparing, they paint nothing.

**Most remaining examples load their data over the network** (`data/movies.json` and friends), which
neither this harness nor the oracle will do — inline the rows, as `barley-trellis` does. Discount the
loader diagnostic when judging how far an example is from passing; it is not an engine gap.

What is left, by the engine gap behind it rather than by the error count:

- `budget-forecasts` — the `argmin`/`argmax` aggregate operations.
- `error-bars` — the `stderr` aggregate operation.
- `probability-density` — a `density` transform taking its extent from its own data for
  distributions other than `kde`.
- `donut-chart-labelled` — the `pluck` expression function, and a dataset sourcing from *several*
  named datasets at once (`"source": ["a", "b"]`), which the parser currently reads as one name.
- `connected-scatter-plot` — nothing but the loader, so inlining its data should either pass on
  arrival or surface something nobody has looked at yet. Cheap either way.
- `histogram-null-values` — a range written as an array whose *elements* are signals,
  `[{"signal": "barStep + nullGap"}, {"signal": "width"}]`. The scale is not built at all, which
  cascades into three more reports about the axes and encodings that referred to it.

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
